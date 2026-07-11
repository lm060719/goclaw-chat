package xyz.limo060719.goclaw.ui.chat

import xyz.limo060719.goclaw.data.remote.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reply-recovery matcher must answer the LATEST turn only — the subtle case is a server
 * transcript that already holds a previous turn's reply while our newest user message hasn't
 * landed yet; it must return null (keep polling), not the stale previous reply.
 */
class ChatRecoveryTest {

    private fun user(c: String) = ServerMessage("user", c)
    private fun asst(c: String) = ServerMessage("assistant", c)

    @Test fun returns_reply_after_our_latest_user_turn() {
        val history = listOf(user("q1"), asst("a1"), user("q2"), asst("a2"))
        assertEquals("a2", replyForLatestTurn(history, localUserCount = 2))
    }

    @Test fun null_when_latest_user_turn_not_yet_recorded() {
        // We've sent 2 user turns locally, but the server only recorded the first (+ its reply).
        val history = listOf(user("q1"), asst("a1"))
        assertNull(replyForLatestTurn(history, localUserCount = 2))
    }

    @Test fun null_when_reply_to_latest_turn_not_produced_yet() {
        val history = listOf(user("q1"), asst("a1"), user("q2"))
        assertNull(replyForLatestTurn(history, localUserCount = 2))
    }

    @Test fun ignores_blank_assistant_content() {
        val history = listOf(user("q1"), asst("   "))
        assertNull(replyForLatestTurn(history, localUserCount = 1))
    }

    @Test fun picks_last_assistant_block_after_the_turn() {
        val history = listOf(user("q1"), asst("partial"), asst("final"))
        assertEquals("final", replyForLatestTurn(history, localUserCount = 1))
    }

    @Test fun null_for_zero_user_turns() {
        assertNull(replyForLatestTurn(listOf(asst("a")), localUserCount = 0))
    }
}
