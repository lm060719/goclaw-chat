package xyz.limo060719.goclaw.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.data.remote.GoClawWsClient
import xyz.limo060719.goclaw.data.remote.SessionSummary
import javax.inject.Inject

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val loading: Boolean = false,
    val busyKey: String? = null,
    val message: String? = null,
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val ws: GoClawWsClient,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

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
            runCatching { ws.listSessions(s) }
                .onSuccess { list ->
                    _state.value = _state.value.copy(
                        sessions = list.sortedByDescending { it.updated },
                        loading = false,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loading = false, message = "加载失败：${it.message}") }
        }
    }

    fun compact(key: String) = action(key, "已压缩历史") { ws.compactSession(it, key) }

    fun reset(key: String) = action(key, "已清空历史") { ws.resetSession(it, key) }

    fun delete(key: String) {
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(busyKey = key)
            val ok = runCatching { ws.deleteSession(s, key) }.getOrDefault(false)
            _state.value = _state.value.copy(
                busyKey = null,
                sessions = if (ok) _state.value.sessions.filterNot { it.key == key } else _state.value.sessions,
                message = if (ok) "已删除会话" else "删除失败",
            )
        }
    }

    private fun action(key: String, okMsg: String, op: suspend (xyz.limo060719.goclaw.data.GoClawSettings) -> Boolean) {
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(busyKey = key)
            val ok = runCatching { op(s) }.getOrDefault(false)
            _state.value = _state.value.copy(busyKey = null, message = if (ok) okMsg else "操作失败")
        }
    }
}
