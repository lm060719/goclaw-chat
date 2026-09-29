package xyz.limo060719.goclaw.ui.chat

import xyz.limo060719.goclaw.data.Conversation
import xyz.limo060719.goclaw.data.ConversationMeta
import xyz.limo060719.goclaw.domain.model.Role
import java.util.Calendar
import java.util.TimeZone

/* Pure helpers behind the drawer (grouping, search) and export — no Android deps, unit-tested. */

enum class ConversationGroup { PINNED, TODAY, YESTERDAY, LAST_7_DAYS, OLDER }

/**
 * Buckets conversations for the drawer: pinned first, then by calendar day of [ConversationMeta.updatedAt]
 * relative to [now]. Each bucket keeps newest-first order; empty buckets are dropped.
 */
fun groupConversations(
    metas: List<ConversationMeta>,
    now: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): List<Pair<ConversationGroup, List<ConversationMeta>>> {
    val startOfToday = Calendar.getInstance(zone).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 24L * 60 * 60 * 1000
    fun groupOf(m: ConversationMeta) = when {
        m.pinned -> ConversationGroup.PINNED
        m.updatedAt >= startOfToday -> ConversationGroup.TODAY
        m.updatedAt >= startOfToday - day -> ConversationGroup.YESTERDAY
        m.updatedAt >= startOfToday - 6 * day -> ConversationGroup.LAST_7_DAYS
        else -> ConversationGroup.OLDER
    }
    return metas.sortedByDescending { it.updatedAt }
        .groupBy(::groupOf)
        .toSortedMap()
        .map { (g, items) -> g to items }
}

/** A drawer search match: [messageId] set when the hit is inside the conversation's messages. */
data class SearchHit(val conversationId: String, val messageId: String?, val snippet: String?)

/** First occurrence of [query] in [text] (case-insensitive) with some context, or null. */
fun searchSnippet(text: String, query: String, context: Int = 18): String? {
    val q = query.trim()
    if (q.isEmpty()) return null
    val at = text.indexOf(q, ignoreCase = true)
    if (at < 0) return null
    val from = (at - context).coerceAtLeast(0)
    val to = (at + q.length + context).coerceAtMost(text.length)
    val body = text.substring(from, to).replace('\n', ' ').trim()
    return (if (from > 0) "…" else "") + body + (if (to < text.length) "…" else "")
}

/** Title match, else the first message containing [query]. */
fun searchConversation(conv: Conversation, query: String): SearchHit? {
    if (conv.title.contains(query.trim(), ignoreCase = true)) return SearchHit(conv.id, null, null)
    for (m in conv.messages) {
        val snippet = searchSnippet(m.text, query) ?: continue
        return SearchHit(conv.id, m.id, snippet)
    }
    return null
}

/**
 * Renders a conversation as a Markdown document for export/sharing. Reasoning and raw tool I/O are
 * left out (noise for a reader); tool calls are listed by name, attachments by placeholder.
 */
fun conversationToMarkdown(
    conv: Conversation,
    userLabel: String,
    assistantLabel: String,
    imageLabel: String,
    formatTime: (Long) -> String,
): String = buildString {
    append("# ").append(conv.title.ifBlank { "GoClaw" }).append("\n\n")
    conv.agentKey?.takeIf { it.isNotBlank() }?.let { append("> Agent: `").append(it).append("`\n\n") }
    for (m in conv.messages) {
        val who = when (m.role) {
            Role.USER -> userLabel
            Role.ASSISTANT -> assistantLabel
            Role.TOOL -> null
        }
        val tools = (m.tools + listOfNotNull(m.tool)).map { it.name }
        if (who == null) {
            tools.forEach { append("> 🔧 ").append(it).append("\n") }
            append("\n")
            continue
        }
        append("### ").append(who).append(" · ").append(formatTime(m.createdAt)).append("\n\n")
        tools.forEach { append("> 🔧 ").append(it).append("\n") }
        if (tools.isNotEmpty()) append("\n")
        repeat(m.attachments.size) { append("[").append(imageLabel).append("]\n") }
        m.fileNames.forEach { append("📎 ").append(it).append("\n") }
        m.files.forEach { append("📎 ").append(it.filename.ifBlank { it.path }).append("\n") }
        if (m.attachments.isNotEmpty() || m.fileNames.isNotEmpty() || m.files.isNotEmpty()) append("\n")
        if (m.text.isNotBlank()) append(m.text.trim()).append("\n\n")
    }
}.trimEnd() + "\n"
