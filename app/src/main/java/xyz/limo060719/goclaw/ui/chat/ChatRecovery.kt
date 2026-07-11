package xyz.limo060719.goclaw.ui.chat

import xyz.limo060719.goclaw.data.remote.ServerMessage

/**
 * The assistant reply to our *latest* user turn in a server transcript, or null if the server
 * hasn't produced it yet. Pure so it can be unit-tested without a live session.
 *
 * [localUserCount] is how many user turns this device has sent. We locate the transcript entry for
 * our latest user message (the N-th `user` message) and only accept an `assistant` message that
 * comes AFTER it. If the server hasn't yet recorded our latest user message — e.g. the app was
 * backgrounded before `chat.send` landed — there is no such entry, so we return null (and the
 * caller keeps polling) instead of surfacing the *previous* turn's reply as the answer to this one.
 */
internal fun replyForLatestTurn(history: List<ServerMessage>, localUserCount: Int): String? {
    if (localUserCount <= 0) return null
    val ourUserIdx = history
        .mapIndexedNotNull { i, m -> if (m.role == "user") i else null }
        .getOrNull(localUserCount - 1) ?: return null
    return history.drop(ourUserIdx + 1)
        .lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content
}
