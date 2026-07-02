package xyz.limo060719.goclaw.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.data.remote.GoClawWsClient
import xyz.limo060719.goclaw.data.remote.LogLine
import javax.inject.Inject

data class LogsUiState(
    val lines: List<LogLine> = emptyList(),
    val streaming: Boolean = false,
    /** Minimum severity filter; "" means all levels. */
    val level: String = "",
    val message: String? = null,
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val ws: GoClawWsClient,
) : ViewModel() {

    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
    fun clearLines() { _state.value = _state.value.copy(lines = emptyList()) }

    fun toggle() { if (_state.value.streaming) stop() else start() }

    /** Switches the severity filter; restarts the stream if it's currently running. */
    fun setLevel(level: String) {
        if (_state.value.level == level) return
        val wasStreaming = _state.value.streaming
        _state.value = _state.value.copy(level = level)
        if (wasStreaming) { stop(); start() }
    }

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = "尚未配置后端地址 / API 密钥。")
                return@launch
            }
            _state.value = _state.value.copy(streaming = true)
            ws.tailLogs(s, _state.value.level.ifBlank { null })
                .catch { _state.value = _state.value.copy(message = "日志流中断：${it.message}") }
                .onCompletion { _state.value = _state.value.copy(streaming = false) }
                .collect { line ->
                    _state.value = _state.value.copy(
                        lines = (_state.value.lines + line).takeLast(MAX_LINES),
                    )
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(streaming = false)
    }

    override fun onCleared() {
        job?.cancel()
    }

    companion object {
        private const val MAX_LINES = 500
    }
}
