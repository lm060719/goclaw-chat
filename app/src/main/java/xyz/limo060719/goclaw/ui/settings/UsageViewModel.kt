package xyz.limo060719.goclaw.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.data.remote.GoClawApi
import xyz.limo060719.goclaw.data.remote.UsageSummary
import javax.inject.Inject

data class UsageUiState(
    val usage: UsageSummary? = null,
    val raw: String? = null,
    val loading: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val api: GoClawApi,
) : ViewModel() {

    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    init { refresh() }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun refresh() {
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = "尚未配置后端地址 / API 密钥。")
                return@launch
            }
            _state.value = _state.value.copy(loading = true)
            val raw = api.usageRaw(s).getOrNull()
            api.usageSummary(s)
                .onSuccess { _state.value = _state.value.copy(usage = it, raw = raw, loading = false) }
                .onFailure { _state.value = _state.value.copy(raw = raw, loading = false, message = "加载失败：${it.message}") }
        }
    }
}
