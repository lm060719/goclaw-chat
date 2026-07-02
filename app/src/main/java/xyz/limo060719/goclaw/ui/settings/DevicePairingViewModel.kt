package xyz.limo060719.goclaw.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
                _state.value = _state.value.copy(message = "尚未配置后端地址 / API 密钥。")
                return@launch
            }
            _state.value = _state.value.copy(loading = true)
            runCatching { ws.listPairings(s) }
                .onSuccess { _state.value = _state.value.copy(pairings = it, loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, message = "加载失败：${it.message}") }
        }
    }

    fun requestPairing(channel: String, chatId: String) {
        if (channel.isBlank() || chatId.isBlank()) {
            _state.value = _state.value.copy(message = "请填写渠道与 Chat ID。")
            return
        }
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = "尚未配置后端地址 / API 密钥。")
                return@launch
            }
            _state.value = _state.value.copy(requesting = true)
            runCatching { ws.requestPairing(s, channel.trim(), chatId.trim()) }
                .onSuccess { code ->
                    _state.value = _state.value.copy(
                        requesting = false,
                        requestedCode = code,
                        message = if (code.isNullOrBlank()) "请求失败" else null,
                    )
                    if (!code.isNullOrBlank()) refresh()
                }
                .onFailure { _state.value = _state.value.copy(requesting = false, message = "请求失败：${it.message}") }
        }
    }

    fun approve(pairing: DevicePairing) {
        val code = pairing.code
        if (code.isBlank()) { _state.value = _state.value.copy(message = "该配对缺少配对码，无法批准。"); return }
        resolve(pairing, "已批准") { s -> ws.approvePairing(s, code, s.userId) }
    }

    fun deny(pairing: DevicePairing) {
        val code = pairing.code
        if (code.isBlank()) { _state.value = _state.value.copy(message = "该配对缺少配对码，无法拒绝。"); return }
        resolve(pairing, "已拒绝") { s -> ws.denyPairing(s, code) }
    }

    fun revoke(pairing: DevicePairing) {
        if (pairing.channel.isBlank() || pairing.senderId.isBlank()) {
            _state.value = _state.value.copy(message = "该配对缺少 channel / senderId，无法撤销。")
            return
        }
        resolve(pairing, "已撤销") { s -> ws.revokePairing(s, pairing.channel, pairing.senderId) }
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
                        message = if (ok) successMsg else "操作失败",
                    )
                }
                .onFailure { _state.value = _state.value.copy(busyKey = null, message = "操作失败：${it.message}") }
        }
    }
}
