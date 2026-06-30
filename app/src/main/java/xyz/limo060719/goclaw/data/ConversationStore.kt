package xyz.limo060719.goclaw.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.limo060719.goclaw.data.remote.dto.ApiMessage
import xyz.limo060719.goclaw.domain.model.UiMessage
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight entry used to render the history list in the drawer. */
@Serializable
data class ConversationMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val agentKey: String? = null,
)

/** A full persisted conversation: UI messages for display + wire history to resume. */
@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<UiMessage> = emptyList(),
    val wire: List<ApiMessage> = emptyList(),
    /** Server-side session id (from WebSocket chat.send) — keeps multi-turn context. */
    val serverSessionId: String? = null,
    /** Agent this conversation is bound to (its server session uses this agent key). */
    val agentKey: String? = null,
)

/**
 * File-based conversation history. An `index.json` holds the lightweight metas
 * (cheap to load for the drawer); each conversation's full content lives in
 * its own `<id>.json` and is only read when opened.
 */
@Singleton
class ConversationStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val dir: File by lazy { File(context.filesDir, "conversations").apply { mkdirs() } }
    private val indexFile: File get() = File(dir, "index.json")

    private val _conversations = MutableStateFlow<List<ConversationMeta>>(emptyList())
    val conversations: StateFlow<List<ConversationMeta>> = _conversations.asStateFlow()

    init {
        _conversations.value = loadIndex()
        backfillAgentKeys()
    }

    /** One-time: fill in agent tags for conversations saved before the index tracked them. */
    private fun backfillAgentKeys() {
        val current = _conversations.value
        if (current.none { it.agentKey == null }) return
        var changed = false
        val updated = current.map { meta ->
            if (meta.agentKey == null) {
                val ak = load(meta.id)?.agentKey?.takeIf { it.isNotBlank() }
                if (ak != null) { changed = true; meta.copy(agentKey = ak) } else meta
            } else meta
        }
        if (changed) {
            _conversations.value = updated
            persistIndex()
        }
    }

    private fun loadIndex(): List<ConversationMeta> = runCatching {
        if (!indexFile.exists()) emptyList()
        else json.decodeFromString(ListSerializer(ConversationMeta.serializer()), indexFile.readText())
            .sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    private fun persistIndex() = runCatching {
        indexFile.writeText(
            json.encodeToString(ListSerializer(ConversationMeta.serializer()), _conversations.value),
        )
    }

    fun load(id: String): Conversation? = runCatching {
        val f = File(dir, "$id.json")
        if (!f.exists()) null
        else json.decodeFromString(Conversation.serializer(), f.readText())
    }.getOrNull()

    fun save(conversation: Conversation) {
        runCatching {
            File(dir, "${conversation.id}.json")
                .writeText(json.encodeToString(Conversation.serializer(), conversation))
        }
        val meta = ConversationMeta(
            conversation.id, conversation.title, conversation.updatedAt, conversation.agentKey,
        )
        _conversations.value = (_conversations.value.filterNot { it.id == meta.id } + meta)
            .sortedByDescending { it.updatedAt }
        persistIndex()
    }

    fun rename(id: String, title: String) {
        val clean = title.trim().ifBlank { return }
        load(id)?.let { save(it.copy(title = clean)) }
            ?: run {
                // No content file yet; still update the index entry.
                _conversations.value = _conversations.value
                    .map { if (it.id == id) it.copy(title = clean) else it }
                persistIndex()
            }
    }

    fun delete(id: String) {
        runCatching { File(dir, "$id.json").delete() }
        _conversations.value = _conversations.value.filterNot { it.id == id }
        persistIndex()
    }
}
