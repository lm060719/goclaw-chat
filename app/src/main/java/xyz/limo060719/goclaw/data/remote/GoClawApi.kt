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

/** A backend-managed (executable) skill (`/v1/skills`). */
data class BackendSkill(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
)

class GoClawApi @Inject constructor(
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
                val body = resp.body ?: error("空响应")
                val bytes = body.bytes()
                if (bytes.isEmpty()) error("空文件")
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

    /** Recent LLM traces (`GET /v1/traces`). */
    suspend fun traces(s: GoClawSettings, limit: Int = 50): Result<List<TraceInfo>> =
        getElement(s, "/v1/traces?limit=$limit").mapCatching { parseTraces(it) }

    /** Full trace detail as pretty-printed JSON (`GET /v1/traces/{id}`). */
    suspend fun traceDetail(s: GoClawSettings, id: String): Result<String> =
        getElement(s, "/v1/traces/$id").mapCatching {
            Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), it)
        }

    /** Performs an authenticated GET and parses the body as a JSON element. */
    private suspend fun getElement(s: GoClawSettings, path: String): Result<JsonElement> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = with(http) { Request.Builder().url(url(s.baseUrl, path)).goClawAuth(s).get().build() }
                http.client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
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
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body?.bytes()?.takeIf { it.isNotEmpty() } ?: error("空响应")
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
