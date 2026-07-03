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
import xyz.limo060719.goclaw.data.remote.DevicePairing
import xyz.limo060719.goclaw.data.remote.GoClawWsClient
import javax.inject.Inject

data class DevicePairingUiState(
    val pairings: List<DevicePairing> = emptyList(),
    val loading: Boolean = false,
    /** stableKey of the pairing currently being approved/denied/revoked, if any. */
    val busyKey: String? = null,
    val requesting: Boolean = false,
    /** The pairing code returned by the most recent request, shown in a dialog. */
    val requestedCode: String? = null,
    val message: String? = null,
)

@HiltViewModel
class DevicePairingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val ws: GoClawWsClient,
) : ViewModel() {

    private val _state = MutableStateFlow(DevicePairingUiState())
    val state: StateFlow<DevicePairingUiState> = _state.asStateFlow()

    init { refresh() }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
    fun clearRequestedCode() { _state.value = _state.value.copy(requestedCode = null) }

    fun refresh() {
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = context.getString(R.string.err_not_configured))
                return@launch
            }
            _state.value = _state.value.copy(loading = true)
            runCatching { ws.listPairings(s) }
                .onSuccess { _state.value = _state.value.copy(pairings = it, loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, message = context.getString(R.string.err_load_failed, it.message ?: "")) }
        }
    }

    fun requestPairing(channel: String, chatId: String) {
        if (channel.isBlank() || chatId.isBlank()) {
            _state.value = _state.value.copy(message = context.getString(R.string.pairing_need_channel_chat))
            return
        }
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = context.getString(R.string.err_not_configured))
                return@launch
            }
            _state.value = _state.value.copy(requesting = true)
            runCatching { ws.requestPairing(s, channel.trim(), chatId.trim()) }
                .onSuccess { code ->
                    _state.value = _state.value.copy(
                        requesting = false,
                        requestedCode = code,
                        message = if (code.isNullOrBlank()) context.getString(R.string.err_request_failed) else null,
                    )
                    if (!code.isNullOrBlank()) refresh()
                }
                .onFailure { _state.value = _state.value.copy(requesting = false, message = context.getString(R.string.err_op_failed_fmt, it.message ?: "")) }
        }
    }

    fun approve(pairing: DevicePairing) {
        val code = pairing.code
        if (code.isBlank()) { _state.value = _state.value.copy(message = context.getString(R.string.pairing_no_code_approve)); return }
        resolve(pairing, context.getString(R.string.common_approved)) { s -> ws.approvePairing(s, code, s.userId) }
    }

    fun deny(pairing: DevicePairing) {
        val code = pairing.code
        if (code.isBlank()) { _state.value = _state.value.copy(message = context.getString(R.string.pairing_no_code_deny)); return }
        resolve(pairing, context.getString(R.string.common_rejected)) { s -> ws.denyPairing(s, code) }
    }

    fun revoke(pairing: DevicePairing) {
        if (pairing.channel.isBlank() || pairing.senderId.isBlank()) {
            _state.value = _state.value.copy(message = context.getString(R.string.pairing_no_channel_revoke))
            return
        }
        resolve(pairing, context.getString(R.string.common_revoked)) { s -> ws.revokePairing(s, pairing.channel, pairing.senderId) }
    }

    private fun resolve(
        pairing: DevicePairing,
        successMsg: String,
        action: suspend (xyz.limo060719.goclaw.data.GoClawSettings) -> Boolean,
    ) {
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(busyKey = pairing.stableKey)
            runCatching { action(s) }
                .onSuccess { ok ->
                    _state.value = _state.value.copy(
                        busyKey = null,
                        pairings = if (ok) _state.value.pairings.filterNot { it.stableKey == pairing.stableKey }
                        else _state.value.pairings,
                        message = if (ok) successMsg else context.getString(R.string.err_op_failed),
                    )
                }
                .onFailure { _state.value = _state.value.copy(busyKey = null, message = context.getString(R.string.err_op_failed_fmt, it.message ?: "")) }
        }
    }
}
