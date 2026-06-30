package xyz.limo060719.goclaw.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.data.ChatEvent
import xyz.limo060719.goclaw.data.ChatRepository
import xyz.limo060719.goclaw.data.Conversation
import xyz.limo060719.goclaw.data.ConversationMeta
import xyz.limo060719.goclaw.data.ConversationStore
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.domain.model.Attachment
import xyz.limo060719.goclaw.domain.model.FileRef
import xyz.limo060719.goclaw.domain.model.Role
import xyz.limo060719.goclaw.domain.model.ToolCard
import xyz.limo060719.goclaw.domain.model.UiMessage
import xyz.limo060719.goclaw.util.ImageUtil
import xyz.limo060719.goclaw.voice.SpeechManager
import javax.inject.Inject

data class PickedFile(val uri: Uri, val name: String)

@androidx.compose.runtime.Immutable
data class WechatProfile(
    val selfName: String = "",
    val assistantName: String = "",
    val selfAvatar: String = "",
    val assistantAvatar: String = "",
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val input: String = "",
    val attachments: List<Attachment> = emptyList(),
    val files: List<PickedFile> = emptyList(),
    val agent: String = "",
    val isStreaming: Boolean = false,
    val isRecording: Boolean = false,
    val ttsEnabled: Boolean = false,
    val error: String? = null,
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChatRepository,
    private val speech: SpeechManager,
    private val conversationStore: ConversationStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** History list shown in the drawer. */
    val conversations: StateFlow<List<ConversationMeta>> = conversationStore.conversations

    /** Whether to render the chat in WeChat style. */
    val wechatUi: StateFlow<Boolean> = settingsStore.settings
        .map { it.wechatUi }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Names + avatars used by the WeChat-style chat. */
    val wechatProfile: StateFlow<WechatProfile> = settingsStore.settings
        .map { WechatProfile(it.selfName, it.assistantName, it.selfAvatar, it.assistantAvatar) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WechatProfile())

    /** Agents the user saved (for the in-chat quick switcher). */
    val savedAgents: StateFlow<List<String>> = settingsStore.settings
        .map { it.savedAgents }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var currentConversationId: String? = null
    private var currentTitle: String? = null
    /** Agent the current conversation is bound to (fixed once the first message is sent). */
    private var currentAgentKey: String? = null
    /** Latest active agent from settings; used only for brand-new conversations. */
    private var activeAgent: String = ""
    /** The currently-open reply block bubble (reasoning shown, answer pending). */
    private var currentAssistantId: String? = null
    /** True once any streamed block (thinking/answer) appeared this run — disables Done backfill. */
    private var streamedAnyBlock: Boolean = false
    private var streamJob: Job? = null
    private var syncJob: Job? = null

    private val recorder = xyz.limo060719.goclaw.util.AudioRecorder(context)
    private var voiceStart = 0L
    private val audioPlayer = xyz.limo060719.goclaw.util.AudioPlayer(context)
    /** Whether replies are spoken via backend TTS (mirrors settings.ttsBackend). */
    private var ttsBackend = false

    init {
        settingsStore.settings.onEach {
            // Default a new chat to the agent last picked; fall back to the global default.
            val default = it.lastAgent.ifBlank { it.agent }
            activeAgent = default
            ttsBackend = it.ttsBackend
            // Keep the chip on that default while the conversation is fresh & unbound.
            if (currentAgentKey == null && _state.value.messages.isEmpty()) {
                _state.value = _state.value.copy(agent = default)
            }
        }.launchIn(viewModelScope)
    }

    /** Switch the agent for a not-yet-started conversation. Ignored once a message exists. */
    fun selectAgent(key: String) {
        if (_state.value.messages.isNotEmpty()) return
        currentAgentKey = key
        _state.value = _state.value.copy(agent = key)
        // Remember this pick so the next new chat opens with the same agent.
        viewModelScope.launch { settingsStore.updateLastAgent(key) }
    }

    fun onInputChange(text: String) { _state.value = _state.value.copy(input = text) }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    /* ---- message selection / actions ---- */

    fun startSelection(id: String) {
        _state.value = _state.value.copy(selectionMode = true, selectedIds = setOf(id))
    }

    fun toggleSelected(id: String) {
        val cur = _state.value.selectedIds
        val next = if (id in cur) cur - id else cur + id
        _state.value = _state.value.copy(selectionMode = next.isNotEmpty(), selectedIds = next)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectionMode = false, selectedIds = emptySet())
    }

    fun deleteMessages(ids: Set<String>) {
        if (ids.isEmpty()) return
        val remaining = _state.value.messages.filterNot { it.id in ids }
        _state.value = _state.value.copy(
            messages = remaining, selectionMode = false, selectedIds = emptySet(),
        )
        if (remaining.isEmpty()) {
            val cid = currentConversationId
            val ak = currentAgentKey
            if (cid != null && ak != null) {
                viewModelScope.launch { repository.deleteServerSession(cid, ak) }
            }
            cid?.let { conversationStore.delete(it) }
            currentConversationId = null
            currentTitle = null
            currentAgentKey = null
        } else {
            // Local-only delete: the message is removed from this device's view. The server
            // session keeps the full context (so the agent's memory is unaffected).
            persist()
        }
    }

    /** Plain text for the given messages, in display order (for copy / share). */
    fun textOf(ids: Set<String>): String =
        _state.value.messages.filter { it.id in ids }.joinToString("\n\n", transform = ::messageText)

    private fun messageText(m: UiMessage): String = when (m.role) {
        Role.TOOL -> m.tool?.let { "[工具 ${it.name}]\n${it.result}" }.orEmpty()
        else -> m.text
    }

    fun toggleTts() {
        val on = !_state.value.ttsEnabled
        if (!on) { speech.stopSpeaking(); audioPlayer.stop() }
        _state.value = _state.value.copy(ttsEnabled = on)
    }

    /** Speaks a reply via backend TTS when enabled (falling back to on-device), else on-device. */
    private fun speakReply(text: String) {
        if (ttsBackend) {
            viewModelScope.launch {
                val bytes = runCatching { repository.synthesizeSpeech(text) }.getOrNull()
                if (bytes != null) audioPlayer.play(bytes) else speech.speak(text)
            }
        } else {
            speech.speak(text)
        }
    }

    fun addImage(uri: Uri) = viewModelScope.launch {
        val att = ImageUtil.toAttachment(context, uri)
        if (att != null) {
            _state.value = _state.value.copy(attachments = _state.value.attachments + att)
        } else {
            _state.value = _state.value.copy(error = "无法加载图片")
        }
    }

    fun removeAttachment(att: Attachment) {
        _state.value = _state.value.copy(attachments = _state.value.attachments - att)
    }

    fun addFile(uri: Uri) {
        _state.value = _state.value.copy(files = _state.value.files + PickedFile(uri, queryFileName(uri)))
    }

    fun removeFile(f: PickedFile) {
        _state.value = _state.value.copy(files = _state.value.files - f)
    }

    fun downloadAndSaveFile(fileRef: FileRef) {
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(error = "尚未配置后端地址 / API 密钥。")
                return@launch
            }
            _state.value = _state.value.copy(isStreaming = true)

            // Resolve download URL candidates from the documented serving endpoints.
            // Absolute URLs and /v1/ paths are used as-is; workspace-relative paths are served
            // via /v1/files/{path...} or /v1/storage/files/{path...} (GoClaw REST API).
            val raw = fileRef.path.trim()
            val candidates = linkedSetOf<String>()
            when {
                raw.startsWith("http://", true) || raw.startsWith("https://", true) ||
                    raw.startsWith("/v1/") -> candidates.add(raw)
                else -> {
                    val rel = raw.trimStart('/')
                    val noWs = rel.removePrefix("workspace/")
                    candidates.add("/v1/files/$noWs")
                    candidates.add("/v1/storage/files/$noWs")
                    candidates.add("/v1/files/$rel")
                    candidates.add(raw)
                }
            }

            var lastError: Throwable? = null
            for (candidate in candidates) {
                val result = repository.downloadFile(s, candidate, fileRef.filename)
                result.onSuccess { downloaded ->
                    _state.value = _state.value.copy(isStreaming = false)
                    val saved = saveToDownloads(downloaded.filename, downloaded.mimeType, downloaded.bytes)
                    if (saved != null) {
                        _state.value = _state.value.copy(error = "已保存到：$saved")
                    } else {
                        _state.value = _state.value.copy(error = "保存失败")
                    }
                    return@launch
                }.onFailure { lastError = it }
            }

            _state.value = _state.value.copy(
                isStreaming = false,
                error = "下载失败：${lastError?.message ?: "文件不可达"}",
            )
        }
    }

    private fun saveToDownloads(filename: String, mimeType: String, bytes: ByteArray): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/GoClaw")
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    "Downloads/GoClaw/$filename"
                } else null
            } else {
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "GoClaw")
                dir.mkdirs()
                val file = java.io.File(dir, filename)
                file.writeBytes(bytes)
                "Downloads/GoClaw/$filename"
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun queryFileName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "file"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        }
        return name
    }

    private suspend fun uploadFiles(files: List<PickedFile>): List<String> = files.mapNotNull { f ->
        val mime = context.contentResolver.getType(f.uri) ?: "application/octet-stream"
        val bytes = runCatching {
            context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@mapNotNull null
        repository.uploadRaw(bytes, mime, f.name)
    }

    fun newConversation() {
        streamJob?.cancel()
        currentConversationId = null
        currentTitle = null
        currentAgentKey = null
        currentAssistantId = null
        _state.value = ChatUiState(
            agent = activeAgent,
            ttsEnabled = _state.value.ttsEnabled,
        )
    }

    fun openConversation(id: String) {
        val conv = conversationStore.load(id) ?: return
        streamJob?.cancel()
        currentConversationId = conv.id
        currentTitle = conv.title.takeIf { it.isNotBlank() }
        currentAgentKey = conv.agentKey
        currentAssistantId = null
        _state.value = ChatUiState(
            messages = conv.messages.map { it.copy(streaming = false) },
            agent = conv.agentKey ?: activeAgent,
            ttsEnabled = _state.value.ttsEnabled,
        )
        // If the last turn is unanswered/cut off, the server ran it anyway — recover the reply.
        recoverReply()
    }

    /** Called when the app returns to foreground — pick up any reply finished while away. */
    fun syncPending() = recoverReply()

    /**
     * Best-effort recovery of a reply that the server produced after the client lost the stream
     * (disconnect, app kill, backgrounding). Polls the server session for a while, since the
     * run may still be finishing. Appends the reply (if the last turn is the user message) or
     * replaces a cut-off assistant bubble.
     */
    private fun recoverReply() {
        val cid = currentConversationId ?: return
        val ak = currentAgentKey ?: return
        if (_state.value.isStreaming) return
        if (!isPending(_state.value.messages.lastOrNull())) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            repeat(12) {
                if (currentConversationId != cid || _state.value.isStreaming) return@launch
                if (!isPending(_state.value.messages.lastOrNull())) return@launch
                val reply = repository.fetchServerHistory(cid, ak)
                    .lastOrNull { it.role == "user" || it.role == "assistant" }
                    ?.takeIf { it.role == "assistant" && it.content.isNotBlank() }?.content
                if (reply != null) {
                    val last = _state.value.messages.lastOrNull()
                    if (currentConversationId == cid && !_state.value.isStreaming && last != null) {
                        when {
                            last.role == Role.USER ->
                                appendMessage(UiMessage(role = Role.ASSISTANT, text = reply))
                            last.role == Role.ASSISTANT && last.incomplete ->
                                updateMessage(last.id) { it.copy(text = reply, incomplete = false, streaming = false) }
                        }
                        persist()
                    }
                    return@launch
                }
                kotlinx.coroutines.delay(2500)
            }
        }
    }

    private fun isPending(m: UiMessage?): Boolean =
        m != null && (m.role == Role.USER || (m.role == Role.ASSISTANT && m.incomplete))

    fun deleteConversation(id: String) {
        conversationStore.load(id)?.let { conv ->
            conv.agentKey?.let { ak ->
                viewModelScope.launch { repository.deleteServerSession(conv.id, ak) }
            }
        }
        conversationStore.delete(id)
        if (id == currentConversationId) newConversation()
    }

    fun renameConversation(id: String, title: String) {
        conversationStore.rename(id, title)
        if (id == currentConversationId) currentTitle = title.trim().ifBlank { currentTitle }
    }

    /** Save the current conversation (messages + server session id) to disk. */
    private fun persist() {
        val msgs = _state.value.messages
        if (msgs.isEmpty()) return
        val id = currentConversationId ?: java.util.UUID.randomUUID().toString()
            .also { currentConversationId = it }
        val title = currentTitle ?: (msgs.firstOrNull { it.role == Role.USER }
            ?.text?.trim()?.take(30)?.takeIf { it.isNotBlank() } ?: "新对话")
        currentTitle = title
        conversationStore.save(
            Conversation(
                id = id,
                title = title,
                updatedAt = System.currentTimeMillis(),
                messages = msgs.map { it.copy(streaming = false) },
                agentKey = currentAgentKey,
            )
        )
    }

    fun send() {
        val s = _state.value
        val text = s.input.trim()
        val attachments = s.attachments
        val files = s.files
        if ((text.isEmpty() && attachments.isEmpty() && files.isEmpty()) || s.isStreaming) return

        syncJob?.cancel()
        val firstMessage = _state.value.messages.isEmpty()
        // Bind the conversation to the chosen/active agent on its first message; fixed after.
        if (currentAgentKey == null) {
            currentAgentKey = _state.value.agent.ifBlank { activeAgent }.ifBlank { "default" }
        }
        val agentKey = currentAgentKey ?: "default"

        appendMessage(
            UiMessage(role = Role.USER, text = text, attachments = attachments, fileNames = files.map { it.name })
        )
        _state.value = _state.value.copy(
            input = "", attachments = emptyList(), files = emptyList(), isStreaming = true,
        )
        currentAssistantId = null
        streamedAnyBlock = false
        persist()
        val convId = currentConversationId ?: return

        streamJob = viewModelScope.launch {
            val imagePaths =
                if (attachments.isEmpty()) emptyList()
                else runCatching { repository.uploadImages(attachments) }.getOrDefault(emptyList())
            val filePaths = runCatching { uploadFiles(files) }.getOrDefault(emptyList())
            val media = imagePaths + filePaths

            if ((attachments.isNotEmpty() || files.isNotEmpty()) && media.isEmpty()) {
                _state.value = _state.value.copy(isStreaming = false, error = "附件上传失败")
                return@launch
            }

            // Attachments go through chat.send's native `media` field; message stays plain text.
            val message = text.ifBlank {
                if (media.isNotEmpty()) "请查看我发送的附件并处理。" else ""
            }
            repository.run(message, convId, agentKey, firstMessage, media)
                .onEach { ev -> handle(ev) }.collect()
        }
    }

    fun stopStreaming() {
        val cid = currentConversationId
        val ak = currentAgentKey
        streamJob?.cancel()
        finalizeBlock()
        _state.value = _state.value.copy(isStreaming = false)
        // Also tell the server to stop the run — cancelling the flow only drops our socket.
        if (cid != null && ak != null) {
            viewModelScope.launch { repository.abortRun(cid, ak) }
        }
    }

    private fun handle(ev: ChatEvent) {
        when (ev) {
            is ChatEvent.Thinking -> openThinking(ev.text)
            is ChatEvent.AnswerBlock -> completeBlock(ev.text)
            is ChatEvent.ToolInvocation -> {
                finalizeBlock()
                val files = ToolCard.extractFiles(ev.arguments, ev.result)
                android.util.Log.d("GoClawChat", "ToolInvocation name=${ev.name} args=${ev.arguments.take(200)} result=${ev.result.take(200)} files=${files.size}")
                appendMessage(
                    UiMessage(role = Role.TOOL, tool = ToolCard(ev.name, ev.arguments, ev.result, files))
                )
                persist()
            }
            is ChatEvent.AssistantDone -> {
                android.util.Log.d("GoClawChat", "AssistantDone files=${ev.files.size} text=${ev.text.take(200)}")
                // Use explicitly delivered files from media, or extract paths from text.
                // Only match paths with directory components (not bare filenames) to avoid false positives.
                val allFiles = ev.files.ifEmpty {
                    ToolCard.extractFiles("", ev.text).filter {
                        it.path.contains('/') || it.path.contains('\\')
                    }
                }
                android.util.Log.d("GoClawChat", "AssistantDone allFiles=${allFiles.size}")
                if (!streamedAnyBlock) reconcileAssistant(ev.text, ev.thinking, allFiles)
                else if (allFiles.isNotEmpty()) {
                    currentAssistantId?.let { id ->
                        updateMessage(id) { it.copy(files = allFiles) }
                    }
                }
                finalizeBlock()
                _state.value = _state.value.copy(isStreaming = false)
                if (_state.value.ttsEnabled && ev.text.isNotBlank()) speakReply(ev.text)
                persist()
            }
            is ChatEvent.Error -> {
                // A partially-streamed block was cut off — flag it so we can recover the full reply.
                currentAssistantId?.let { id -> updateMessage(id) { it.copy(incomplete = true) } }
                finalizeBlock()
                _state.value = _state.value.copy(isStreaming = false, error = ev.message)
                persist()
                recoverReply()
            }
        }
    }

    /** Opens (once) the single assistant bubble for this turn. */
    private fun ensureBubble(): String {
        currentAssistantId?.let { return it }
        streamedAnyBlock = true
        val msg = UiMessage(role = Role.ASSISTANT, streaming = true)
        currentAssistantId = msg.id
        appendMessage(msg)
        return msg.id
    }

    /** Append one step to a reasoning transcript, separated by a blank line. */
    private fun appendStep(existing: String, piece: String): String {
        val p = piece.trim()
        if (p.isEmpty()) return existing
        return if (existing.isBlank()) p else "$existing\n\n$p"
    }

    /**
     * A new reasoning step. The whole turn lives in ONE bubble: all reasoning AND any intermediate
     * answer blocks are folded into `thinking` as steps; only the LAST answer block stays visible.
     * So when fresh reasoning arrives, the currently-visible answer was intermediate → fold it in.
     */
    private fun openThinking(text: String) {
        val id = ensureBubble()
        updateMessage(id) {
            var t = it.thinking
            if (it.text.isNotBlank()) t = appendStep(t, it.text) // prior answer was intermediate
            it.copy(thinking = appendStep(t, text), text = "", streaming = true)
        }
    }

    /** The latest answer block. Kept visible; folded into `thinking` only if a newer block supersedes it. */
    private fun completeBlock(text: String) {
        val id = ensureBubble()
        updateMessage(id) {
            // Two answer blocks in a row (no reasoning between) → the older one was intermediate.
            val t = if (it.text.isNotBlank()) appendStep(it.thinking, it.text) else it.thinking
            it.copy(thinking = t, text = text, streaming = true)
        }
    }

    private fun finalizeBlock() {
        currentAssistantId?.let { id -> updateMessage(id) { it.copy(streaming = false) } }
        currentAssistantId = null
    }

    /**
     * Backfill a bubble from the final run result — used only when NO streaming blocks arrived
     * (e.g. a provider that returns the whole reply in one shot). When blocks did stream, they are
     * already the source of truth and the final `res "m"` (last block only) must not overwrite them.
     */
    private fun reconcileAssistant(finalText: String, finalThinking: String, files: List<xyz.limo060719.goclaw.domain.model.FileRef> = emptyList()) {
        val id = currentAssistantId
        if (id == null) {
            if (finalText.isNotBlank() || finalThinking.isNotBlank() || files.isNotEmpty()) {
                appendMessage(
                    UiMessage(
                        role = Role.ASSISTANT,
                        text = finalText,
                        thinking = finalThinking,
                        files = files,
                        streaming = false,
                    )
                )
            }
        } else {
            updateMessage(id) {
                it.copy(
                    text = it.text.ifBlank { finalText },
                    thinking = it.thinking.ifBlank { finalThinking },
                    files = if (files.isNotEmpty()) files else it.files,
                    streaming = false,
                )
            }
        }
        currentAssistantId = null
    }

    /* ---- voice message (audio sent to the agent) ---- */

    fun startVoiceMessage() {
        if (_state.value.isStreaming || _state.value.isRecording) return
        if (recorder.start()) {
            voiceStart = System.currentTimeMillis()
            _state.value = _state.value.copy(isRecording = true)
        } else {
            _state.value = _state.value.copy(error = "无法开始录音，请检查麦克风权限")
        }
    }

    fun cancelVoiceMessage() {
        recorder.cancel()
        _state.value = _state.value.copy(isRecording = false)
    }

    fun finishVoiceMessage() {
        if (!_state.value.isRecording) return
        val tooShort = System.currentTimeMillis() - voiceStart < 800
        val file = recorder.stop()
        _state.value = _state.value.copy(isRecording = false)
        if (file == null) return
        if (tooShort || file.length() < 1500) {
            file.delete()
            _state.value = _state.value.copy(error = "说话时间太短")
            return
        }
        sendVoice(file)
    }

    private fun sendVoice(file: java.io.File) {
        if (_state.value.isStreaming) { file.delete(); return }
        syncJob?.cancel()
        val firstMessage = _state.value.messages.isEmpty()
        if (currentAgentKey == null) {
            currentAgentKey = _state.value.agent.ifBlank { activeAgent }.ifBlank { "default" }
        }
        val agentKey = currentAgentKey ?: "default"
        appendMessage(UiMessage(role = Role.USER, fileNames = listOf("🎤 语音")))
        _state.value = _state.value.copy(isStreaming = true)
        currentAssistantId = null
        streamedAnyBlock = false
        persist()
        val convId = currentConversationId ?: return
        streamJob = viewModelScope.launch {
            val path = runCatching { file.readBytes() }.getOrNull()
                ?.let { repository.uploadRaw(it, "audio/mp4", "voice.m4a") }
            file.delete()
            if (path == null) {
                _state.value = _state.value.copy(isStreaming = false, error = "语音上传失败")
                return@launch
            }
            repository.run("请把这条语音转写成文字并回复。", convId, agentKey, firstMessage, listOf(path))
                .onEach { ev -> handle(ev) }.collect()
        }
    }

    /* ---- list helpers ---- */

    private fun appendMessage(msg: UiMessage) {
        _state.value = _state.value.copy(messages = _state.value.messages + msg)
    }

    private fun updateMessage(id: String, transform: (UiMessage) -> UiMessage) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { if (it.id == id) transform(it) else it }
        )
    }

    override fun onCleared() {
        speech.shutdown()
        recorder.cancel()
        audioPlayer.release()
        super.onCleared()
    }
}
