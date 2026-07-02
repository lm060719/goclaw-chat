package xyz.limo060719.goclaw.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.data.GoClawSettings
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.data.remote.GoClawApi
import xyz.limo060719.goclaw.data.remote.GoClawWsClient
import xyz.limo060719.goclaw.data.remote.dto.AgentInfo
import xyz.limo060719.goclaw.data.remote.dto.ProviderInfo
import javax.inject.Inject

data class SettingsUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val userId: String = "",
    val agent: String = "",
    val model: String = "",
    val savedAgents: List<String> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    val loadingAgents: Boolean = false,
    // Model configuration (changes the agent's model server-side via agents.update).
    val providers: List<ProviderInfo> = emptyList(),
    val selectedProviderId: String? = null,
    val models: List<String> = emptyList(),
    val loadingProviders: Boolean = false,
    val loadingModels: Boolean = false,
    val applyingModel: Boolean = false,
    val testingConnection: Boolean = false,
    val gatewayOnline: Boolean? = null,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val api: GoClawApi,
    private val ws: GoClawWsClient,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(
                baseUrl = s.baseUrl,
                apiKey = s.apiKey,
                userId = s.userId,
                agent = s.agent,
                model = s.model,
                savedAgents = s.savedAgents,
            )
        }
    }

    fun onBaseUrl(v: String) { _state.value = _state.value.copy(baseUrl = v) }
    fun onApiKey(v: String) { _state.value = _state.value.copy(apiKey = v) }
    fun onUserId(v: String) { _state.value = _state.value.copy(userId = v) }
    fun onAgent(v: String) { _state.value = _state.value.copy(agent = v) }
    fun onModel(v: String) { _state.value = _state.value.copy(model = v) }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun selectSavedAgent(key: String) { _state.value = _state.value.copy(agent = key) }

    fun saveCurrentAgent() {
        val key = _state.value.agent.trim()
        if (key.isBlank()) return
        val next = (_state.value.savedAgents + key).distinct()
        _state.value = _state.value.copy(savedAgents = next, message = "已保存 Agent：$key")
        viewModelScope.launch { settingsStore.updateSavedAgents(next) }
    }

    fun removeSavedAgent(key: String) {
        val next = _state.value.savedAgents - key
        _state.value = _state.value.copy(savedAgents = next)
        viewModelScope.launch { settingsStore.updateSavedAgents(next) }
    }

    fun fetchAgents() {
        val st = _state.value
        if (st.baseUrl.isBlank() || st.apiKey.isBlank()) {
            _state.value = st.copy(message = "请先填写后端地址和 API 密钥")
            return
        }
        _state.value = st.copy(loadingAgents = true)
        viewModelScope.launch {
            val s = GoClawSettings(
                baseUrl = st.baseUrl.trim().trimEnd('/'),
                apiKey = st.apiKey.trim(),
                userId = st.userId.trim(),
            )
            // WS agents.list returns all agents; REST /v1/agents is a filtered subset → fallback only.
            runCatching { ws.listAgents(s).ifEmpty { api.agents(s).getOrElse { emptyList() } } }
                .onSuccess { list ->
                    _state.value = _state.value.copy(
                        agents = list,
                        loadingAgents = false,
                        message = if (list.isEmpty()) "未发现 Agent" else null,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(loadingAgents = false, message = "加载失败：${it.message}")
                }
        }
    }

    fun testConnection() {
        val st = _state.value
        if (st.baseUrl.isBlank() || st.apiKey.isBlank()) {
            _state.value = st.copy(message = "请先填写后端地址和 API 密钥")
            return
        }
        _state.value = st.copy(testingConnection = true)
        viewModelScope.launch {
            val version = runCatching { ws.gatewayVersion(snapshotSettings()) }.getOrNull()
            _state.value = _state.value.copy(
                testingConnection = false,
                gatewayOnline = version != null,
                message = if (version != null) "已连接 ✓ 网关 $version" else "连接失败，请检查地址/密钥/网络",
            )
        }
    }

    /** Settings snapshot from the current form (so the user can query before saving). */
    private fun snapshotSettings(): GoClawSettings {
        val st = _state.value
        return GoClawSettings(
            baseUrl = st.baseUrl.trim().trimEnd('/'),
            apiKey = st.apiKey.trim(),
            userId = st.userId.trim(),
        )
    }

    fun fetchProviders() {
        val st = _state.value
        if (st.baseUrl.isBlank() || st.apiKey.isBlank()) {
            _state.value = st.copy(message = "请先填写后端地址和 API 密钥")
            return
        }
        _state.value = st.copy(loadingProviders = true)
        viewModelScope.launch {
            api.providers(snapshotSettings())
                .onSuccess { list ->
                    _state.value = _state.value.copy(
                        providers = list,
                        loadingProviders = false,
                        message = if (list.isEmpty()) "未发现供应商" else null,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loadingProviders = false, message = "加载供应商失败：${it.message}") }
        }
    }

    fun selectProvider(providerId: String) {
        _state.value = _state.value.copy(selectedProviderId = providerId, models = emptyList(), loadingModels = true)
        viewModelScope.launch {
            api.providerModels(snapshotSettings(), providerId)
                .onSuccess { _state.value = _state.value.copy(models = it, loadingModels = false, message = if (it.isEmpty()) "该供应商无可用模型" else null) }
                .onFailure { _state.value = _state.value.copy(loadingModels = false, message = "加载模型失败：${it.message}") }
        }
    }

    fun selectModel(model: String) { _state.value = _state.value.copy(model = model) }

    /** Applies the chosen model to the selected agent server-side via agents.update. */
    fun applyModelToAgent() {
        val st = _state.value
        val target = st.agents.firstOrNull { it.resolvedKey == st.agent }
        val agentId = (target?.id?.takeIf { it.isNotBlank() } ?: st.agent.trim())
        if (agentId.isBlank()) {
            _state.value = st.copy(message = "请先在上方加载并选择一个 Agent")
            return
        }
        if (st.model.isBlank()) {
            _state.value = st.copy(message = "请先选择模型")
            return
        }
        val providerName = st.providers.firstOrNull { it.resolvedId == st.selectedProviderId }?.providerName
        _state.value = st.copy(applyingModel = true)
        viewModelScope.launch {
            val ok = runCatching { ws.updateAgentModel(snapshotSettings(), agentId, st.model, providerName) }
                .getOrDefault(false)
            _state.value = _state.value.copy(
                applyingModel = false,
                message = if (ok) "已更新 Agent 模型为 ${st.model}" else "更新失败（可能需要管理员权限）",
            )
        }
    }

    fun save() {
        val st = _state.value
        // Model is optional here — it's configured server-side on the agent; chat.send never sends it.
        viewModelScope.launch {
            settingsStore.update(
                GoClawSettings(
                    baseUrl = st.baseUrl,
                    apiKey = st.apiKey,
                    userId = st.userId,
                    agent = st.agent,
                    model = st.model,
                )
            )
            settingsStore.updateSavedAgents(st.savedAgents)
            _state.value = _state.value.copy(message = "已保存")
        }
    }
}
