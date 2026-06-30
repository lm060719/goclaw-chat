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
import java.io.File
import java.util.UUID
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
 *  - .md / .txt / other text : treated as raw instructions, name = file name
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

    suspend fun importFromUri(uri: Uri): Result<Skill> = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryDisplayName(uri)
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: error("无法读取文件")

            val skill = if (name.endsWith(".json", true) || text.trimStart().startsWith("{")) {
                json.decodeFromString(Skill.serializer(), text)
            } else {
                Skill(name = name.substringBeforeLast('.'), instructions = text)
            }
            add(skill)
            skill
        }
    }

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
