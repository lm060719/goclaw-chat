package xyz.limo060719.goclaw.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.domain.skills.Skill
import xyz.limo060719.goclaw.domain.skills.SkillRepository
import javax.inject.Inject

@HiltViewModel
class SkillsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SkillRepository,
) : ViewModel() {

    val skills: StateFlow<List<Skill>> = repository.skills

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun import(uri: Uri) {
        viewModelScope.launch {
            repository.importFromUri(uri)
                .onSuccess { list ->
                    _message.value = when (list.size) {
                        0 -> context.getString(R.string.skills_import_none)
                        1 -> context.getString(R.string.skills_imported_one_fmt, list.first().name)
                        else -> context.getString(R.string.skills_imported_many_fmt, list.size, list.joinToString(", ") { it.name })
                    }
                }
                .onFailure { _message.value = context.getString(R.string.skills_import_failed_fmt, it.message ?: "") }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) = repository.setEnabled(id, enabled)

    fun remove(id: String) = repository.remove(id)

    fun clearMessage() { _message.value = null }
}
