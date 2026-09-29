package xyz.limo060719.goclaw.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParseTest {

    /* ---- splitChunks: the basis of incremental rendering ---- */

    @Test fun chunks_split_on_blank_lines() {
        assertEquals(listOf("a\nb", "c"), splitChunks("a\nb\n\nc"))
    }

    @Test fun code_fence_is_one_chunk_even_with_blank_lines() {
        val text = "intro\n```kt\nval a = 1\n\nval b = 2\n```\nafter"
        assertEquals(listOf("intro", "```kt\nval a = 1\n\nval b = 2\n```", "after"), splitChunks(text))
    }

    @Test fun unclosed_fence_runs_to_end_while_streaming() {
        assertEquals(listOf("x", "```py\nprint(1)\n\nprint(2)"), splitChunks("x\n```py\nprint(1)\n\nprint(2)"))
    }

    @Test fun streaming_append_only_changes_last_chunk() {
        val before = splitChunks("para one\n\npara two\n\npara th")
        val after = splitChunks("para one\n\npara two\n\npara three, more")
        assertEquals(before.dropLast(1), after.dropLast(1))
    }

    /* ---- parseChunk ---- */

    @Test fun closed_fence_parses_lang_and_body() {
        assertEquals(listOf(CodeSegment("val a = 1", "kotlin")), parseChunk("```kotlin\nval a = 1\n```"))
    }

    @Test fun open_fence_keeps_partial_body() {
        assertEquals(listOf(CodeSegment("print(1)", "py")), parseChunk("```py\nprint(1)"))
    }

    @Test fun table_is_detected() {
        val seg = parseChunk("| a | b |\n|---|---|\n| 1 | 2 |").single() as TableSegment
        assertEquals(listOf("a", "b"), seg.header)
        assertEquals(listOf(listOf("1", "2")), seg.rows)
    }

    /* ---- parseBlocks ---- */

    @Test fun nested_list_depths() {
        val items = parseBlocks("- a\n  - b\n    - c\n- d").filterIsInstance<MdListItem>()
        assertEquals(listOf(0, 1, 2, 0), items.map { it.depth })
        assertEquals(listOf("a", "b", "c", "d"), items.map { it.text })
    }

    @Test fun four_space_indent_is_one_level() {
        val items = parseBlocks("1. a\n    - b").filterIsInstance<MdListItem>()
        assertEquals(listOf(0, 1), items.map { it.depth })
        assertEquals("1.", items[0].marker)
    }

    @Test fun task_items() {
        val items = parseBlocks("- [ ] todo\n- [x] done").filterIsInstance<MdListItem>()
        assertEquals(listOf(false, true), items.map { it.checked })
        assertEquals(listOf("todo", "done"), items.map { it.text })
    }

    @Test fun indented_continuation_joins_the_item() {
        val items = parseBlocks("- first line\n  continues here").filterIsInstance<MdListItem>()
        assertEquals("first line continues here", items.single().text)
    }

    @Test fun image_line_becomes_image_block() {
        assertEquals(listOf(MdImage("cat", "https://x.y/cat.png")), parseBlocks("![cat](https://x.y/cat.png)"))
    }

    @Test fun bold_line_is_not_a_bullet() {
        assertTrue(parseBlocks("**bold** text").single() is MdParagraph)
    }

    /* ---- highlightCode ---- */

    private fun kinds(code: String, lang: String) =
        highlightCode(code, lang).map { code.substring(it.start, it.end) to it.kind }

    @Test fun highlights_keywords_strings_comments_numbers() {
        val k = kinds("val s = \"hi\" // note\nreturn 42", "kotlin")
        assertTrue(("val" to TokenKind.KEYWORD) in k)
        assertTrue(("\"hi\"" to TokenKind.STRING) in k)
        assertTrue(("// note" to TokenKind.COMMENT) in k)
        assertTrue(("42" to TokenKind.NUMBER) in k)
    }

    @Test fun python_uses_hash_comments_not_slashes() {
        val k = kinds("x = 1 // 2  # half", "python")
        assertTrue(("# half" to TokenKind.COMMENT) in k)
        assertTrue(k.none { it.first.startsWith("//") })
    }

    @Test fun apostrophe_string_stops_at_line_end() {
        val code = "it's\nval x"
        val k = kinds(code, "kotlin")
        assertTrue(("val" to TokenKind.KEYWORD) in k)
    }

    @Test fun identifiers_containing_digits_are_not_numbers() {
        assertTrue(kinds("abc123", "kotlin").isEmpty())
    }
}
