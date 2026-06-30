package xyz.limo060719.goclaw.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.domain.skills.Skill
import xyz.limo060719.goclaw.domain.skills.SkillRepository
import javax.inject.Inject

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val repository: SkillRepository,
) : ViewModel() {

    val skills: StateFlow<List<Skill>> = repository.skills

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun import(uri: Uri) {
        viewModelScope.launch {
            repository.importFromUri(uri)
                .onSuccess { _message.value = "已导入：${it.name}" }
                .onFailure { _message.value = "导入失败：${it.message}" }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) = repository.setEnabled(id, enabled)

    fun remove(id: String) = repository.remove(id)

    fun clearMessage() { _message.value = null }
}
