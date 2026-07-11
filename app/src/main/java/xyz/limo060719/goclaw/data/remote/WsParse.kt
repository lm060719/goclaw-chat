package xyz.limo060719.goclaw.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tolerant, side-effect-free parsers for GoClaw gateway payloads. Extracted from
 * [GoClawWsClient] so the field-name juggling — the single biggest source of "screen shows all
 * zeros" bugs, since the backend's JSON shapes are undocumented/inconsistent — can be unit-tested
 * against real captured frames without standing up a WebSocket. The transport client delegates to
 * these; call sites keep the original names via same-package top-level resolution.
 *
 * `strP`/`strA`/`boolP` are the primitive field pickers: they try each candidate key in order and
 * return the first usable value, so a slightly-off backend key name degrades to a blank field
 * instead of crashing the whole screen.
 */

/** First boolean-parseable value among [keys]; false if none. */
internal fun boolP(o: JsonObject?, vararg keys: String): Boolean {
    if (o == null) return false
    for (k in keys) (o[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { return it }
    return false
}

/** First non-blank JSON-primitive value among [keys]. */
internal fun strP(o: JsonObject?, vararg keys: String): String {
    if (o == null) return ""
    for (k in keys) {
        val v = o[k] as? JsonPrimitive ?: continue
        if (!v.isString && v.content == "null") continue
        if (v.content.isNotBlank()) return v.content
    }
    return ""
}

/** First non-blank value among [keys]; objects/arrays are returned as their JSON string. */
internal fun strA(o: JsonObject?, vararg keys: String): String {
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

/** Tolerant parse of a sessions.list payload (bare array or {sessions|data|items}). */
internal fun parseSessions(payloadEl: JsonElement?): List<SessionSummary> {
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
internal fun parseApprovals(payloadEl: JsonElement?): List<ExecApproval> {
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
internal fun parsePairings(payloadEl: JsonElement?): List<DevicePairing> {
    fun mapEntry(el: JsonElement, statusHint: String): DevicePairing? {
        val o = el as? JsonObject ?: return null
        val pairing = DevicePairing(
            code = strP(o, "code", "pairingCode", "pair_code", "pairCode"),
            channel = strP(o, "channel"),
            chatId = strP(o, "chatId", "chat_id"),
            senderId = strP(o, "senderId", "sender_id", "sender"),
            approvedBy = strP(o, "approvedBy", "approved_by", "paired_by"),
            status = strP(o, "status", "state").ifBlank { statusHint },
            createdAt = strP(o, "createdAt", "created_at", "requestedAt", "requested_at", "paired_at"),
        )
        return if (pairing.code.isBlank() && pairing.channel.isBlank() && pairing.senderId.isBlank()) null else pairing
    }
    return when (payloadEl) {
        is JsonArray -> payloadEl.mapNotNull { mapEntry(it, "") }
        is JsonObject -> {
            val pending = payloadEl["pending"] as? JsonArray
            // Real gateway uses "paired" for approved pairings; "approved" kept as fallback.
            val approved = (payloadEl["paired"] ?: payloadEl["approved"]) as? JsonArray
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
internal fun parseLogEvent(obj: JsonObject): LogLine? {
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
internal fun parseHeartbeatConfig(el: JsonElement?): HeartbeatConfig? {
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
internal fun parseHeartbeatLogs(el: JsonElement?): List<HeartbeatLog> {
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
internal fun parseHeartbeatTargets(el: JsonElement?): List<HeartbeatTarget> {
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
internal fun parseChecklist(el: JsonElement): String = when (el) {
    is JsonPrimitive -> el.content
    is JsonObject -> strA(el, "content", "text", "markdown", "checklist", "body")
    else -> ""
}

/** Tolerant parse of an api_keys.list payload (bare array or {keys|api_keys|items|data}). */
internal fun parseApiKeys(el: JsonElement?): List<ApiKeyInfo> {
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

/** Parses the `media` / `files` / `attachments` array of a final result payload. */
internal fun parseMedia(payload: JsonObject?): List<WsMediaItem> {
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

internal fun errorMessage(obj: JsonObject): String? =
    obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content

internal fun extractSessionId(payload: JsonObject?, top: JsonObject): String? {
    for (key in listOf("sessionId", "session_id", "session_key")) {
        (payload?.get(key) ?: top[key])?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}
