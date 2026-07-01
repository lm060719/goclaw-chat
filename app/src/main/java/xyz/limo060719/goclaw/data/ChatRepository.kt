package xyz.limo060719.goclaw.data

import android.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.limo060719.goclaw.data.remote.DownloadedFile
import xyz.limo060719.goclaw.data.remote.GoClawApi
import xyz.limo060719.goclaw.data.remote.GoClawWsClient
import xyz.limo060719.goclaw.data.remote.ServerMessage
import xyz.limo060719.goclaw.data.remote.WsChatEvent
import xyz.limo060719.goclaw.domain.model.Attachment
import xyz.limo060719.goclaw.domain.skills.SkillRepository
import javax.inject.Inject

sealed interface ChatEvent {
    /** A reasoning block — attaches to the upcoming/current answer block's bubble. */
    data class Thinking(val text: String) : ChatEvent
    /** A complete answer block (the gateway emits one `chunk` event per reply block). */
    data class AnswerBlock(val text: String) : ChatEvent
    data class ToolInvocation(val name: String, val arguments: String, val result: String) : ChatEvent
    /** Authoritative final answer + reasoning; used only to backfill when nothing streamed. */
    data class AssistantDone(val text: String, val thinking: String = "", val files: List<xyz.limo060719.goclaw.domain.model.FileRef> = emptyList()) : ChatEvent
    data class Error(val message: String) : ChatEvent
}

/** Peels complete `<thinking>…</thinking>` blocks out of a piece of text (defensive; some
 *  providers inline reasoning instead of sending a separate `thinking` event). */
internal object ThinkingTags {
    private val TAG = Regex("(?s)<thinking>(.*?)</thinking>")
    fun extract(s: String): String = TAG.findAll(s).joinToString("\n") { it.groupValues[1].trim() }
    fun strip(s: String): String = TAG.replace(s, "").trim()
}

/**
 * Drives a chat turn over the GoClaw WebSocket gateway. Conversation context is kept
 * server-side and resumed via the session id (like the Telegram channel), so we only ever
 * send the latest user message — no client-side history packing.
 */
