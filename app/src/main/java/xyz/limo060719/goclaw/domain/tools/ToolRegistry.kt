package xyz.limo060719.goclaw.domain.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.limo060719.goclaw.data.remote.dto.FunctionDef
import xyz.limo060719.goclaw.data.remote.dto.ToolDef
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A locally-executable capability exposed to the model. */
class Tool(
    val def: ToolDef,
    val handler: suspend (JsonObject) -> String,
) {
    val name: String get() = def.function.name
}

@Singleton
class ToolRegistry @Inject constructor() {
    private val tools = LinkedHashMap<String, Tool>()

    init {
        register(currentTimeTool())
        register(deviceInfoTool())
    }

    fun register(tool: Tool) { tools[tool.name] = tool }

    /** Tool schemas sent in the chat request. */
    fun defs(): List<ToolDef> = tools.values.map { it.def }

    suspend fun execute(name: String, argsRaw: String, json: kotlinx.serialization.json.Json): String {
        val tool = tools[name] ?: return """{"error":"unknown tool: $name"}"""
        val args = runCatching {
            json.decodeFromString(JsonObject.serializer(), argsRaw.ifBlank { "{}" })
        }.getOrElse { return """{"error":"bad arguments: ${it.message}"}""" }
        return runCatching { tool.handler(args) }
            .getOrElse { """{"error":"${it.message}"}""" }
    }

    // ---- built-in example tools ----

    private fun currentTimeTool() = Tool(
        def = ToolDef(
            function = FunctionDef(
                name = "get_current_time",
                description = "Returns the current local date and time of the device.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                },
            ),
        ),
        handler = {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
            """{"time":"${fmt.format(Date())}"}"""
        },
    )

    private fun deviceInfoTool() = Tool(
        def = ToolDef(
            function = FunctionDef(
                name = "get_device_info",
                description = "Returns basic info about the Android device running this client.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                },
            ),
        ),
        handler = {
            buildJsonObject {
                put("model", android.os.Build.MODEL)
                put("manufacturer", android.os.Build.MANUFACTURER)
                put("sdk_int", android.os.Build.VERSION.SDK_INT)
                put("release", android.os.Build.VERSION.RELEASE)
            }.toString()
        },
    )
}
