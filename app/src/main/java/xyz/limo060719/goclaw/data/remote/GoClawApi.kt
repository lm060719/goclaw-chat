package xyz.limo060719.goclaw.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.limo060719.goclaw.data.GoClawSettings
import xyz.limo060719.goclaw.data.remote.dto.AgentInfo
import xyz.limo060719.goclaw.data.remote.dto.MediaUpload
import xyz.limo060719.goclaw.data.remote.dto.SessionInfo
import javax.inject.Inject

/** A file fetched from the backend, ready to be written to local storage. */
class DownloadedFile(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
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
