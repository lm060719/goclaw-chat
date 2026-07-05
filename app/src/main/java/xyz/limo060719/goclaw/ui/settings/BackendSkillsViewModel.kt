package xyz.limo060719.goclaw.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.data.remote.BackendSkill
import xyz.limo060719.goclaw.data.remote.GoClawApi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

data class BackendSkillsUiState(
    val skills: List<BackendSkill> = emptyList(),
    val loading: Boolean = false,
    val uploading: Boolean = false,
    val busyId: String? = null,
    val message: String? = null,
)

@HiltViewModel
class BackendSkillsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val api: GoClawApi,
) : ViewModel() {

    private val _state = MutableStateFlow(BackendSkillsUiState())
    val state: StateFlow<BackendSkillsUiState> = _state.asStateFlow()

    init { refresh() }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun refresh() {
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = context.getString(R.string.err_not_configured))
                return@launch
            }
            _state.value = _state.value.copy(loading = true)
            api.listBackendSkills(s)
                .onSuccess { _state.value = _state.value.copy(skills = it, loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, message = context.getString(R.string.err_load_failed, it.message ?: "")) }
        }
    }

    fun upload(uri: Uri) {
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = context.getString(R.string.err_not_configured_short))
                return@launch
            }
            _state.value = _state.value.copy(uploading = true)
            val raw = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (raw == null) {
                _state.value = _state.value.copy(uploading = false, message = context.getString(R.string.err_file_read))
                return@launch
            }
            // Backend requires SKILL.md at the zip root; repackage each SKILL.md folder so it complies.
            val packages = withContext(Dispatchers.IO) {
                runCatching { repackageSkillZips(raw) }.getOrDefault(emptyList())
            }
            if (packages.isEmpty()) {
                _state.value = _state.value.copy(
                    uploading = false,
                    message = context.getString(R.string.backend_skills_no_skillmd),
                )
                return@launch
            }
            var ok = 0
            var lastErr: String? = null
            for ((name, zipBytes) in packages) {
                api.uploadSkill(s, zipBytes, name)
                    .onSuccess { ok++ }
                    .onFailure { lastErr = it.message }
            }
            _state.value = _state.value.copy(
                uploading = false,
                message = if (ok == packages.size) context.getString(R.string.backend_skills_uploaded_fmt, ok)
                else context.getString(R.string.backend_skills_upload_partial_fmt, ok, packages.size, lastErr ?: ""),
            )
            refresh()
        }
    }

    /**
     * Splits/normalizes a picked zip into one upload per `SKILL.md`, each repackaged so SKILL.md
     * sits at the archive root (the backend rejects deeper nesting).
     */
    private fun repackageSkillZips(zipBytes: ByteArray): List<Pair<String, ByteArray>> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zis.readBytes()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        val skillMds = entries.keys.filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
        return skillMds.map { md ->
            val prefix = md.substringBeforeLast("SKILL.md") // "dir/sub/" or "" for a root SKILL.md
            val members = entries.filterKeys { it.startsWith(prefix) }
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zos ->
                for ((path, data) in members) {
                    val rel = path.removePrefix(prefix)
                    if (rel.isBlank()) continue
                    zos.putNextEntry(ZipEntry(rel))
                    zos.write(data)
                    zos.closeEntry()
                }
            }
            val skillName = prefix.trimEnd('/').substringAfterLast('/').ifBlank { "skill" }
            "$skillName.zip" to baos.toByteArray()
        }
    }

    fun toggle(id: String) {
        viewModelScope.launch {
            val s = settingsStore.current()
            val target = !(_state.value.skills.firstOrNull { it.id == id }?.enabled ?: false)
            _state.value = _state.value.copy(busyId = id)
            api.toggleBackendSkill(s, id, target)
                .onSuccess {
                    _state.value = _state.value.copy(
                        busyId = null,
                        skills = _state.value.skills.map { if (it.id == id) it.copy(enabled = target) else it },
                    )
                }
                .onFailure { _state.value = _state.value.copy(busyId = null, message = context.getString(R.string.err_op_failed_fmt, it.message ?: "")) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(busyId = id)
            api.deleteBackendSkill(s, id)
                .onSuccess {
                    _state.value = _state.value.copy(
                        busyId = null,
                        skills = _state.value.skills.filterNot { it.id == id },
                        message = context.getString(R.string.common_deleted),
                    )
                }
                .onFailure { _state.value = _state.value.copy(busyId = null, message = context.getString(R.string.err_delete_failed_fmt, it.message ?: "")) }
        }
    }

    private fun queryName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "skill.zip"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        }
        return name
    }
}
