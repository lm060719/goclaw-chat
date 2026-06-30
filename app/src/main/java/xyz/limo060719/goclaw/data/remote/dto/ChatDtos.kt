package xyz.limo060719.goclaw.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI-compatible wire models for the GoClaw backend.
 * `content` is a raw JsonElement so it can be either a plain string
 * or an array of content parts (multimodal text + image_url).
 */
@Serializable
data class ApiMessage(
    val role: String,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ApiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCall,
    val index: Int? = null,
)

@Serializable
data class ApiFunctionCall(
    val name: String? = null,
    val arguments: String = "",
)

@Serializable
data class ToolDef(
    val type: String = "function",
    val function: FunctionDef,
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String = "",
    val parameters: JsonElement, // JSON schema object
)

@Serializable
data class ChatRequest(
    val agent: String? = null,
    val model: String? = null,
    val messages: List<ApiMessage>,
    val stream: Boolean = true,
    val tools: List<ToolDef>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
)

/* ---- streaming chunk ---- */

@Serializable
data class ChatChunk(
    val choices: List<ChunkChoice> = emptyList(),
)

@Serializable
data class ChunkChoice(
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ApiToolCall>? = null,
)

/* ---- /v1/agents and /v1/sessions ---- */

@Serializable
data class AgentInfo(
    val id: String? = null,
    val key: String? = null,
    val slug: String? = null,
    val agent: String? = null,
    @SerialName("agent_key") val agentKey: String? = null,
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("agent_description") val agentDescription: String? = null,
    val frontmatter: String? = null,
) {
    val resolvedKey: String
        get() = listOf(key, agentKey, slug, agent, id)
            .firstOrNull { !it.isNullOrBlank() }.orEmpty()
    val display: String
        get() = listOf(displayName, name)
            .firstOrNull { !it.isNullOrBlank() } ?: resolvedKey
    val summary: String?
        get() = listOf(description, frontmatter, agentDescription)
            .firstOrNull { !it.isNullOrBlank() }
}

@Serializable
data class AgentList(val data: List<AgentInfo> = emptyList())

@Serializable
data class SessionInfo(
    val id: String? = null,
    val title: String? = null,
    val agent: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class SessionList(val data: List<SessionInfo> = emptyList())

/* ---- /v1/media/upload ---- */

/** Response of `POST /v1/media/upload`. The server returns a workspace path the agent can read. */
@Serializable
data class MediaUpload(
    val path: String = "",
    val url: String = "",
    val id: String = "",
    val filename: String = "",
    @SerialName("mime_type") val mimeType: String = "",
)
