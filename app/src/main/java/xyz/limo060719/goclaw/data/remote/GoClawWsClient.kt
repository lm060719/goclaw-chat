package xyz.limo060719.goclaw.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.GoClawSettings
import xyz.limo060719.goclaw.data.remote.dto.AgentInfo
import javax.inject.Inject

/** A message from the server-side session transcript (chat.history). */
data class ServerMessage(val role: String, val content: String)

/** A file delivered by the agent (via the `media`/`files` field of the final result). */
data class WsMediaItem(
    val path: String = "",
    val url: String = "",
    val filename: String = "",
    val mimeType: String = "",
)

/** A pending shell-command approval request (exec.approval.*). */
data class ExecApproval(
    val id: String,
    val command: String = "",
    val agentId: String = "",
    val cwd: String = "",
    val reason: String = "",
)

/**
 * A device pairing entry (device.pair.list). A pairing may be pending (has a [code] awaiting
 * approval) or approved (bound to a [channel]/[senderId], which is what `device.pair.revoke` needs).
 */
data class DevicePairing(
    val code: String = "",
    val channel: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val approvedBy: String = "",
    /** "pending" or "approved" (best-effort — derived from the payload or its containing array). */
    val status: String = "",
    val createdAt: String = "",
) {
    val isApproved: Boolean get() = status.contains("approv", ignoreCase = true)
    val isPending: Boolean get() = status.contains("pend", ignoreCase = true)
    /** Stable identity for list keys (code for pending, channel:senderId for approved). */
    val stableKey: String get() = code.ifBlank { "$channel:$senderId" }
}

/** An agent's heartbeat configuration (heartbeat.get / heartbeat.set). */
data class HeartbeatConfig(
    val enabled: Boolean = false,
    val intervalSec: Int = 300,
    val prompt: String = "",
    val providerName: String = "",
    val model: String = "",
)

/** One heartbeat execution log entry (heartbeat.logs). */
data class HeartbeatLog(
    val id: String = "",
    val status: String = "",
    val message: String = "",
    val createdAt: String = "",
)

/** A heartbeat notification delivery target (heartbeat.targets). */
data class HeartbeatTarget(
    val channel: String = "",
    val target: String = "",
    val label: String = "",
)

/** An API key (api_keys.list). The raw secret is only ever returned once, at creation. */
data class ApiKeyInfo(
    val id: String,
    val name: String = "",
    val scopes: List<String> = emptyList(),
    val prefix: String = "",
    val ownerId: String = "",
    val createdAt: String = "",
    val expiresAt: String = "",
    val revoked: Boolean = false,
)

/** Result of api_keys.create: the new key's id and its one-time raw secret (field `key`). */
data class CreatedApiKey(val id: String, val key: String)

/** A single entry from the live server log stream (logs.tail). */
data class LogLine(
    val level: String = "",
    val message: String = "",
    val timestamp: String = "",
    val source: String = "",
)

/** A server-side chat session (sessions.list). */
data class SessionSummary(
    val key: String,
    val title: String = "",
    val agent: String = "",
    val messageCount: Int = 0,
    val updated: String = "",
)

/** Events emitted while a single chat.send round runs over the WebSocket. */
sealed interface WsChatEvent {
    /** A reasoning/extended-thinking block (gateway sends one full block per step). */
    data class Thinking(val text: String) : WsChatEvent
    data class Token(val text: String) : WsChatEvent
    data class Tool(val name: String, val input: String, val output: String) : WsChatEvent
    /** Final assistant text + reasoning + delivered files + the server session id to persist. */
    data class Done(
        val content: String,
        val sessionId: String?,
        val thinking: String = "",
        val media: List<WsMediaItem> = emptyList(),
    ) : WsChatEvent
    data class Failed(val error: Throwable) : WsChatEvent
}

/**
 * Talks to the GoClaw gateway WebSocket (`/ws`). Each [chat] call opens a short-lived
 * connection: connect → chat.send → collect streaming events → final response, then closes.
 * Multi-turn context is preserved by reusing the returned `sessionId` (NOT by keeping the
 * socket open), exactly like the dashboard and the Telegram channel.
 */
class GoClawWsClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: GoClawHttp,
) {
    /**
     * A failure that happened BEFORE `chat.send` was dispatched (TCP/TLS setup, connect
     * handshake, or the handshake watchdog). The message never reached the server, so the
     * attempt can be retried without duplicating the user's message.
     */
    private class PreSendFailure(cause: Throwable) : Exception(cause.message, cause)

    /**
     * One chat round with automatic connection resilience: failures during the connect phase
     * (before `chat.send` is dispatched — transient network blips, TLS hiccups, a stalled
     * handshake) are retried transparently with backoff. Failures after the message was sent
     * are NOT retried (the server may already be running the turn); they surface as [WsChatEvent.Failed]
     * and the caller's history-recovery picks the reply up.
     */
    fun chat(
        settings: GoClawSettings,
        agentId: String,
        message: String,
        sessionKey: String,
        mediaPaths: List<String> = emptyList(),
    ): Flow<WsChatEvent> = flow {
        var attempt = 0
        while (true) {
            var retriable: Throwable? = null
            chatAttempt(settings, agentId, message, sessionKey, mediaPaths).collect { ev ->
                if (ev is WsChatEvent.Failed && ev.error is PreSendFailure) {
                    if (attempt < MAX_CONNECT_RETRIES) retriable = ev.error
                    else emit(WsChatEvent.Failed(ev.error.cause ?: ev.error))
                } else {
                    emit(ev)
                }
            }
            if (retriable == null) return@flow
            attempt++
            delay(CONNECT_RETRY_DELAY_MS * attempt)
        }
    }

    private fun chatAttempt(
        settings: GoClawSettings,
        agentId: String,
        message: String,
        sessionKey: String,
        mediaPaths: List<String> = emptyList(),
    ): Flow<WsChatEvent> = callbackFlow {
        val pendingToolInput = HashMap<String, String>()
        val accumulated = StringBuilder()
        val accumulatedThinking = StringBuilder()
        /** Set once `chat.send` goes out — failures after this must not be retried. */
        val sendDispatched = AtomicBoolean(false)
        /** Set once a terminal event (Done/Failed) was emitted, so a following socket
         *  close isn't misreported as a mid-stream drop. */
        val finished = AtomicBoolean(false)

        val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val connect = buildJsonObject {
                    put("type", "req"); put("id", "c"); put("method", "connect")
                    putJsonObject("params") {
                        put("token", settings.apiKey)
                        put("user_id", settings.userId.ifBlank { "system" })
                    }
                }
                webSocket.send(connect.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = http.json.parseToJsonElement(text).jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "res" -> handleRes(webSocket, obj)
                        "event" -> handleEvent(obj)
                    }
                }
            }

            private fun handleRes(webSocket: WebSocket, obj: JsonObject) {
                val id = obj["id"]?.jsonPrimitive?.content
                val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                when (id) {
                    "c" -> {
                        if (ok) {
                            val send = buildJsonObject {
                                put("type", "req"); put("id", "m"); put("method", "chat.send")
                                putJsonObject("params") {
                                    put("agentId", agentId)
                                    put("message", message)
                                    if (sessionKey.isNotBlank()) put("sessionKey", sessionKey)
                                    if (mediaPaths.isNotEmpty()) {
                                        putJsonArray("media") {
                                            mediaPaths.forEach { p ->
                                                addJsonObject {
                                                    put("path", p)
                                                    put("filename", p.substringAfterLast('/').ifBlank { "image.jpg" })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            sendDispatched.set(true)
                            webSocket.send(send.toString())
                        } else {
                            // A definitive server rejection (bad token etc.) — retrying won't help.
                            finished.set(true)
                            trySend(WsChatEvent.Failed(IllegalStateException(errorMessage(obj) ?: context.getString(R.string.err_connect_failed))))
                            webSocket.close(1000, null)
                        }
                    }
                    "m" -> {
                        finished.set(true)
                        if (ok) {
                            val payload = obj["payload"]?.jsonObject
                            val content = payload?.get("content")?.jsonPrimitive?.content
                                ?.takeIf { it.isNotBlank() } ?: accumulated.toString()
                            val thinking = payload?.get("thinking")?.jsonPrimitive?.content
                                ?.takeIf { it.isNotBlank() } ?: accumulatedThinking.toString()
                            val media = parseMedia(payload)
                            trySend(WsChatEvent.Done(content, extractSessionId(payload, obj), thinking, media))
                        } else {
                            trySend(WsChatEvent.Failed(IllegalStateException(errorMessage(obj) ?: context.getString(R.string.err_request_failed))))
                        }
                        webSocket.close(1000, null)
                    }
                }
            }

            private fun handleEvent(obj: JsonObject) {
                val payload = obj["payload"]?.jsonObject
                when (obj["event"]?.jsonPrimitive?.content) {
                    // Real gateway frames: event "agent", payload.type in {thinking, chunk, message,
                    // tool.call, tool.result, run.*}, with the actual data nested in payload.payload.
                    // "chat" is kept as a fallback for older/flat frames.
                    "agent", "chat" -> {
                        val inner = payload?.get("payload")?.jsonObject
                        when (payload?.get("type")?.jsonPrimitive?.content) {
                            "thinking" -> {
                                val t = strP(inner, "content", "text").ifBlank { strP(payload, "content", "text") }
                                if (t.isNotBlank()) {
                                    if (accumulatedThinking.isNotEmpty()) accumulatedThinking.append("\n\n")
                                    accumulatedThinking.append(t)
                                    trySend(WsChatEvent.Thinking(t))
                                }
                            }
                            "chunk" -> {
                                val c = strP(inner, "content", "text").ifBlank { strP(payload, "content", "text") }
                                if (c.isNotBlank()) {
                                    accumulated.append(c)
                                    trySend(WsChatEvent.Token(c))
                                }
                            }
                            "tool.call" -> {
                                val name = strP(inner, "tool", "toolName", "name")
                                    .ifBlank { strP(payload, "tool", "toolName", "name") }
                                val input = strA(inner, "input", "toolInput", "arguments", "args")
                                    .ifBlank { strA(payload, "input", "toolInput", "arguments", "args") }
                                if (name.isNotBlank()) pendingToolInput[name] = input
                            }
                            "tool.result" -> {
                                val name = strP(inner, "tool", "toolName", "name")
                                    .ifBlank { strP(payload, "tool", "toolName", "name") }
                                val output = strA(inner, "output", "result", "content")
                                    .ifBlank { strA(payload, "output", "result", "content") }
                                trySend(WsChatEvent.Tool(name, pendingToolInput.remove(name).orEmpty(), output))
                            }
                            else -> {
                                // Older flat "chat" frames: payload.delta / payload.chunk.
                                val delta = strP(payload, "delta", "chunk")
                                if (delta.isNotBlank()) {
                                    accumulated.append(delta)
                                    trySend(WsChatEvent.Token(delta))
                                }
                            }
                        }
                    }
                    // Fallback: some gateway versions emit tool events as the top-level event name.
                    "tool.call" -> {
                        val name = strP(payload, "tool", "toolName", "name")
                        val input = strA(payload, "input", "toolInput", "arguments")
                        if (name.isNotBlank()) pendingToolInput[name] = input
                    }
                    "tool.result" -> {
                        val name = strP(payload, "tool", "toolName", "name")
                        val output = strA(payload, "output", "result")
                        trySend(WsChatEvent.Tool(name, pendingToolInput.remove(name).orEmpty(), output))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (finished.compareAndSet(false, true)) {
                    trySend(WsChatEvent.Failed(if (sendDispatched.get()) t else PreSendFailure(t)))
                }
                close()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                emitDropIfUnfinished()
                webSocket.close(1000, null); close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                emitDropIfUnfinished()
                close()
            }

            /**
             * The server closed the socket without a final `chat.send` response (gateway
             * restart, idle kill, proxy timeout). Without this the flow would just end and
             * the UI would spin forever — surface it as a failure so the caller's
             * history-recovery kicks in.
             */
            private fun emitDropIfUnfinished() {
                if (finished.compareAndSet(false, true)) {
                    val cause = IOException(context.getString(R.string.err_conn_interrupted))
                    trySend(WsChatEvent.Failed(if (sendDispatched.get()) cause else PreSendFailure(cause)))
                }
            }
        }

        val ws = http.wsClient.newWebSocket(request, listener)
        // Handshake watchdog: if the connect round-trip hasn't completed shortly, the socket
        // is likely half-dead — cancel it, which surfaces as a retriable pre-send failure.
        launch {
            delay(CONNECT_WATCHDOG_MS)
            if (!sendDispatched.get() && !finished.get()) ws.cancel()
        }
        awaitClose { ws.cancel() }
    }

    /**
     * Streams live server logs via `logs.tail`. Unlike the one-shot RPCs, this keeps the socket
     * open: connect → `logs.tail{action:"start"}` → emit each pushed log event → on cancellation
     * `logs.tail{action:"stop"}` then close. [level] optionally filters by minimum severity.
     */
    fun tailLogs(settings: GoClawSettings, level: String? = null): Flow<LogLine> = callbackFlow {
        val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(connectFrame(settings))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = http.json.parseToJsonElement(text).jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "res" -> {
                            if (obj["id"]?.jsonPrimitive?.content == "c") {
                                if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                                    webSocket.send(
                                        buildJsonObject {
                                            put("type", "req"); put("id", "lt"); put("method", "logs.tail")
                                            putJsonObject("params") {
                                                put("action", "start")
                                                if (!level.isNullOrBlank()) put("level", level)
                                            }
                                        }.toString()
                                    )
                                } else {
                                    close(IllegalStateException(errorMessage(obj) ?: context.getString(R.string.err_connect_failed)))
                                }
                            }
                        }
                        "event" -> parseLogEvent(obj)?.let { trySend(it) }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null); close()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        }

        val ws = http.wsClient.newWebSocket(request, listener)
        awaitClose {
            // Politely tell the server to stop the stream before dropping the socket.
            runCatching {
                ws.send(
                    buildJsonObject {
                        put("type", "req"); put("id", "ls"); put("method", "logs.tail")
                        putJsonObject("params") { put("action", "stop") }
                    }.toString()
                )
            }
            ws.cancel()
        }
    }

    /** Fetches the server-side transcript for a session via `chat.history`. */
    suspend fun fetchHistory(
        settings: GoClawSettings,
        agentId: String,
        sessionKey: String,
    ): List<ServerMessage> = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    buildJsonObject {
                        put("type", "req"); put("id", "c"); put("method", "connect")
                        putJsonObject("params") {
                            put("token", settings.apiKey)
                            put("user_id", settings.userId.ifBlank { "system" })
                        }
                    }.toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = http.json.parseToJsonElement(text).jsonObject
                    if (obj["type"]?.jsonPrimitive?.content != "res") return
                    val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                    when (obj["id"]?.jsonPrimitive?.content) {
                        "c" -> if (ok) webSocket.send(
                            buildJsonObject {
                                put("type", "req"); put("id", "h"); put("method", "chat.history")
                                putJsonObject("params") {
                                    put("agentId", agentId)
                                    put("sessionKey", sessionKey)
                                }
                            }.toString()
                        ) else finish(webSocket, emptyList())
                        "h" -> {
                            val arr = obj["payload"]?.jsonObject?.get("messages") as? JsonArray
                            val list = arr?.mapNotNull { el ->
                                val o = el as? JsonObject ?: return@mapNotNull null
                                val role = (o["role"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                                val content = (o["content"] as? JsonPrimitive)?.content.orEmpty()
                                ServerMessage(role, content)
                            }.orEmpty()
                            finish(webSocket, list)
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                finish(webSocket, emptyList())

            private fun finish(webSocket: WebSocket, result: List<ServerMessage>) {
                webSocket.close(1000, null)
                if (done.compareAndSet(false, true) && cont.isActive) cont.resume(result)
            }
        }
        val ws = http.wsClient.newWebSocket(request, listener)
        cont.invokeOnCancellation { ws.cancel() }
    }

    /** Deletes a server-side session (and its history) via `sessions.delete`. Best-effort. */
    suspend fun deleteSession(settings: GoClawSettings, sessionKey: String): Boolean =
        sessionKeyCall(settings, "sessions.delete", sessionKey)

    private suspend fun sessionKeyCall(
        settings: GoClawSettings,
        method: String,
        sessionKey: String,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    buildJsonObject {
                        put("type", "req"); put("id", "c"); put("method", "connect")
                        putJsonObject("params") {
                            put("token", settings.apiKey)
                            put("user_id", "system")  // management op: connect as owner, not the chat user
                        }
                    }.toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = http.json.parseToJsonElement(text).jsonObject
                    if (obj["type"]?.jsonPrimitive?.content != "res") return
                    val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                    when (obj["id"]?.jsonPrimitive?.content) {
                        "c" -> if (ok) webSocket.send(
                            buildJsonObject {
                                put("type", "req"); put("id", "d"); put("method", method)
                                putJsonObject("params") { put("key", sessionKey) }
                            }.toString()
                        ) else finish(webSocket, false)
                        "d" -> finish(webSocket, ok)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                finish(webSocket, false)

            private fun finish(webSocket: WebSocket, ok: Boolean) {
                webSocket.close(1000, null)
                if (done.compareAndSet(false, true) && cont.isActive) cont.resume(ok)
            }
        }
        val ws = http.wsClient.newWebSocket(request, listener)
        cont.invokeOnCancellation { ws.cancel() }
    }

    /** Lists pending shell-command approvals via `exec.approval.list`. Tolerant of payload shape. */
    suspend fun listExecApprovals(settings: GoClawSettings): List<ExecApproval> =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        buildJsonObject {
                            put("type", "req"); put("id", "c"); put("method", "connect")
                            putJsonObject("params") {
                                put("token", settings.apiKey)
                                put("user_id", "system")  // management op: connect as owner, not the chat user
                            }
                        }.toString()
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val obj = http.json.parseToJsonElement(text).jsonObject
                        if (obj["type"]?.jsonPrimitive?.content != "res") return
                        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                        when (obj["id"]?.jsonPrimitive?.content) {
                            "c" -> if (ok) webSocket.send(
                                buildJsonObject {
                                    put("type", "req"); put("id", "a"); put("method", "exec.approval.list")
                                    putJsonObject("params") {}
                                }.toString()
                            ) else finish(webSocket, emptyList())
                            "a" -> finish(webSocket, if (ok) parseApprovals(obj["payload"]) else emptyList())
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    finish(webSocket, emptyList())

                private fun finish(webSocket: WebSocket, result: List<ExecApproval>) {
                    webSocket.close(1000, null)
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resume(result)
                }
            }
            val ws = http.wsClient.newWebSocket(request, listener)
            cont.invokeOnCancellation { ws.cancel() }
        }

    /** Approves or denies a pending command via `exec.approval.approve` / `exec.approval.deny`. */
    suspend fun resolveExecApproval(
        settings: GoClawSettings,
        id: String,
        approve: Boolean,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val method = if (approve) "exec.approval.approve" else "exec.approval.deny"
        val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    buildJsonObject {
                        put("type", "req"); put("id", "c"); put("method", "connect")
                        putJsonObject("params") {
                            put("token", settings.apiKey)
                            put("user_id", "system")  // management op: connect as owner, not the chat user
                        }
                    }.toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = http.json.parseToJsonElement(text).jsonObject
                    if (obj["type"]?.jsonPrimitive?.content != "res") return
                    val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                    when (obj["id"]?.jsonPrimitive?.content) {
                        "c" -> if (ok) webSocket.send(
                            buildJsonObject {
                                put("type", "req"); put("id", "r"); put("method", method)
                                // The gateway binds whichever id key it recognizes.
                                putJsonObject("params") {
                                    put("id", id); put("approvalId", id); put("requestId", id)
                                }
                            }.toString()
                        ) else finish(webSocket, false)
                        "r" -> finish(webSocket, ok)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                finish(webSocket, false)

            private fun finish(webSocket: WebSocket, ok: Boolean) {
                webSocket.close(1000, null)
                if (done.compareAndSet(false, true) && cont.isActive) cont.resume(ok)
            }
        }
        val ws = http.wsClient.newWebSocket(request, listener)
        cont.invokeOnCancellation { ws.cancel() }
    }

    /** Approves a pending pairing via `device.pair.approve`. */
    suspend fun approvePairing(settings: GoClawSettings, code: String, approvedBy: String): Boolean =
        rpcBool(settings, "device.pair.approve") {
            put("code", code)
            put("approvedBy", approvedBy.ifBlank { settings.userId.ifBlank { "system" } })
        }

    /** Rejects a pending pairing via `device.pair.deny`. */
    suspend fun denyPairing(settings: GoClawSettings, code: String): Boolean =
        rpcBool(settings, "device.pair.deny") { put("code", code) }

    /** Revokes an approved pairing via `device.pair.revoke`. */
    suspend fun revokePairing(settings: GoClawSettings, channel: String, senderId: String): Boolean =
        rpcBool(settings, "device.pair.revoke") { put("channel", channel); put("senderId", senderId) }

    /** Requests a pairing code via `device.pair.request`. Returns the code, or null on failure. */
    suspend fun requestPairing(settings: GoClawSettings, channel: String, chatId: String): String? {
        val payload = rpcPayload(settings, "device.pair.request") {
            put("channel", channel); put("chatId", chatId)
        } as? JsonObject ?: return null
        return strP(payload, "code", "pairingCode", "pair_code", "pairCode").ifBlank { null }
    }

    /** Lists pending and approved pairings via `device.pair.list`. Tolerant of payload shape. */
    suspend fun listPairings(settings: GoClawSettings): List<DevicePairing> =
        parsePairings(rpcPayload(settings, "device.pair.list") {})

    /* ---- Heartbeat (heartbeat.*) ---- */

    /** Reads an agent's heartbeat config via `heartbeat.get`. */
    suspend fun getHeartbeat(settings: GoClawSettings, agentId: String): HeartbeatConfig? =
        parseHeartbeatConfig(rpcPayload(settings, "heartbeat.get") { put("agentId", agentId) })

    /** Creates/updates an agent's heartbeat config via `heartbeat.set` (intervalSec is floored at 300). */
    suspend fun setHeartbeat(settings: GoClawSettings, agentId: String, config: HeartbeatConfig): Boolean =
        rpcBool(settings, "heartbeat.set") {
            put("agentId", agentId)
            put("enabled", config.enabled)
            put("intervalSec", config.intervalSec.coerceAtLeast(300))
            if (config.prompt.isNotBlank()) put("prompt", config.prompt)
            if (config.providerName.isNotBlank()) put("providerName", config.providerName)
            if (config.model.isNotBlank()) put("model", config.model)
        }

    /** Enables/disables an agent's heartbeat via `heartbeat.toggle`. */
    suspend fun toggleHeartbeat(settings: GoClawSettings, agentId: String, enabled: Boolean): Boolean =
        rpcBool(settings, "heartbeat.toggle") { put("agentId", agentId); put("enabled", enabled) }

    /** Triggers one heartbeat run immediately via `heartbeat.test`. */
    suspend fun testHeartbeat(settings: GoClawSettings, agentId: String): Boolean =
        rpcBool(settings, "heartbeat.test") { put("agentId", agentId) }

    /** Lists heartbeat execution logs via `heartbeat.logs`. */
    suspend fun heartbeatLogs(
        settings: GoClawSettings,
        agentId: String,
        limit: Int = 50,
        offset: Int = 0,
    ): List<HeartbeatLog> = parseHeartbeatLogs(
        rpcPayload(settings, "heartbeat.logs") {
            put("agentId", agentId); put("limit", limit); if (offset > 0) put("offset", offset)
        }
    )

    /** Reads the agent's HEARTBEAT.md context file via `heartbeat.checklist.get`. */
    suspend fun getHeartbeatChecklist(settings: GoClawSettings, agentId: String): String? =
        rpcPayload(settings, "heartbeat.checklist.get") { put("agentId", agentId) }?.let { parseChecklist(it) }

    /** Writes/replaces the agent's HEARTBEAT.md context file via `heartbeat.checklist.set`. */
    suspend fun setHeartbeatChecklist(settings: GoClawSettings, agentId: String, content: String): Boolean =
        rpcBool(settings, "heartbeat.checklist.set") { put("agentId", agentId); put("content", content) }

    /** Lists heartbeat notification delivery targets via `heartbeat.targets`. */
    suspend fun heartbeatTargets(settings: GoClawSettings, agentId: String): List<HeartbeatTarget> =
        parseHeartbeatTargets(rpcPayload(settings, "heartbeat.targets") { put("agentId", agentId) })

    /* ---- API keys (api_keys.*) ---- */

    /** Lists API keys via `api_keys.list`. */
    suspend fun listApiKeys(settings: GoClawSettings): List<ApiKeyInfo> =
        parseApiKeys(rpcPayload(settings, "api_keys.list") {})

    /**
     * Creates an API key via `api_keys.create`. Returns the raw secret, which the server only ever
     * returns once (at creation) — or null on failure.
     */
    suspend fun createApiKey(
        settings: GoClawSettings,
        name: String,
        scopes: List<String>,
        expiresIn: Int? = null,
    ): CreatedApiKey? {
        val payload = rpcPayload(settings, "api_keys.create") {
            put("name", name)
            putJsonArray("scopes") { scopes.forEach { add(it) } }
            if (expiresIn != null) put("expires_in", expiresIn)
        } as? JsonObject ?: return null
        val raw = strP(payload, "key", "apiKey", "api_key", "raw", "rawKey", "token", "secret")
            .ifBlank { strP(payload["data"] as? JsonObject, "key", "apiKey", "api_key", "token") }
            .ifBlank { strP(payload["key"] as? JsonObject, "value", "raw", "secret") }
        if (raw.isBlank()) return null
        return CreatedApiKey(id = strP(payload, "id", "keyId", "key_id"), key = raw)
    }

    /** Revokes an API key via `api_keys.revoke`. */
    suspend fun revokeApiKey(settings: GoClawSettings, id: String): Boolean =
        rpcBool(settings, "api_keys.revoke") { put("id", id) }

    /**
     * Lists agents via WS `agents.list`. The REST `/v1/agents` only returns a filtered subset
     * (often just the default agent), so the dashboard/heartbeat use this to see all agents.
     */
    suspend fun listAgents(settings: GoClawSettings): List<AgentInfo> {
        // connectFrame connects as "system", so this returns ALL agents regardless of the chat user_id.
        val payload = rpcPayload(settings, "agents.list") {} ?: return emptyList()
        val arr = when (payload) {
            is JsonArray -> payload
            is JsonObject -> (payload["agents"] ?: payload["data"] ?: payload["items"]
                ?: payload.values.firstOrNull { it is JsonArray }) as? JsonArray
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { runCatching { http.json.decodeFromJsonElement(AgentInfo.serializer(), it) }.getOrNull() }
    }

    /** Aborts the in-progress run for a session via `chat.abort`. Best-effort. */
    suspend fun abortRun(settings: GoClawSettings, sessionKey: String): Boolean =
        rpcBool(settings, "chat.abort") { put("sessionKey", sessionKey) }

    /** Clears a session's history via `sessions.reset`. */
    suspend fun resetSession(settings: GoClawSettings, key: String): Boolean =
        rpcBool(settings, "sessions.reset") { put("key", key) }

    /** Changes an agent's model (and optionally provider) server-side via `agents.update`. */
    suspend fun updateAgentModel(
        settings: GoClawSettings,
        agentId: String,
        model: String,
        provider: String? = null,
    ): Boolean = rpcBool(settings, "agents.update") {
        put("agentId", agentId)
        put("model", model)
        if (!provider.isNullOrBlank()) put("provider", provider)
    }

    /** Truncates a session's history, keeping the last [keepLast] messages, via `sessions.compact`. */
    suspend fun compactSession(settings: GoClawSettings, key: String, keepLast: Int = 4): Boolean =
        rpcBool(settings, "sessions.compact") { put("key", key); put("keepLast", keepLast) }

    /** Lists server-side sessions via `sessions.list` (optionally scoped to an agent). */
    suspend fun listSessions(settings: GoClawSettings, agentId: String? = null): List<SessionSummary> =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(connectFrame(settings))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val obj = http.json.parseToJsonElement(text).jsonObject
                        if (obj["type"]?.jsonPrimitive?.content != "res") return
                        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                        when (obj["id"]?.jsonPrimitive?.content) {
                            "c" -> if (ok) webSocket.send(
                                buildJsonObject {
                                    put("type", "req"); put("id", "s"); put("method", "sessions.list")
                                    putJsonObject("params") { if (!agentId.isNullOrBlank()) put("agentId", agentId) }
                                }.toString()
                            ) else finish(webSocket, emptyList())
                            "s" -> finish(webSocket, if (ok) parseSessions(obj["payload"]) else emptyList())
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    finish(webSocket, emptyList())

                private fun finish(webSocket: WebSocket, result: List<SessionSummary>) {
                    webSocket.close(1000, null)
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resume(result)
                }
            }
            val ws = http.wsClient.newWebSocket(request, listener)
            cont.invokeOnCancellation { ws.cancel() }
        }

    /** Health/version probe: connect → `status` → returns the gateway version, or null if unreachable. */
    suspend fun gatewayVersion(settings: GoClawSettings): String? =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(connectFrame(settings))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val obj = http.json.parseToJsonElement(text).jsonObject
                        if (obj["type"]?.jsonPrimitive?.content != "res") return
                        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                        when (obj["id"]?.jsonPrimitive?.content) {
                            "c" -> if (ok) webSocket.send(
                                buildJsonObject {
                                    put("type", "req"); put("id", "v"); put("method", "status")
                                    putJsonObject("params") {}
                                }.toString()
                            ) else finish(webSocket, null)
                            "v" -> {
                                val p = obj["payload"]?.jsonObject
                                finish(webSocket, if (ok) strP(p, "version", "gatewayVersion").ifBlank { "ok" } else null)
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    finish(webSocket, null)

                private fun finish(webSocket: WebSocket, result: String?) {
                    webSocket.close(1000, null)
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resume(result)
                }
            }
            val ws = http.wsClient.newWebSocket(request, listener)
            cont.invokeOnCancellation { ws.cancel() }
        }

    /** connect → single RPC `method` with the given params → returns the response `ok`. */
    private suspend fun rpcBool(
        settings: GoClawSettings,
        method: String,
        params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(connectFrame(settings))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = http.json.parseToJsonElement(text).jsonObject
                    if (obj["type"]?.jsonPrimitive?.content != "res") return
                    val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                    when (obj["id"]?.jsonPrimitive?.content) {
                        "c" -> if (ok) webSocket.send(
                            buildJsonObject {
                                put("type", "req"); put("id", "x"); put("method", method)
                                putJsonObject("params", params)
                            }.toString()
                        ) else finish(webSocket, false)
                        "x" -> finish(webSocket, ok)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                finish(webSocket, false)

            private fun finish(webSocket: WebSocket, ok: Boolean) {
                webSocket.close(1000, null)
                if (done.compareAndSet(false, true) && cont.isActive) cont.resume(ok)
            }
        }
        val ws = http.wsClient.newWebSocket(request, listener)
        cont.invokeOnCancellation { ws.cancel() }
    }

    /** connect → single RPC `method` with the given params → returns the response `payload` (or null). */
    private suspend fun rpcPayload(
        settings: GoClawSettings,
        method: String,
        params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): kotlinx.serialization.json.JsonElement? = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(connectFrame(settings))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = http.json.parseToJsonElement(text).jsonObject
                    if (obj["type"]?.jsonPrimitive?.content != "res") return
                    val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                    when (obj["id"]?.jsonPrimitive?.content) {
                        "c" -> if (ok) webSocket.send(
                            buildJsonObject {
                                put("type", "req"); put("id", "x"); put("method", method)
                                putJsonObject("params", params)
                            }.toString()
                        ) else finish(webSocket, null)
                        "x" -> finish(webSocket, if (ok) obj["payload"] else null)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                finish(webSocket, null)

            private fun finish(webSocket: WebSocket, result: kotlinx.serialization.json.JsonElement?) {
                webSocket.close(1000, null)
                if (done.compareAndSet(false, true) && cont.isActive) cont.resume(result)
            }
        }
        val ws = http.wsClient.newWebSocket(request, listener)
        cont.invokeOnCancellation { ws.cancel() }
    }

    /**
     * The `connect` handshake for MANAGEMENT/admin RPCs (agents, heartbeat, api_keys, pairing,
     * sessions, approvals, logs, status). Always connects as the gateway owner user "system" so
     * listings aren't filtered by the chat user_id — the gateway otherwise scopes e.g. `agents.list`
     * to the connected user's own agents. Chat/history use their own inline connect frames with the
     * configured `user_id` (that setting only distinguishes chat users).
     */
    private fun connectFrame(settings: GoClawSettings): String =
        buildJsonObject {
            put("type", "req"); put("id", "c"); put("method", "connect")
            putJsonObject("params") {
                put("token", settings.apiKey)
                put("user_id", "system")
                put("protocol", 3)
                put("protocolVersion", 3)
            }
        }.toString()

    private companion object {
        /** Extra attempts after the first when the connect phase fails (message not yet sent). */
        const val MAX_CONNECT_RETRIES = 2
        /** Base backoff between connect retries (multiplied by the attempt number). */
        const val CONNECT_RETRY_DELAY_MS = 800L
        /** How long the open→connect-ack round-trip may take before the socket is declared dead. */
        const val CONNECT_WATCHDOG_MS = 15_000L
    }
}
