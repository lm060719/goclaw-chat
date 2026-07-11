package xyz.limo060719.goclaw.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Defensive peeling of inline <thinking> blocks for providers that don't send a separate event. */
class ThinkingTagsTest {

    @Test fun extract_pulls_reasoning_out_of_tags() {
        assertEquals("reasoning", ThinkingTags.extract("before<thinking>reasoning</thinking>after"))
    }

    @Test fun extract_joins_multiple_blocks_with_newlines() {
        assertEquals("one\ntwo", ThinkingTags.extract("<thinking>one</thinking>x<thinking>two</thinking>"))
    }

    @Test fun extract_handles_multiline_reasoning() {
        assertEquals("line1\nline2", ThinkingTags.extract("<thinking>line1\nline2</thinking>"))
    }

    @Test fun strip_removes_tags_and_trims() {
        assertEquals("visible answer", ThinkingTags.strip("<thinking>hidden</thinking>visible answer"))
    }

    @Test fun strip_leaves_plain_text_untouched() {
        assertEquals("just an answer", ThinkingTags.strip("just an answer"))
    }

    @Test fun extract_returns_empty_when_no_tags() {
        assertEquals("", ThinkingTags.extract("no tags here"))
    }
}
