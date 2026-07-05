package xyz.limo060719.goclaw.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.ApiKeySecretStore
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.data.remote.ApiKeyInfo
import xyz.limo060719.goclaw.data.remote.GoClawWsClient
import javax.inject.Inject

data class ApiKeysUiState(
    val keys: List<ApiKeyInfo> = emptyList(),
    val loading: Boolean = false,
    val creating: Boolean = false,
    val revokingId: String? = null,
    /** The raw secret returned once by api_keys.create; shown in a dialog until dismissed. */
    val createdSecret: String? = null,
    /** Plaintext of keys created in this app (keyId → secret), persisted locally. */
    val savedSecrets: Map<String, String> = emptyMap(),
    val message: String? = null,
)

@HiltViewModel
class ApiKeysViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val secretStore: ApiKeySecretStore,
    private val ws: GoClawWsClient,
) : ViewModel() {

    private val _state = MutableStateFlow(ApiKeysUiState())
    val state: StateFlow<ApiKeysUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            secretStore.secrets.collect { _state.value = _state.value.copy(savedSecrets = it) }
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
    fun clearCreatedSecret() { _state.value = _state.value.copy(createdSecret = null) }

    fun refresh() {
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = context.getString(R.string.err_not_configured))
                return@launch
            }
            _state.value = _state.value.copy(loading = true)
            runCatching { ws.listApiKeys(s) }
                .onSuccess { _state.value = _state.value.copy(keys = it, loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, message = context.getString(R.string.err_load_failed, it.message ?: "")) }
        }
    }

    /** [expiresInDays] null = 不过期; otherwise converted to seconds for the `expires_in` param. */
    fun create(name: String, scopes: List<String>, expiresInDays: Int?) {
        if (name.isBlank()) { _state.value = _state.value.copy(message = context.getString(R.string.apikeys_need_name)); return }
        if (scopes.isEmpty()) { _state.value = _state.value.copy(message = context.getString(R.string.apikeys_need_scope)); return }
        val expiresInSec = expiresInDays?.takeIf { it > 0 }?.let { it * 86_400 }
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(creating = true)
            runCatching { ws.createApiKey(s, name.trim(), scopes, expiresInSec) }
                .onSuccess { created ->
                    if (created != null) {
                        // Persist the plaintext locally so it can be viewed/copied again later.
                        secretStore.save(created.id, created.key)
                        _state.value = _state.value.copy(creating = false, createdSecret = created.key)
                        refresh()
                    } else {
                        _state.value = _state.value.copy(creating = false, message = context.getString(R.string.apikeys_create_failed))
                    }
                }
                .onFailure { _state.value = _state.value.copy(creating = false, message = context.getString(R.string.apikeys_create_failed_fmt, it.message ?: "")) }
        }
    }

    fun revoke(id: String) {
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(revokingId = id)
            runCatching { ws.revokeApiKey(s, id) }
                .onSuccess { ok ->
                    if (ok) secretStore.remove(id)
                    _state.value = _state.value.copy(
                        revokingId = null,
                        keys = if (ok) _state.value.keys.filterNot { it.id == id } else _state.value.keys,
                        message = if (ok) context.getString(R.string.common_revoked) else context.getString(R.string.err_op_failed),
                    )
                }
                .onFailure { _state.value = _state.value.copy(revokingId = null, message = context.getString(R.string.apikeys_revoke_failed_fmt, it.message ?: "")) }
        }
    }
}