class ChatRepository @Inject constructor(
    private val settingsStore: SettingsStore,
    private val ws: GoClawWsClient,
    private val skills: SkillRepository,
    private val api: GoClawApi,
) {
    /**
     * Uploads each image to the backend workspace and returns the server-side paths.
     * Images that fail to upload are skipped. The agent reads these paths with its
     * `read_image` tool.
     */
    suspend fun uploadImages(attachments: List<Attachment>): List<String> {
        if (attachments.isEmpty()) return emptyList()
        val s = settingsStore.current()
        if (!s.isConfigured) return emptyList()
        return attachments.mapNotNull { att ->
            val bytes = runCatching { Base64.decode(att.base64, Base64.NO_WRAP) }.getOrNull()
                ?: return@mapNotNull null
            api.uploadMedia(s, bytes, att.mimeType, "image.jpg").getOrNull()
        }
    }

    /** Uploads arbitrary file bytes; returns the server-side path or null. */
    suspend fun uploadRaw(bytes: ByteArray, mimeType: String, filename: String): String? {
        val s = settingsStore.current()
        if (!s.isConfigured) return null
        return api.uploadMedia(s, bytes, mimeType, filename).getOrNull()
    }

    /** Synthesizes speech for [text] via the backend TTS endpoint (Result carries the failure reason). */
    suspend fun synthesizeSpeech(text: String): Result<ByteArray> {
        val s = settingsStore.current()
        if (!s.isConfigured || text.isBlank()) return Result.failure(IllegalStateException("未配置或空文本"))
        return api.synthesizeTts(s, text)
    }

    /** Downloads a file from the server by its path. */
    suspend fun downloadFile(
        s: GoClawSettings,
        path: String,
        filename: String,
    ): Result<DownloadedFile> = api.downloadFile(s, path, filename.ifBlank { null })

    /**
     * Sends one user message for the conversation [conversationId]. The WebSocket session key
     * is derived deterministically (`agent:{agent}:ws:direct:{conversationId}`) so the same
     * conversation always resumes the same server-side session — multi-turn context, like the
     * Telegram channel. [isFirstMessage] injects the skills prompt once at the start.
     */
    /** Canonical WS session key for a conversation bound to [agentKey]. */
    private fun sessionKeyOf(agentKey: String, conversationId: String): String =
        "agent:${agentKey.ifBlank { "default" }}:ws:direct:$conversationId"

    /** Aborts the in-progress server run for this conversation via `chat.abort`. Best-effort. */
    suspend fun abortRun(conversationId: String, agentKey: String) {
        val s = settingsStore.current()
        if (!s.isConfigured) return
        runCatching { ws.abortRun(s, sessionKeyOf(agentKey.ifBlank { "default" }, conversationId)) }
    }

    /** Deletes the conversation's server-side session + history. Best-effort. */
    suspend fun deleteServerSession(conversationId: String, agentKey: String) {
        val s = settingsStore.current()
        if (!s.isConfigured) return
        runCatching { ws.deleteSession(s, sessionKeyOf(agentKey, conversationId)) }
    }

    /** Server-side transcript for a conversation (source of truth for context). */
    suspend fun fetchServerHistory(conversationId: String, agentKey: String): List<ServerMessage> {
        val s = settingsStore.current()
        if (!s.isConfigured) return emptyList()
        val agent = agentKey.ifBlank { "default" }
        return runCatching { ws.fetchHistory(s, agent, sessionKeyOf(agent, conversationId)) }
            .getOrDefault(emptyList())
    }

    fun run(
        message: String,
        conversationId: String,
        agentKey: String,
        isFirstMessage: Boolean,
        imagePaths: List<String> = emptyList(),
    ): Flow<ChatEvent> = flow {
        val settings = settingsStore.current()
        if (!settings.isConfigured) {
            emit(ChatEvent.Error("尚未配置后端地址 / API 密钥。")); return@flow
        }

        val agent = agentKey.ifBlank { "default" }
        val sessionKey = sessionKeyOf(agent, conversationId)
        val outMessage = if (isFirstMessage) prependSkills(message) else message

        ws.chat(settings, agent, outMessage, sessionKey, imagePaths).collect { ev ->
            when (ev) {
                is WsChatEvent.Thinking -> ev.text.takeIf { it.isNotBlank() }
                    ?.let { emit(ChatEvent.Thinking(it)) }
                is WsChatEvent.Token -> {
                    // One `chunk` event = one complete answer block. Peel any inline <thinking> just in case.
                    ThinkingTags.extract(ev.text).takeIf { it.isNotBlank() }
                        ?.let { emit(ChatEvent.Thinking(it)) }
                    ThinkingTags.strip(ev.text).takeIf { it.isNotBlank() }
                        ?.let { emit(ChatEvent.AnswerBlock(it)) }
                }
                is WsChatEvent.Tool -> emit(ChatEvent.ToolInvocation(ev.name, ev.input, ev.output))
                is WsChatEvent.Done -> {
                    // Final run result carries only the LAST block; used solely to backfill the UI
                    // when no streaming blocks arrived (some providers reply in one shot).
                    val finalText = ThinkingTags.strip(ev.content)
                    val finalThinking = ev.thinking?.takeIf { it.isNotBlank() }
                        ?: ThinkingTags.extract(ev.content).takeIf { it.isNotBlank() }
                        ?: ""
                    val files = ev.media.map { m ->
                        val name = m.filename.ifBlank { m.path.substringAfterLast('/') }.ifBlank { m.url.substringAfterLast('/') }
                        xyz.limo060719.goclaw.domain.model.FileRef(
                            path = m.path.ifBlank { m.url },
                            filename = name,
                            mimeType = m.mimeType.ifBlank { xyz.limo060719.goclaw.domain.model.ToolCard.guessMime(name) },
                        )
                    }.filter { it.filename.contains('.') }
                    emit(ChatEvent.AssistantDone(finalText, finalThinking, files))
                }
                is WsChatEvent.Failed -> emit(ChatEvent.Error(friendlyError(ev.error)))
            }
        }
    }

    private fun prependSkills(message: String): String {
        val sys = skills.systemPrompt()?.takeIf { it.isNotBlank() } ?: return message
        return "$sys\n\n$message"
    }

    /** Turn raw transport errors into a short, actionable message. */
    private fun friendlyError(t: Throwable): String {
        val raw = t.message.orEmpty()
        val isTls = t is javax.net.ssl.SSLException ||
            raw.contains("BAD_RECORD_MAC", true) || raw.contains("DECRYPT", true) ||
            raw.contains("SSL", true)
        return if (isTls) "连接中断(TLS 错误),与服务器的连接被断开,请重试。" else raw.ifBlank { "请求失败" }
    }
}
