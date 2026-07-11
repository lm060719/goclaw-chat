package xyz.limo060719.goclaw.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.GoClawSettings
import xyz.limo060719.goclaw.data.remote.dto.AgentInfo
import xyz.limo060719.goclaw.data.remote.dto.MediaUpload
import xyz.limo060719.goclaw.data.remote.dto.ProviderInfo
import xyz.limo060719.goclaw.data.remote.dto.SessionInfo
import javax.inject.Inject

/** A file fetched from the backend, ready to be written to local storage. */
class DownloadedFile(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** Aggregated token/cost usage (`/v1/usage/summary`, the `current` period). */
class UsageSummary(
    val totalTokens: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val costUsd: Double,
    val requests: Long,
    val period: String,
    val llmCalls: Long = 0,
    val toolCalls: Long = 0,
    val uniqueUsers: Long = 0,
    val errors: Long = 0,
)

/** One time bucket of usage (`/v1/usage/timeseries`) — tokens/cost/requests over a window. */
class UsagePoint(
    val label: String,
    val tokens: Long,
    val costUsd: Double,
    val requests: Long,
)

/** One row of a usage breakdown (`/v1/usage/breakdown`) grouped by model / agent / provider. */
class UsageBreakdownRow(
    val name: String,
    val tokens: Long,
    val costUsd: Double,
    val requests: Long,
)

/** One LLM execution trace row (`/v1/traces`). */
class TraceInfo(
    val id: String,
    val agent: String,
    val status: String,
    val model: String,
    val tokens: Long,
    val costUsd: Double,
    val createdAt: String,
)

/** The visual kind of a single trace step, used by the UI for icon/color. */
enum class TraceStepKind { USER, ASSISTANT, SYSTEM, THINKING, TOOL_CALL, TOOL_RESULT, LLM, EVENT, OTHER }

/** One entry on the trace timeline (a message / thinking block / tool call / event). */
class TraceStep(
    val kind: TraceStepKind,
    val title: String,
    val subtitle: String,
    val body: String,
)

/**
 * A parsed, presentable trace detail. Field shapes vary per backend build, so parsing is
 * deliberately tolerant: [meta] and [steps] hold whatever we could recognize, and [raw] always
 * carries the full pretty-printed JSON as a fallback view.
 */
class TraceDetail(
    val meta: List<Pair<String, String>>,
    val steps: List<TraceStep>,
    val raw: String,
)

/** A backend-managed (executable) skill (`/v1/skills`). */
data class BackendSkill(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
)

class GoClawApi @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: GoClawHttp,
) {
    suspend fun agents(s: GoClawSettings): Result<List<AgentInfo>> =
        getList(s, "/v1/agents") { raw -> parseList(raw, AgentInfo.serializer()) }

    /**
     * Downloads a file from the backend. [path] may be an absolute URL, a `/v1/...` path,
     * or any server path/route — resolved against the base URL when not absolute. Returns a
     * failure (with HTTP status) when the file isn't served, so the caller can try the next path.
     */
    suspend fun downloadFile(
        s: GoClawSettings,
        path: String,
        filename: String?,
    ): Result<DownloadedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val target = if (path.startsWith("http://", true) || path.startsWith("https://", true)) {
                path
            } else {
                http.url(s.baseUrl, path)
            }
            val req = with(http) { Request.Builder().url(target).goClawAuth(s).get().build() }
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error(context.getString(R.string.err_empty_response))
                val bytes = body.bytes()
                if (bytes.isEmpty()) error(context.getString(R.string.err_empty_file))
                val name = filename?.takeIf { it.isNotBlank() }
                    ?: resp.request.url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
                    ?: "download"
                val contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                val mime = contentType
                    ?.takeIf { it.isNotBlank() && !it.startsWith("application/octet-stream") }
                    ?: xyz.limo060719.goclaw.domain.model.ToolCard.guessMime(name)
                DownloadedFile(name, mime, bytes)
            }
        }
    }

    /** Uploads a file to the workspace and returns its server-side temp path. */
    suspend fun uploadMedia(
        s: GoClawSettings,
        bytes: ByteArray,
        mimeType: String,
        filename: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
                .build()
            val req = with(http) {
                Request.Builder().url(url(s.baseUrl, "/v1/media/upload")).goClawAuth(s).post(body).build()
            }
            http.client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")
                http.json.decodeFromString(MediaUpload.serializer(), raw).path
                    .ifBlank { error("upload returned empty path") }
            }
        }
    }

    suspend fun sessions(s: GoClawSettings): Result<List<SessionInfo>> =
        getList(s, "/v1/sessions") { raw -> parseList(raw, SessionInfo.serializer()) }

    /**
     * Forks a chat session at [upToIndex] into a new session keyed [newSessionKey]
     * (`POST /v1/chat/sessions/{key}/branch`). Best-effort: some gateway builds don't expose this
     * route, so a 404/error surfaces as Result.failure for the caller to report — it never crashes.
     * NOTE: `up_to_index` semantics (which server-transcript entries it counts) are unverified against
     * this backend; it's sent as a best-effort cut point derived from the local message position.
     */
    suspend fun branchSession(
        s: GoClawSettings,
        sessionKey: String,
        upToIndex: Int,
        newSessionKey: String,
        label: String,
    ): Result<Unit> {
        val body = buildJsonObject {
            put("up_to_index", upToIndex)
            put("new_session_key", newSessionKey)
            if (label.isNotBlank()) put("label", label)
        }.toString()
        return postOk(s, "/v1/chat/sessions/${java.net.URLEncoder.encode(sessionKey, "UTF-8")}/branch", body)
    }

    /** Lists configured LLM providers (`GET /v1/providers`). */
    suspend fun providers(s: GoClawSettings): Result<List<ProviderInfo>> =
        getList(s, "/v1/providers") { raw -> parseList(raw, ProviderInfo.serializer()) }

    /** Lists a provider's available model ids (`GET /v1/providers/{id}/models`). */
    suspend fun providerModels(s: GoClawSettings, providerId: String): Result<List<String>> =
        getList(s, "/v1/providers/$providerId/models") { raw -> parseModelIds(raw) }

    /** Tolerant parse of a models list: bare array or {data|models|...}; string or {id|name|model}. */
    private fun parseModelIds(raw: String): List<String> {
        val root = runCatching { http.json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val array = root.findItemArray() ?: return emptyList()
        return array.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.content.takeIf { it.isNotBlank() }
                is JsonObject -> ((el["id"] ?: el["name"] ?: el["model"]) as? JsonPrimitive)
                    ?.content?.takeIf { it.isNotBlank() }
                else -> null
            }
        }.distinct()
    }

    /** Token/cost usage summary (`GET /v1/usage/summary`). */
    suspend fun usageSummary(s: GoClawSettings): Result<UsageSummary> =
        getElement(s, "/v1/usage/summary").mapCatching { parseUsage(it) }

    /** Raw `GET /v1/usage/summary` body (debug aid to align field names). */
    suspend fun usageRaw(s: GoClawSettings): Result<String> =
        getElement(s, "/v1/usage/summary").map { it.toString() }

    /** Both trend endpoints require an ISO-8601 `from`/`to` window (400 "from and to are required" otherwise). */
    private fun usageWindow(days: Long): String {
        val now = java.time.Instant.now()
        val from = now.minus(days, java.time.temporal.ChronoUnit.DAYS)
        return "from=$from&to=$now"
    }

    /**
     * Usage over time (`GET /v1/usage/timeseries?from&to`) for the last 30 days. The gateway returns
     * hourly buckets, so we aggregate them into daily points for a legible chart. Best-effort: a
     * failure just means no trend to show — the summary is the source of truth.
     */
    suspend fun usageTimeseries(s: GoClawSettings): Result<List<UsagePoint>> =
        getElement(s, "/v1/usage/timeseries?${usageWindow(30)}").mapCatching { aggregateDaily(parseTimeseries(it)) }

    /** Usage grouped by model/agent/provider (`GET /v1/usage/breakdown?from&to`), last 30 days. */
    suspend fun usageBreakdown(s: GoClawSettings): Result<List<UsageBreakdownRow>> =
        getElement(s, "/v1/usage/breakdown?${usageWindow(30)}").mapCatching { parseBreakdown(it) }

    /** Raw timeseries/breakdown bodies — a diagnostic shown only when a trend fetch fails. */
    suspend fun usageTimeseriesRaw(s: GoClawSettings): Result<String> =
        getElement(s, "/v1/usage/timeseries?${usageWindow(30)}").map { it.toString() }

    suspend fun usageBreakdownRaw(s: GoClawSettings): Result<String> =
        getElement(s, "/v1/usage/breakdown?${usageWindow(30)}").map { it.toString() }

    /** Collapses hourly usage points into one point per calendar day (ISO date prefix), summed. */
    private fun aggregateDaily(points: List<UsagePoint>): List<UsagePoint> =
        points.filter { it.label.length >= 10 }
            .groupBy { it.label.take(10) }
            .toSortedMap()
            .map { (date, pts) ->
                UsagePoint(
                    label = date,
                    tokens = pts.sumOf { it.tokens },
                    costUsd = pts.sumOf { it.costUsd },
                    requests = pts.sumOf { it.requests },
                )
            }

    /** Recent LLM traces (`GET /v1/traces`). */
    suspend fun traces(s: GoClawSettings, limit: Int = 50): Result<List<TraceInfo>> =
        getElement(s, "/v1/traces?limit=$limit").mapCatching { parseTraces(it) }

    /** Full trace detail (`GET /v1/traces/{id}`), parsed into a presentable timeline. */
    suspend fun traceDetail(s: GoClawSettings, id: String): Result<TraceDetail> =
        getElement(s, "/v1/traces/$id").mapCatching { parseTraceDetail(it) }

    /** Performs an authenticated GET and parses the body as a JSON element. */
    private suspend fun getElement(s: GoClawSettings, path: String): Result<JsonElement> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = with(http) { Request.Builder().url(url(s.baseUrl, path)).goClawAuth(s).get().build() }
                http.client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    // Include the body: 4xx responses usually explain what's wrong (e.g. a missing
                    // required query param), which is exactly what the usage diagnostic needs.
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${raw.take(300)}")
                    http.json.parseToJsonElement(raw)
                }
            }
        }

    private fun parseUsage(el: JsonElement): UsageSummary {
        val root = el as? JsonObject ?: return UsageSummary(0, 0, 0, 0.0, 0, "")
        // The summary nests the active window under `current` (with `previous` for comparison).
        val o = (root["current"] as? JsonObject) ?: (root["summary"] as? JsonObject)
            ?: (root["data"] as? JsonObject) ?: (root["totals"] as? JsonObject) ?: root
        var cost = o.num("cost_usd", "costUsd", "total_cost_usd", "cost", "total_cost")
        if (cost == 0.0) cost = o.num("cost_micros", "total_cost_micros", "costMicros") / 1_000_000.0
        val input = o.num("prompt_tokens", "promptTokens", "input_tokens", "inputTokens").toLong()
        val output = o.num("completion_tokens", "completionTokens", "output_tokens", "outputTokens").toLong()
        var total = o.num("total_tokens", "totalTokens", "tokens").toLong()
        if (total == 0L) total = input + output
        return UsageSummary(
            totalTokens = total,
            promptTokens = input,
            completionTokens = output,
            costUsd = cost,
            requests = o.num("requests", "request_count", "requestCount", "count", "calls").toLong(),
            period = o.strv("period", "window", "range", "since"),
            llmCalls = o.num("llm_calls", "llmCalls").toLong(),
            toolCalls = o.num("tool_calls", "toolCalls").toLong(),
            uniqueUsers = o.num("unique_users", "uniqueUsers", "users").toLong(),
            errors = o.num("errors", "error_count", "errorCount").toLong(),
        )
    }

    /** Sums input+output token fields (or a single total) on a usage-like object. */
    private fun JsonObject.tokenSum(): Long {
        var total = num("total_tokens", "totalTokens", "tokens").toLong()
        if (total == 0L) {
            val input = num("prompt_tokens", "promptTokens", "input_tokens", "inputTokens").toLong()
            val output = num("completion_tokens", "completionTokens", "output_tokens", "outputTokens").toLong()
            total = input + output
        }
        return total
    }

    private fun JsonObject.costUsd(): Double {
        var cost = num("cost_usd", "costUsd", "total_cost_usd", "cost", "total_cost")
        if (cost == 0.0) cost = num("cost_micros", "total_cost_micros", "costMicros") / 1_000_000.0
        return cost
    }

    /**
     * Tolerant parse of a usage timeseries. Accepts the points under any of several array keys
     * (or nested under `current`), and reads a time label + tokens/cost/requests from each point
     * under many candidate field names — the exact shape is undocumented and varies by build.
     */
    private fun parseTimeseries(el: JsonElement): List<UsagePoint> {
        val root = el as? JsonObject
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> el.arrayOf("series", "points", "timeseries", "buckets", "data", "items")
                ?: (el["current"] as? JsonObject)?.arrayOf("series", "points", "timeseries", "buckets", "data")
                ?: root?.values?.firstOrNull { it is JsonArray } as? JsonArray
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.map { o ->
            UsagePoint(
                label = o.strv("bucket_time", "bucketTime", "time", "timestamp", "date", "day", "bucket", "period", "label", "ts", "hour"),
                tokens = o.tokenSum(),
                costUsd = o.costUsd(),
                requests = o.num("requests", "request_count", "requestCount", "count", "calls").toLong(),
            )
        }
    }

    /** Tolerant parse of a usage breakdown grouped by model/agent/provider. */
    private fun parseBreakdown(el: JsonElement): List<UsageBreakdownRow> {
        val root = el as? JsonObject
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> el.arrayOf("breakdown", "by_model", "byModel", "models", "by_agent",
                "byAgent", "agents", "rows", "groups", "data", "items")
                ?: root?.values?.firstOrNull { it is JsonArray } as? JsonArray
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val name = o.strv("model", "agent", "provider", "name", "key", "label", "id", "group")
            if (name.isBlank()) return@mapNotNull null
            UsageBreakdownRow(
                name = name,
                tokens = o.tokenSum(),
                costUsd = o.costUsd(),
                requests = o.num("requests", "request_count", "requestCount", "count", "calls").toLong(),
            )
        }.sortedByDescending { it.tokens }
    }

    /* ---- Backend (executable) skills ---- */

    suspend fun listBackendSkills(s: GoClawSettings): Result<List<BackendSkill>> =
        getElement(s, "/v1/skills").mapCatching { parseBackendSkills(it) }

    /** Uploads a skill bundle (`.zip`, ≤20MB) to the backend (`POST /v1/skills/upload`). */
    suspend fun uploadSkill(s: GoClawSettings, bytes: ByteArray, filename: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename, bytes.toRequestBody("application/zip".toMediaTypeOrNull()))
                    .build()
                val req = with(http) {
                    Request.Builder().url(url(s.baseUrl, "/v1/skills/upload")).goClawAuth(s).post(body).build()
                }
                http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(200)}")
                }
                Unit
            }
        }

    /** Synthesizes speech for [text] via the backend (`POST /v1/tts/synthesize`). Returns audio bytes. */
    suspend fun synthesizeTts(s: GoClawSettings, text: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildJsonObject { put("text", text) }.toString()
                val req = with(http) {
                    Request.Builder().url(url(s.baseUrl, "/v1/tts/synthesize")).goClawAuth(s)
                        .post(payload.toRequestBody("application/json".toMediaTypeOrNull())).build()
                }
                http.client.newCall(req).execute().use { resp ->
                    val ct = resp.body?.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(160)}")
                    val bytes = resp.body?.bytes()?.takeIf { it.isNotEmpty() } ?: error(context.getString(R.string.err_empty_response))
                    // Reject JSON/text bodies (error payloads sometimes returned with 200) so callers fall back.
                    if (ct.startsWith("application/json") || ct.startsWith("text/")) {
                        error(context.getString(R.string.err_non_audio_fmt, ct, String(bytes).take(160)))
                    }
                    bytes
                }
            }
        }

    /** Enables/disables a backend skill (`POST /v1/skills/{id}/toggle` with `{enabled}`). */
    suspend fun toggleBackendSkill(s: GoClawSettings, id: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildJsonObject { put("enabled", enabled) }.toString()
                val req = with(http) {
                    Request.Builder().url(url(s.baseUrl, "/v1/skills/$id/toggle")).goClawAuth(s)
                        .post(payload.toRequestBody("application/json".toMediaTypeOrNull())).build()
                }
                http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(160)}")
                }
                Unit
            }
        }

    suspend fun deleteBackendSkill(s: GoClawSettings, id: String): Result<Unit> =
        deleteOk(s, "/v1/skills/$id")

    private suspend fun deleteOk(s: GoClawSettings, path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = with(http) {
                    Request.Builder().url(url(s.baseUrl, path)).goClawAuth(s).delete().build()
                }
                http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                }
                Unit
            }
        }

    private suspend fun postOk(s: GoClawSettings, path: String, jsonBody: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = with(http) {
                    Request.Builder().url(url(s.baseUrl, path)).goClawAuth(s)
                        .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull())).build()
                }
                http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(160)}")
                }
                Unit
            }
        }

    private fun parseBackendSkills(el: JsonElement): List<BackendSkill> {
        val arr = el.findItemArray() ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val id = o.strv("id", "skill_id", "skillId", "slug")
            if (id.isBlank()) return@mapNotNull null
            BackendSkill(
                id = id,
                name = o.strv("name", "title", "display_name", "slug").ifBlank { id },
                description = o.strv("description", "summary", "desc"),
                enabled = o.boolOr("enabled", "is_enabled", "active", default = true),
            )
        }
    }

    private fun JsonObject.boolOr(vararg keys: String, default: Boolean): Boolean {
        for (k in keys) (this[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { return it }
        return default
    }

    private fun parseTraces(el: JsonElement): List<TraceInfo> {
        val arr = el.findItemArray() ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.map { o ->
            TraceInfo(
                id = o.strv("id", "traceId", "trace_id"),
                agent = o.strv("agent", "agentName", "agent_name", "agentId", "agent_id"),
                status = o.strv("status", "state"),
                model = o.strv("model", "model_id", "modelId"),
                tokens = o.num("total_tokens", "totalTokens", "tokens").toLong(),
                costUsd = o.num("cost_usd", "costUsd", "cost", "total_cost_usd"),
                createdAt = o.strv("created_at", "createdAt", "started_at", "startedAt", "time", "timestamp"),
            )
        }
    }

    private val prettyJson = Json { prettyPrint = true }

    /** Renders any element as text: primitives inline, objects/arrays as pretty JSON. */
    private fun JsonElement.asText(): String = when (this) {
        is JsonPrimitive -> content
        else -> prettyJson.encodeToString(JsonElement.serializer(), this)
    }

    /**
     * Pulls the best textual content out of an object, trying several candidate keys. Handles the
     * common "content is an array of {type,text} blocks" shape by concatenating the text blocks.
     */
    private fun JsonObject.textv(vararg keys: String): String {
        for (k in keys) when (val v = this[k]) {
            is JsonPrimitive -> v.content.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            is JsonArray -> {
                val joined = v.joinToString("\n") { blk ->
                    when (blk) {
                        is JsonObject -> blk.strv("text", "content", "value")
                        is JsonPrimitive -> blk.content
                        else -> ""
                    }
                }.trim()
                if (joined.isNotBlank()) return joined
            }
            is JsonObject -> return v.asText()
            else -> {}
        }
        return ""
    }

    private fun parseTraceDetail(el: JsonElement): TraceDetail {
        val raw = prettyJson.encodeToString(JsonElement.serializer(), el)
        val root = el as? JsonObject
            ?: (el as? JsonArray)?.let { buildJsonObject { } } // steps-only array handled below
            ?: return TraceDetail(emptyList(), emptyList(), raw)

        // Metadata: only surface fields that are actually present and non-blank.
        val meta = buildList {
            fun add(label: String, value: String) { if (value.isNotBlank()) add(label to value) }
            add("模型", root.strv("model", "model_id", "modelId"))
            add("智能体", root.strv("agent", "agentName", "agent_name", "agentId", "agent_id"))
            add("供应商", root.strv("provider", "provider_name", "providerName"))
            add("状态", root.strv("status", "state"))
            val inTok = root.num("prompt_tokens", "promptTokens", "input_tokens", "inputTokens").toLong()
            val outTok = root.num("completion_tokens", "completionTokens", "output_tokens", "outputTokens").toLong()
            var total = root.num("total_tokens", "totalTokens", "tokens").toLong()
            if (total == 0L) total = inTok + outTok
            if (total > 0) add("Token", if (inTok > 0 || outTok > 0) "$total ($inTok↑ / $outTok↓)" else "$total")
            val cost = root.num("cost_usd", "costUsd", "cost", "total_cost_usd")
            if (cost > 0) add("费用", "$" + String.format("%.4f", cost))
            val durMs = root.num("duration_ms", "durationMs", "latency_ms", "latencyMs", "elapsed_ms").toLong()
            if (durMs > 0) add("耗时", if (durMs >= 1000) String.format("%.2fs", durMs / 1000.0) else "${durMs}ms")
            add("时间", root.strv("created_at", "createdAt", "started_at", "startedAt", "time", "timestamp"))
            add("会话", root.strv("session_key", "sessionKey", "session_id", "sessionId", "session"))
            add("ID", root.strv("id", "traceId", "trace_id"))
            add("错误", root.strv("error", "error_message", "errorMessage", "failure"))
        }

        // Steps: the timeline lives under one of several candidate array keys.
        val stepsArr = (el as? JsonArray) ?: root.arrayOf(
            "steps", "spans", "events", "messages", "timeline", "trace",
            "entries", "records", "calls", "turns", "history", "items",
        )
        val steps = stepsArr?.mapNotNull { it as? JsonObject }?.map { parseTraceStep(it) } ?: emptyList()

        return TraceDetail(meta, steps, raw)
    }

    private fun parseTraceStep(o: JsonObject): TraceStep {
        val type = o.strv("type", "kind", "role", "event", "event_type", "phase", "name").lowercase()
        val toolName = o.strv("tool", "tool_name", "toolName", "function", "name")

        val kind = when {
            type.contains("user") -> TraceStepKind.USER
            type.contains("assistant") || type.contains("output") && type.contains("message") -> TraceStepKind.ASSISTANT
            type.contains("system") -> TraceStepKind.SYSTEM
            type.contains("think") || type.contains("reason") -> TraceStepKind.THINKING
            type.contains("tool") && (type.contains("result") || type.contains("response") || type.contains("output")) -> TraceStepKind.TOOL_RESULT
            type.contains("tool") || type.contains("function") -> TraceStepKind.TOOL_CALL
            type.contains("llm") || type.contains("model") || type.contains("completion") || type.contains("generation") -> TraceStepKind.LLM
            type.contains("message") || type.contains("msg") -> TraceStepKind.ASSISTANT
            type.isNotBlank() -> TraceStepKind.EVENT
            else -> TraceStepKind.OTHER
        }

        val title = when (kind) {
            TraceStepKind.USER -> "用户"
            TraceStepKind.ASSISTANT -> "助手"
            TraceStepKind.SYSTEM -> "系统"
            TraceStepKind.THINKING -> "思考"
            TraceStepKind.TOOL_CALL -> "调用工具" + if (toolName.isNotBlank()) " · $toolName" else ""
            TraceStepKind.TOOL_RESULT -> "工具结果" + if (toolName.isNotBlank()) " · $toolName" else ""
            TraceStepKind.LLM -> "模型调用"
            else -> type.ifBlank { "步骤" }
        }

        val subtitle = buildList {
            o.strv("status", "state").takeIf { it.isNotBlank() }?.let { add(it) }
            val tok = o.num("total_tokens", "totalTokens", "tokens").toLong()
            if (tok > 0) add("$tok tok")
            val durMs = o.num("duration_ms", "durationMs", "latency_ms", "latencyMs", "elapsed_ms").toLong()
            if (durMs > 0) add(if (durMs >= 1000) String.format("%.2fs", durMs / 1000.0) else "${durMs}ms")
            o.strv("created_at", "createdAt", "time", "timestamp", "ts").takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")

        val body = when (kind) {
            TraceStepKind.TOOL_CALL -> o.textv("arguments", "args", "input", "params", "parameters", "content")
            TraceStepKind.TOOL_RESULT -> o.textv("result", "output", "response", "content", "value")
            else -> o.textv("content", "text", "message", "output", "value")
        }.ifBlank {
            // Nothing recognizable — show the whole entry so no data is silently dropped.
            o.asText()
        }

        return TraceStep(kind, title, subtitle, body)
    }

    private fun JsonObject.arrayOf(vararg keys: String): JsonArray? {
        for (k in keys) (this[k] as? JsonArray)?.let { return it }
        return null
    }

    private fun JsonObject.num(vararg keys: String): Double {
        for (k in keys) (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { return it }
        return 0.0
    }

    private fun JsonObject.strv(vararg keys: String): String {
        for (k in keys) (this[k] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        return ""
    }

    /**
     * Tolerant list parsing: accepts a bare `[...]`, `{data:[...]}`, `{agents:[...]}`,
     * `{sessions:[...]}`, or any object whose first array value holds the items.
     * Items that fail to decode are skipped rather than discarding the whole list.
     */
    private fun <T> parseList(
        raw: String,
        itemSerializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> {
        val root = runCatching { http.json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val array = root.findItemArray() ?: return emptyList()
        return array.mapNotNull { el ->
            runCatching { http.json.decodeFromJsonElement(itemSerializer, el) }.getOrNull()
        }
    }

    private fun JsonElement.findItemArray(): JsonArray? = when (this) {
        is JsonArray -> this
        is JsonObject -> (this["data"] ?: this["agents"] ?: this["sessions"]
            ?: values.firstOrNull { it is JsonArray }) as? JsonArray
        else -> null
    }

    private suspend fun <T> getList(
        s: GoClawSettings,
        path: String,
        parse: (String) -> List<T>,
    ): Result<List<T>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = with(http) {
                Request.Builder()
                    .url(url(s.baseUrl, path))
                    .goClawAuth(s)
                    .get()
                    .build()
            }
            http.client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")
                parse(raw)
            }
        }
    }
}
