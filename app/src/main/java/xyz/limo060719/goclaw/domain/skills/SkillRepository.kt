package xyz.limo060719.goclaw.domain.skills

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val instructions: String = "",
    val enabled: Boolean = true,
)

/**
 * Skills are imported instruction bundles. Each enabled skill contributes
 * its instructions to the system message of every request.
 *
 * Import formats:
 *  - .json : a Skill object ({name, description, instructions})
 *  - SKILL.md / .md with YAML frontmatter : Anthropic Agent Skill format
 *    (`---\nname: ...\ndescription: ...\n---` + markdown body) → body becomes the instructions
 *  - .md / .txt / other text : treated as raw instructions, name = file name
 *  - .zip : a skill folder or a whole repo — every `SKILL.md` inside is imported as one skill
 */
@Singleton
class SkillRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val file: File get() = File(context.filesDir, "skills.json")

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    init { _skills.value = load() }

    private fun load(): List<Skill> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString(ListSerializer(Skill.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun persist() = runCatching {
        file.writeText(json.encodeToString(ListSerializer(Skill.serializer()), _skills.value))
    }

    /** Imports one or more skills from the picked document. Returns every skill added. */
    suspend fun importFromUri(uri: Uri): Result<List<Skill>> = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryDisplayName(uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取文件")

            val imported = if (name.endsWith(".zip", true) || isZip(bytes)) {
                importZip(bytes)
            } else {
                listOf(parseTextSkill(name, bytes.toString(Charsets.UTF_8)))
            }
            if (imported.isEmpty()) error("未找到可导入的技能（缺少 SKILL.md）")
            imported.forEach { add(it) }
            imported
        }
    }

    /** Parses a single text document into a Skill (JSON object, frontmatter .md, or raw text). */
    private fun parseTextSkill(fileName: String, text: String): Skill {
        val trimmed = text.trimStart()
        return when {
            fileName.endsWith(".json", true) || trimmed.startsWith("{") ->
                json.decodeFromString(Skill.serializer(), text)
            trimmed.startsWith("---") ->
                parseFrontmatterSkill(fileName.substringBeforeLast('.'), trimmed)
            else -> Skill(name = fileName.substringBeforeLast('.'), instructions = text)
        }
    }

    /** Scans a zip for every `SKILL.md` entry and parses each into a Skill. */
    private fun importZip(bytes: ByteArray): List<Skill> {
        val out = mutableListOf<Skill>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory &&
                    entry.name.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)
                ) {
                    val content = zis.readBytes().toString(Charsets.UTF_8)
                    // Name fallback = the folder containing the SKILL.md (e.g. skills/gpt-taste/SKILL.md).
                    val folder = entry.name.substringBeforeLast('/').substringAfterLast('/')
                    out += parseTextSkill("${folder.ifBlank { "skill" }}.md", content)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }

    /**
     * Parses an Agent-Skill markdown file: a leading `---`-delimited YAML frontmatter block
     * (we only read `name`/`description`) followed by the markdown body used as instructions.
     */
    private fun parseFrontmatterSkill(fallbackName: String, text: String): Skill {
        val afterOpen = text.removePrefix("---")
        val close = afterOpen.indexOf("\n---")
        if (close < 0) return Skill(name = fallbackName, instructions = text)
        val frontmatter = afterOpen.substring(0, close)
        val body = afterOpen.substring(close + 4).trimStart('\r', '\n', '-').trim()
        val meta = parseSimpleYaml(frontmatter)
        return Skill(
            name = meta["name"]?.takeIf { it.isNotBlank() } ?: fallbackName,
            description = meta["description"].orEmpty(),
            instructions = body.ifBlank { text },
        )
    }

    /** Minimal `key: value` YAML reader (top-level scalars only; enough for name/description). */
    private fun parseSimpleYaml(fm: String): Map<String, String> =
        fm.lineSequence().mapNotNull { line ->
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#") || !l.contains(':')) return@mapNotNull null
            val key = l.substringBefore(':').trim()
            var value = l.substringAfter(':').trim()
            if (value.length >= 2 &&
                ((value.startsWith('"') && value.endsWith('"')) ||
                    (value.startsWith('\'') && value.endsWith('\'')))
            ) {
                value = value.substring(1, value.length - 1)
            }
            if (key.isEmpty()) null else key to value
        }.toMap()

    private fun isZip(b: ByteArray): Boolean =
        b.size >= 2 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()

    fun add(skill: Skill) {
        _skills.value = _skills.value.filterNot { it.id == skill.id } + skill
        persist()
    }

    fun remove(id: String) {
        _skills.value = _skills.value.filterNot { it.id == id }
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _skills.value = _skills.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persist()
    }

    /** Combined system text from all enabled skills, or null if none. */
    fun systemPrompt(): String? {
        val active = _skills.value.filter { it.enabled && it.instructions.isNotBlank() }
        if (active.isEmpty()) return null
        return active.joinToString("\n\n---\n\n") { s ->
            buildString {
                append("# Skill: ").append(s.name).append('\n')
                if (s.description.isNotBlank()) append(s.description).append("\n\n")
                append(s.instructions)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "skill"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        }
        return name
    }
}
