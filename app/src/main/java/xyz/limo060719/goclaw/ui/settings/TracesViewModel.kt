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
import xyz.limo060719.goclaw.data.remote.TraceInfo
import javax.inject.Inject

data class TracesUiState(
    val traces: List<TraceInfo> = emptyList(),
    val loading: Boolean = false,
    val detail: String? = null,
    val detailLoading: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class TracesViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val api: GoClawApi,
) : ViewModel() {

    private val _state = MutableStateFlow(TracesUiState())
    val state: StateFlow<TracesUiState> = _state.asStateFlow()

    init { refresh() }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
    fun closeDetail() { _state.value = _state.value.copy(detail = null) }

    fun refresh() {
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = "尚未配置后端地址 / API 密钥。")
                return@launch
            }
            _state.value = _state.value.copy(loading = true)
            api.traces(s)
                .onSuccess { _state.value = _state.value.copy(traces = it, loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, message = "加载失败：${it.message}") }
        }
    }

    fun openDetail(id: String) {
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(detailLoading = true, detail = null)
            api.traceDetail(s, id)
                .onSuccess { _state.value = _state.value.copy(detail = it, detailLoading = false) }
                .onFailure { _state.value = _state.value.copy(detailLoading = false, message = "加载详情失败：${it.message}") }
        }
    }
}
