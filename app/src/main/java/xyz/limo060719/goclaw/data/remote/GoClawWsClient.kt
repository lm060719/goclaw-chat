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
