package xyz.limo060719.goclaw.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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
import xyz.limo060719.goclaw.data.GoClawSettings
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
    private val http: GoClawHttp,
) {
    fun chat(
        settings: GoClawSettings,
        agentId: String,
        message: String,
        sessionKey: String,
        mediaPaths: List<String> = emptyList(),
    ): Flow<WsChatEvent> = callbackFlow {
        val pendingToolInput = HashMap<String, String>()
        val accumulated = StringBuilder()
        val accumulatedThinking = StringBuilder()

        val request = Request.Builder().url(http.url(settings.baseUrl, "/ws")).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val connect = buildJsonObject {
                    put("type", "req"); put("id", "c"); put("method", "connect")
                    putJsonObject("params") {
                        put("token", settings.apiKey)
                        put("user_id", settings.userId.ifBlank { "app" })
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
                            webSocket.send(send.toString())
                        } else {
                            trySend(WsChatEvent.Failed(IllegalStateException(errorMessage(obj) ?: "连接失败")))
                            webSocket.close(1000, null)
                        }
                    }
                    "m" -> {
                        if (ok) {
                            val payload = obj["payload"]?.jsonObject
                            val content = payload?.get("content")?.jsonPrimitive?.content
                                ?.takeIf { it.isNotBlank() } ?: accumulated.toString()
                            val thinking = payload?.get("thinking")?.jsonPrimitive?.content
                                ?.takeIf { it.isNotBlank() } ?: accumulatedThinking.toString()
                            val media = parseMedia(payload)
                            trySend(WsChatEvent.Done(content, extractSessionId(payload, obj), thinking, media))
                        } else {
                            trySend(WsChatEvent.Failed(IllegalStateException(errorMessage(obj) ?: "请求失败")))
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
                trySend(WsChatEvent.Failed(t)); close()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null); close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }

        val ws = http.wsClient.newWebSocket(request, listener)
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
                                    close(IllegalStateException(errorMessage(obj) ?: "连接失败"))
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
                            put("user_id", settings.userId.ifBlank { "app" })
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
                            put("user_id", settings.userId.ifBlank { "app" })
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
                                put("user_id", settings.userId.ifBlank { "app" })
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
                            put("user_id", settings.userId.ifBlank { "app" })
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
            put("approvedBy", approvedBy.ifBlank { settings.userId.ifBlank { "app" } })
        }

    /** Rejects a pending pairing via `device.pair.deny`. */
    suspend fun denyPairing(settings: GoClawSettings, code: String): Boolean =
        rpcBool(settings, "device.pair.deny") { put("code", code) }

    /** Revokes an approved pairing via `device.pair.revoke`. */
    suspend fun revokePairing(settings: GoClawSettings, channel: String, senderId: String): Boolean =
        rpcBool(settings, "device.pair.revoke") { put("channel", channel); put("senderId", senderId) }

    /** Requests a pairing code via `device.pair.request`. Returns the code, or null on failure. */
    suspend fun requestPairing(
        settings: GoClawSettings,
        channel: String,
        chatId: String,
    ): String? = suspendCancellableCoroutine { cont ->
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
                                put("type", "req"); put("id", "p"); put("method", "device.pair.request")
                                putJsonObject("params") { put("channel", channel); put("chatId", chatId) }
                            }.toString()
                        ) else finish(webSocket, null)
                        "p" -> {
                            val p = obj["payload"]?.jsonObject
                            finish(webSocket, if (ok) strP(p, "code", "pairingCode", "pair_code", "pairCode") else null)
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

    /** Lists pending and approved pairings via `device.pair.list`. Tolerant of payload shape. */
    suspend fun listPairings(settings: GoClawSettings): List<DevicePairing> =
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
                                    put("type", "req"); put("id", "l"); put("method", "device.pair.list")
                                    putJsonObject("params") {}
                                }.toString()
                            ) else finish(webSocket, emptyList())
                            "l" -> finish(webSocket, if (ok) parsePairings(obj["payload"]) else emptyList())
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    finish(webSocket, emptyList())

                private fun finish(webSocket: WebSocket, result: List<DevicePairing>) {
                    webSocket.close(1000, null)
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resume(result)
                }
            }
            val ws = http.wsClient.newWebSocket(request, listener)
            cont.invokeOnCancellation { ws.cancel() }
        }

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

    /** The standard `connect` handshake frame as a JSON string. */
    private fun connectFrame(settings: GoClawSettings): String =
        buildJsonObject {
            put("type", "req"); put("id", "c"); put("method", "connect")
            putJsonObject("params") {
                put("token", settings.apiKey)
                put("user_id", settings.userId.ifBlank { "app" })
            }
        }.toString()

    /** Tolerant parse of a sessions.list payload (bare array or {sessions|data|items}). */
    private fun parseSessions(payloadEl: kotlinx.serialization.json.JsonElement?): List<SessionSummary> {
        val arr = when (payloadEl) {
            is JsonArray -> payloadEl
            is JsonObject -> (payloadEl["sessions"] ?: payloadEl["data"] ?: payloadEl["items"]) as? JsonArray
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = strP(o, "key", "sessionKey", "session_key", "id")
            if (key.isBlank()) return@mapNotNull null
            SessionSummary(
                key = key,
                title = strA(o, "title", "label", "name", "preview"),
                agent = strP(o, "agent", "agentKey", "agent_key", "agentId", "agent_id"),
                messageCount = strP(o, "messageCount", "message_count", "count").toIntOrNull() ?: 0,
                updated = strP(o, "updatedAt", "updated_at", "updated", "lastActivity", "last_activity"),
            )
        }
    }

    /** Tolerant parse of an exec.approval.list payload (bare array or {approvals|pending|items|data}). */
    private fun parseApprovals(payloadEl: kotlinx.serialization.json.JsonElement?): List<ExecApproval> {
        val arr = when (payloadEl) {
            is JsonArray -> payloadEl
            is JsonObject -> (payloadEl["approvals"] ?: payloadEl["pending"]
                ?: payloadEl["items"] ?: payloadEl["data"]) as? JsonArray
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = strP(o, "id", "approvalId", "requestId", "exec_id")
            if (id.isBlank()) return@mapNotNull null
            ExecApproval(
                id = id,
                command = strA(o, "command", "cmd", "commandLine", "input", "script"),
                agentId = strP(o, "agentId", "agent_id", "agent"),
                cwd = strP(o, "cwd", "workdir", "directory", "dir"),
                reason = strA(o, "reason", "explanation", "description"),
            )
        }
    }

    /**
     * Tolerant parse of a device.pair.list payload. Handles a bare array, a single-array wrapper
     * ({pairings|items|data|devices}), or the split form ({pending:[…], approved:[…]}) that the
     * "lists pending and approved pairings" description implies — tagging each entry's status.
     */
    private fun parsePairings(payloadEl: kotlinx.serialization.json.JsonElement?): List<DevicePairing> {
        fun mapEntry(el: kotlinx.serialization.json.JsonElement, statusHint: String): DevicePairing? {
            val o = el as? JsonObject ?: return null
            val pairing = DevicePairing(
                code = strP(o, "code", "pairingCode", "pair_code", "pairCode"),
                channel = strP(o, "channel"),
                chatId = strP(o, "chatId", "chat_id"),
                senderId = strP(o, "senderId", "sender_id", "sender"),
                approvedBy = strP(o, "approvedBy", "approved_by"),
                status = strP(o, "status", "state").ifBlank { statusHint },
                createdAt = strP(o, "createdAt", "created_at", "requestedAt", "requested_at"),
            )
            return if (pairing.code.isBlank() && pairing.channel.isBlank() && pairing.senderId.isBlank()) null else pairing
        }
        return when (payloadEl) {
            is JsonArray -> payloadEl.mapNotNull { mapEntry(it, "") }
            is JsonObject -> {
                val pending = payloadEl["pending"] as? JsonArray
                val approved = payloadEl["approved"] as? JsonArray
                if (pending != null || approved != null) {
                    pending.orEmpty().mapNotNull { mapEntry(it, "pending") } +
                        approved.orEmpty().mapNotNull { mapEntry(it, "approved") }
                } else {
                    val arr = (payloadEl["pairings"] ?: payloadEl["items"]
                        ?: payloadEl["data"] ?: payloadEl["devices"]) as? JsonArray
                    arr?.mapNotNull { mapEntry(it, "") }.orEmpty()
                }
            }
            else -> emptyList()
        }
    }

    /**
     * Tolerant parse of a pushed log event. The gateway's frame shape for log lines isn't
     * documented, so we accept any event whose name contains "log" and read the fields from
     * `payload` (or a nested `payload.payload`, matching the agent-event convention).
     */
    private fun parseLogEvent(obj: JsonObject): LogLine? {
        val event = obj["event"]?.jsonPrimitive?.content ?: return null
        if (!event.contains("log", ignoreCase = true)) return null
        val payload = obj["payload"]?.jsonObject
        val inner = payload?.get("payload")?.jsonObject ?: payload
        val message = strA(inner, "message", "msg", "text", "line", "content")
            .ifBlank { strA(payload, "message", "msg", "text", "line", "content") }
        if (message.isBlank()) return null
        return LogLine(
            level = strP(inner, "level", "severity", "lvl").ifBlank { strP(payload, "level", "severity", "lvl") },
            message = message,
            timestamp = strP(inner, "time", "timestamp", "ts", "at")
                .ifBlank { strP(payload, "time", "timestamp", "ts", "at") },
            source = strP(inner, "source", "logger", "component", "module", "tag")
                .ifBlank { strP(payload, "source", "logger", "component", "module", "tag") },
        )
    }

    /** Tolerant parse of a heartbeat.get payload (may be wrapped under "heartbeat"/"config"). */
    private fun parseHeartbeatConfig(el: kotlinx.serialization.json.JsonElement?): HeartbeatConfig? {
        val o = el as? JsonObject ?: return null
        val h = (o["heartbeat"] as? JsonObject) ?: (o["config"] as? JsonObject) ?: o
        return HeartbeatConfig(
            enabled = boolP(h, "enabled", "is_enabled", "active"),
            intervalSec = strP(h, "intervalSec", "interval_sec", "interval").toIntOrNull() ?: 300,
            prompt = strA(h, "prompt", "message", "instruction"),
            providerName = strP(h, "providerName", "provider_name", "provider"),
            model = strP(h, "model", "model_id", "modelId"),
        )
    }

    /** Tolerant parse of a heartbeat.logs payload (bare array or {logs|runs|items|data}). */
    private fun parseHeartbeatLogs(el: kotlinx.serialization.json.JsonElement?): List<HeartbeatLog> {
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["logs"] ?: el["runs"] ?: el["items"] ?: el["data"]) as? JsonArray
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.map { o ->
            HeartbeatLog(
                id = strP(o, "id", "runId", "run_id"),
                status = strP(o, "status", "state", "result"),
                message = strA(o, "message", "output", "summary", "detail", "error", "text"),
                createdAt = strP(o, "createdAt", "created_at", "ranAt", "ran_at", "time", "timestamp"),
            )
        }
    }

    /** Tolerant parse of a heartbeat.targets payload (array of objects or plain strings). */
    private fun parseHeartbeatTargets(el: kotlinx.serialization.json.JsonElement?): List<HeartbeatTarget> {
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["targets"] ?: el["items"] ?: el["data"]) as? JsonArray
            else -> null
        } ?: return emptyList()
        val objects = arr.mapNotNull { it as? JsonObject }.map { o ->
            HeartbeatTarget(
                channel = strP(o, "channel", "type", "kind"),
                target = strA(o, "target", "chatId", "chat_id", "address", "to", "id"),
                label = strA(o, "label", "name", "description"),
            )
        }
        if (objects.isNotEmpty()) return objects
        return arr.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { c -> c.isNotBlank() } }
            .map { HeartbeatTarget(target = it) }
    }

    /** Extracts the checklist markdown from a heartbeat.checklist.get payload. */
    private fun parseChecklist(el: kotlinx.serialization.json.JsonElement): String = when (el) {
        is JsonPrimitive -> el.content
        is JsonObject -> strA(el, "content", "text", "markdown", "checklist", "body")
        else -> ""
    }

    /** Tolerant parse of an api_keys.list payload (bare array or {keys|api_keys|items|data}). */
    private fun parseApiKeys(el: kotlinx.serialization.json.JsonElement?): List<ApiKeyInfo> {
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["keys"] ?: el["apiKeys"] ?: el["api_keys"]
                ?: el["items"] ?: el["data"]) as? JsonArray
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val id = strP(o, "id", "keyId", "key_id")
            if (id.isBlank()) return@mapNotNull null
            val scopes = (o["scopes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            ApiKeyInfo(
                id = id,
                name = strA(o, "name", "label", "description"),
                scopes = scopes,
                prefix = strP(o, "prefix", "keyPrefix", "key_prefix", "masked", "preview"),
                ownerId = strP(o, "ownerId", "owner_id", "owner", "created_by", "tenant_id"),
                createdAt = strP(o, "createdAt", "created_at"),
                expiresAt = strP(o, "expiresAt", "expires_at", "expiry"),
                revoked = boolP(o, "revoked", "is_revoked") ||
                    strP(o, "status", "state").equals("revoked", ignoreCase = true),
            )
        }
    }

    /** First boolean-parseable value among [keys]; false if none. */
    private fun boolP(o: JsonObject?, vararg keys: String): Boolean {
        if (o == null) return false
        for (k in keys) (o[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { return it }
        return false
    }

    /** First non-blank JSON-primitive value among [keys]. */
    private fun strP(o: JsonObject?, vararg keys: String): String {
        if (o == null) return ""
        for (k in keys) {
            val v = o[k] as? JsonPrimitive ?: continue
            if (!v.isString && v.content == "null") continue
            if (v.content.isNotBlank()) return v.content
        }
        return ""
    }

    /** First non-blank value among [keys]; objects/arrays are returned as their JSON string. */
    private fun strA(o: JsonObject?, vararg keys: String): String {
        if (o == null) return ""
        for (k in keys) {
            when (val v = o[k]) {
                null -> continue
                is JsonPrimitive -> if (v.content.isNotBlank()) return v.content
                else -> return v.toString()
            }
        }
        return ""
    }

    /** Parses the `media` / `files` / `attachments` array of a final result payload. */
    private fun parseMedia(payload: JsonObject?): List<WsMediaItem> {
        val arr = (payload?.get("media") ?: payload?.get("files") ?: payload?.get("attachments"))
            as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val item = WsMediaItem(
                path = strP(o, "path", "file", "filePath", "file_path"),
                url = strP(o, "url", "href", "downloadUrl", "download_url"),
                filename = strP(o, "filename", "fileName", "name"),
                mimeType = strP(o, "mimeType", "mime_type", "mime", "contentType", "content_type"),
            )
            if (item.path.isBlank() && item.url.isBlank()) null else item
        }
    }

    private fun errorMessage(obj: JsonObject): String? =
        obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content

    private fun extractSessionId(payload: JsonObject?, top: JsonObject): String? {
        for (key in listOf("sessionId", "session_id", "session_key")) {
            (payload?.get(key) ?: top[key])?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}
