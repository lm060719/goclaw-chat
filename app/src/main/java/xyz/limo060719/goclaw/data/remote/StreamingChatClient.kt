package xyz.limo060719.goclaw.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.limo060719.goclaw.data.GoClawSettings
import xyz.limo060719.goclaw.data.remote.dto.ApiToolCall
import xyz.limo060719.goclaw.data.remote.dto.ChatChunk
import xyz.limo060719.goclaw.data.remote.dto.ChatRequest
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

sealed interface StreamEvent {
    data class Token(val text: String) : StreamEvent
    data class Completed(val finishReason: String?, val toolCalls: List<ApiToolCall>) : StreamEvent
    data class Failed(val error: Throwable) : StreamEvent
}

class StreamingChatClient @Inject constructor(
    private val http: GoClawHttp,
) {
    private val jsonMedia = "application/json".toMediaType()

    fun stream(settings: GoClawSettings, request: ChatRequest): Flow<StreamEvent> = flow {
        val body = http.json.encodeToString(ChatRequest.serializer(), request)
            .toRequestBody(jsonMedia)

        val httpReq = with(http) {
            Request.Builder()
                .url(url(settings.baseUrl, "/v1/chat/completions"))
                .goClawAuth(settings)
                .apply { if (settings.agent.isNotBlank()) addHeader("X-GoClaw-Agent", settings.agent) }
                .addHeader("Accept", "text/event-stream")
                .post(body)
                .build()
        }

        val call = http.client.newCall(httpReq)
        val response = call.execute()
        try {
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                emit(StreamEvent.Failed(IllegalStateException("HTTP ${response.code}: $err")))
                return@flow
            }
            val source = response.body?.source()
                ?: run { emit(StreamEvent.Failed(IllegalStateException("Empty body"))); return@flow }

            val acc = ToolCallAccumulator()
            var finishReason: String? = null

            while (!source.exhausted()) {
                coroutineContext.ensureActive()
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break

                val chunk = runCatching {
                    http.json.decodeFromString(ChatChunk.serializer(), payload)
                }.getOrNull() ?: continue

                val choice = chunk.choices.firstOrNull() ?: continue
                choice.delta.content?.let { if (it.isNotEmpty()) emit(StreamEvent.Token(it)) }
                choice.delta.toolCalls?.let(acc::add)
                choice.finishReason?.let { finishReason = it }
            }
            emit(StreamEvent.Completed(finishReason, acc.build()))
        } catch (t: Throwable) {
            if (coroutineContext[kotlinx.coroutines.Job]?.isCancelled == true) throw t
            emit(StreamEvent.Failed(t))
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)
}

/** Accumulates streamed tool_call deltas keyed by their index. */
private class ToolCallAccumulator {
    private data class Partial(var id: String? = null, var name: String? = null, val args: StringBuilder = StringBuilder())

    private val byIndex = LinkedHashMap<Int, Partial>()

    fun add(deltas: List<ApiToolCall>) {
        deltas.forEach { d ->
            val idx = d.index ?: byIndex.size
            val p = byIndex.getOrPut(idx) { Partial() }
            d.id?.takeIf { it.isNotBlank() }?.let { p.id = it }
            d.function.name?.takeIf { it.isNotBlank() }?.let { p.name = it }
            p.args.append(d.function.arguments)
        }
    }

    fun build(): List<ApiToolCall> = byIndex.values.mapIndexedNotNull { i, p ->
        val name = p.name ?: return@mapIndexedNotNull null
        ApiToolCall(
            id = p.id ?: "call_$i",
            function = xyz.limo060719.goclaw.data.remote.dto.ApiFunctionCall(name, p.args.toString()),
        )
    }
}
