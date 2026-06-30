package xyz.limo060719.goclaw.data.remote

import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import xyz.limo060719.goclaw.data.GoClawSettings
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoClawHttp @Inject constructor(
    val json: Json,
) {
    // No read timeout: streaming responses are long-lived.
    // Force HTTP/1.1: long-lived TLS streams over HTTP/2 through the gateway were
    // intermittently failing with BAD_RECORD_MAC / decryption errors.
    val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Dedicated client for short-lived WebSockets. Connection pooling is disabled
     * (maxIdle = 0) so each WS opens a fresh connection — reusing a pooled connection
     * left over from a prior socket was causing BAD_RECORD_MAC on the next send.
     */
    val wsClient: OkHttpClient = client.newBuilder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .pingInterval(20, TimeUnit.SECONDS) // detect silently-dropped sockets (e.g. backgrounding)
        .build()

    fun url(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    /** Applies the auth + user-id headers the GoClaw backend requires on every request. */
    fun Request.Builder.goClawAuth(s: GoClawSettings): Request.Builder = apply {
        addHeader("Authorization", "Bearer ${s.apiKey}")
        if (s.userId.isNotBlank()) addHeader("X-GoClaw-User-Id", s.userId)
    }
}
