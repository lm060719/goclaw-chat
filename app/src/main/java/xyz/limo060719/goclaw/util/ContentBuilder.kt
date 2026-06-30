package xyz.limo060719.goclaw.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.limo060719.goclaw.domain.model.Attachment

object ContentBuilder {
    fun text(value: String): JsonElement = JsonPrimitive(value)

    /**
     * Content sent to the backend. This GoClaw `/v1/chat/completions` types `content`
     * as a plain string and rejects the OpenAI multimodal array format, so we always
     * send text. Images stay available for local preview but are not uploaded.
     *
     * If the backend gains documented vision support, swap this back to
     * [multimodalContent] to send the text + image_url parts array.
     */
    fun userContent(text: String, images: List<Attachment>): JsonElement = JsonPrimitive(text)

    /** OpenAI-style multimodal content (text + image_url parts). Currently unused. */
    fun multimodalContent(text: String, images: List<Attachment>): JsonElement {
        if (images.isEmpty()) return JsonPrimitive(text)
        return buildJsonArray {
            if (text.isNotBlank()) add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
            images.forEach { img ->
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:${img.mimeType};base64,${img.base64}")
                    })
                })
            }
        }
    }
}
