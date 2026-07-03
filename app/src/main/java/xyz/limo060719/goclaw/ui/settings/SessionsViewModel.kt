package xyz.limo060719.goclaw.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.R
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
    @ApplicationContext private val context: Context,
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
                _state.value = _state.value.copy(message = context.getString(R.string.err_not_configured))
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
                .onFailure { _state.value = _state.value.copy(loading = false, message = context.getString(R.string.err_load_failed, it.message ?: "")) }
        }
    }

    fun compact(key: String) = action(key, context.getString(R.string.sessions_compacted)) { ws.compactSession(it, key) }

    fun reset(key: String) = action(key, context.getString(R.string.sessions_cleared)) { ws.resetSession(it, key) }

    fun delete(key: String) {
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(busyKey = key)
            val ok = runCatching { ws.deleteSession(s, key) }.getOrDefault(false)
            _state.value = _state.value.copy(
                busyKey = null,
                sessions = if (ok) _state.value.sessions.filterNot { it.key == key } else _state.value.sessions,
                message = if (ok) context.getString(R.string.sessions_deleted) else context.getString(R.string.common_delete_failed),
            )
        }
    }

    private fun action(key: String, okMsg: String, op: suspend (xyz.limo060719.goclaw.data.GoClawSettings) -> Boolean) {
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(busyKey = key)
            val ok = runCatching { op(s) }.getOrDefault(false)
            _state.value = _state.value.copy(busyKey = null, message = if (ok) okMsg else context.getString(R.string.err_op_failed))
        }
    }
}
