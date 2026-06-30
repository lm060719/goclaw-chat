package xyz.limo060719.goclaw.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Role { USER, ASSISTANT, TOOL }

@Immutable
@Serializable
data class Attachment(
    val mimeType: String,
    val base64: String,
    /** Local content uri string for preview; not sent to the server. */
    val previewUri: String? = null,
)

@Immutable
@Serializable
data class FileRef(
    val path: String,
    val filename: String = "",
    val mimeType: String = "",
)

@Immutable
@Serializable
data class ToolCard(
    val name: String,
    val arguments: String,
    val result: String,
    val files: List<FileRef> = emptyList(),
) {
    companion object {
        private val FILE_EXT = Regex("""\.[A-Za-z0-9]{1,10}$""")
        private val MEDIA_PATH = Regex("""/v1/media/[A-Za-z0-9_./-]+""")
        private val WORKSPACE_PATH = Regex("""/workspace/[A-Za-z0-9_./-]+\.[A-Za-z0-9]{1,10}""")
        private val ABS_PATH = Regex("""/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\.[A-Za-z0-9]{1,10}""")
        private val BARE_FILENAME = Regex("""(?:^|\s)([A-Za-z0-9_.-]+\.[A-Za-z0-9]{1,10})(?:\s|$|—)""")
        private val JSON_PATH_KEYS = listOf("path", "file", "filename", "filePath", "fileName")

        /** Extracts file references from tool arguments JSON + result text. */
        fun extractFiles(arguments: String, result: String): List<FileRef> {
            val seen = linkedSetOf<String>()
            val files = mutableListOf<FileRef>()
            fun add(path: String) {
                val p = path.trim().trim('"', '`', '\'')
                if (p.isBlank() || !seen.add(p)) return
                val name = p.substringAfterLast('/')
                if (name.contains('.')) {
                    files.add(FileRef(p, name, guessMime(name)))
                }
            }

            // 1. Parse arguments JSON for path/file/filename fields
            runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(arguments)
                if (obj is kotlinx.serialization.json.JsonObject) {
                    for (key in JSON_PATH_KEYS) {
                        obj[key]?.let { elem ->
                            val v = (elem as? kotlinx.serialization.json.JsonPrimitive)?.content
                            if (!v.isNullOrBlank()) add(v)
                        }
                    }
                }
            }

            // 2. Match server paths in result text
            for (pattern in listOf(MEDIA_PATH, WORKSPACE_PATH, ABS_PATH)) {
                for (m in pattern.findAll(result)) add(m.value)
            }

            // 3. Handle "name — caption" format (send_file output)
            val dashIdx = result.indexOf('\u2014') // em dash
            if (dashIdx > 0) {
                val before = result.substring(0, dashIdx).trim()
                if (before.contains('.') && !before.contains(' ')) add(before)
            }

            // 4. Bare filenames with extensions
            for (m in BARE_FILENAME.findAll(result)) {
                val name = m.groupValues[1]
                if (name.contains('.') && name.length < 200) add(name)
            }

            // 5. Backtick-wrapped or quoted filenames (e.g., `index.html`, "style.css")
            val QUOTED_FILE = Regex("""[`"']([A-Za-z0-9_. -]+\.[A-Za-z0-9]{1,10})[`"']""")
            for (m in QUOTED_FILE.findAll(result)) {
                add(m.groupValues[1])
            }

            return files
        }

        fun guessMime(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "pdf" -> "application/pdf"
                "json" -> "application/json"
                "txt", "md" -> "text/plain"
                "py" -> "text/x-python"
                "js" -> "application/javascript"
                "kt" -> "text/x-kotlin"
                "java" -> "text/x-java"
                "xml" -> "application/xml"
                "html" -> "text/html"
                "css" -> "text/css"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
        }
    }
}

@Immutable
@Serializable
data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String = "",
    /** Accumulated extended-thinking / reasoning text shown in a collapsible block. */
    val thinking: String = "",
    val attachments: List<Attachment> = emptyList(),
    /** Names of non-image files attached to this message (for display). */
    val fileNames: List<String> = emptyList(),
    val tool: ToolCard? = null,
    /** Files delivered by the agent (e.g. via send_file tool, media field). */
    val files: List<FileRef> = emptyList(),
    val streaming: Boolean = false,
    /** True if this assistant reply was cut off (disconnect) and may be recovered from server. */
    val incomplete: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
