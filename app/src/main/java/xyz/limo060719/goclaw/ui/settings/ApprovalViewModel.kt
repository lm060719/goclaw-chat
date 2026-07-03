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
import xyz.limo060719.goclaw.data.remote.ExecApproval
import xyz.limo060719.goclaw.data.remote.GoClawWsClient
import javax.inject.Inject

data class ApprovalUiState(
    val approvals: List<ExecApproval> = emptyList(),
    val loading: Boolean = false,
    val resolvingId: String? = null,
    val message: String? = null,
)

@HiltViewModel
class ApprovalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val ws: GoClawWsClient,
) : ViewModel() {

    private val _state = MutableStateFlow(ApprovalUiState())
    val state: StateFlow<ApprovalUiState> = _state.asStateFlow()

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
            runCatching { ws.listExecApprovals(s) }
                .onSuccess { _state.value = _state.value.copy(approvals = it, loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, message = context.getString(R.string.err_load_failed, it.message ?: "")) }
        }
    }

    fun approve(id: String) = resolve(id, approve = true)
    fun deny(id: String) = resolve(id, approve = false)

    private fun resolve(id: String, approve: Boolean) {
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(resolvingId = id)
            runCatching { ws.resolveExecApproval(s, id, approve) }
                .onSuccess { ok ->
                    _state.value = _state.value.copy(
                        resolvingId = null,
                        approvals = if (ok) _state.value.approvals.filterNot { it.id == id } else _state.value.approvals,
                        message = if (ok) (if (approve) context.getString(R.string.common_approved) else context.getString(R.string.common_rejected)) else context.getString(R.string.err_op_failed),
                    )
                }
                .onFailure { _state.value = _state.value.copy(resolvingId = null, message = context.getString(R.string.err_op_failed_fmt, it.message ?: "")) }
        }
    }
}
