package xyz.limo060719.goclaw.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.limo060719.goclaw.data.Conversation
import xyz.limo060719.goclaw.data.ConversationMeta
import xyz.limo060719.goclaw.domain.model.Role
import xyz.limo060719.goclaw.domain.model.ToolCard
import xyz.limo060719.goclaw.domain.model.UiMessage
import xyz.limo060719.goclaw.ui.chat.components.isSingleNewlineInsert
import java.util.Calendar
import java.util.TimeZone

class ConversationToolsTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    // 2026-09-29 12:00 UTC
    private val now = Calendar.getInstance(utc).apply { clear(); set(2026, 8, 29, 12, 0) }.timeInMillis

    private fun meta(id: String, at: Long, pinned: Boolean = false) = ConversationMeta(id, id, at, pinned = pinned)

    /* ---- groupConversations ---- */

    @Test fun groups_by_calendar_day_with_pinned_first() {
        val groups = groupConversations(
            listOf(
                meta("today", now - hour),
                meta("earlyToday", now - 11 * hour), // 01:00 same day
                meta("yesterday", now - 13 * hour), // 23:00 previous day
                meta("week", now - 3 * day),
                meta("old", now - 30 * day),
                meta("pinnedOld", now - 60 * day, pinned = true),
            ),
            now, utc,
        )
        assertEquals(
            listOf(
                ConversationGroup.PINNED to listOf("pinnedOld"),
                ConversationGroup.TODAY to listOf("today", "earlyToday"),
                ConversationGroup.YESTERDAY to listOf("yesterday"),
                ConversationGroup.LAST_7_DAYS to listOf("week"),
                ConversationGroup.OLDER to listOf("old"),
            ),
            groups.map { (g, list) -> g to list.map { it.id } },
        )
    }

    @Test fun empty_groups_are_dropped() {
        val groups = groupConversations(listOf(meta("a", now)), now, utc)
        assertEquals(listOf(ConversationGroup.TODAY), groups.map { it.first })
    }

    /* ---- search ---- */

    @Test fun snippet_is_case_insensitive_with_ellipses() {
        val text = "x".repeat(40) + "Hello World" + "y".repeat(40)
        val s = searchSnippet(text, "hello world")!!
        assertTrue(s.startsWith("…") && s.endsWith("…") && s.contains("Hello World"))
    }

    @Test fun snippet_null_when_absent_or_blank() {
        assertNull(searchSnippet("abc", "zzz"))
        assertNull(searchSnippet("abc", "  "))
    }

    @Test fun search_prefers_title_then_first_matching_message() {
        val m1 = UiMessage(role = Role.USER, text = "no match")
        val m2 = UiMessage(role = Role.ASSISTANT, text = "the kotlin answer")
        val conv = Conversation(id = "c", title = "Chat", messages = listOf(m1, m2))
        assertEquals(SearchHit("c", m2.id, "the kotlin answer"), searchConversation(conv, "Kotlin"))
        assertEquals(SearchHit("c", null, null), searchConversation(conv, "chat"))
        assertNull(searchConversation(conv, "python"))
    }

    /* ---- export ---- */

    @Test fun markdown_export_has_title_roles_and_tools_but_no_thinking() {
        val conv = Conversation(
            title = "My chat", agentKey = "coder",
            messages = listOf(
                UiMessage(role = Role.USER, text = "hi", createdAt = 0),
                UiMessage(
                    role = Role.ASSISTANT, text = "hello", thinking = "secret reasoning",
                    tools = listOf(ToolCard("web_search", "{}", "r")), createdAt = 0,
                ),
            ),
        )
        val md = conversationToMarkdown(conv, "Me", "AI", "Image") { "T" }
        assertTrue(md.startsWith("# My chat\n"))
        assertTrue(md.contains("> Agent: `coder`"))
        assertTrue(md.contains("### Me · T\n\nhi"))
        assertTrue(md.contains("### AI · T\n\n> 🔧 web_search"))
        assertFalse(md.contains("secret reasoning"))
    }

    /* ---- enter-to-send ---- */

    @Test fun single_typed_newline_is_detected_anywhere() {
        assertTrue(isSingleNewlineInsert("abc", "abc\n"))
        assertTrue(isSingleNewlineInsert("abc", "a\nbc"))
        assertTrue(isSingleNewlineInsert("", "\n"))
    }

    @Test fun other_edits_are_not_enter() {
        assertFalse(isSingleNewlineInsert("abc", "abcd"))
        assertFalse(isSingleNewlineInsert("abc", "abc\n\n")) // paste of two newlines
        assertFalse(isSingleNewlineInsert("abc", "ab"))
    }
}
