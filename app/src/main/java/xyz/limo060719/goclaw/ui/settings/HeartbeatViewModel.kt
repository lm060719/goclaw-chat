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
import xyz.limo060719.goclaw.data.remote.GoClawWsClient
import xyz.limo060719.goclaw.data.remote.HeartbeatConfig
import xyz.limo060719.goclaw.data.remote.HeartbeatLog
import xyz.limo060719.goclaw.data.remote.HeartbeatTarget
import xyz.limo060719.goclaw.data.remote.dto.AgentInfo
import xyz.limo060719.goclaw.data.remote.dto.ProviderInfo
import javax.inject.Inject

data class HeartbeatUiState(
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgent: String = "",
    val loadingAgents: Boolean = false,
    val loadingConfig: Boolean = false,
    val saving: Boolean = false,
    val toggling: Boolean = false,
    val testing: Boolean = false,
    // editable config form
    val enabled: Boolean = false,
    val intervalMinutes: String = "5",
    val prompt: String = "",
    // provider/model are picked from backend-loaded lists (providers → models)
    val providerName: String = "",
    val model: String = "",
    val providers: List<ProviderInfo> = emptyList(),
    val loadingProviders: Boolean = false,
    val selectedProviderId: String? = null,
    val models: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    // HEARTBEAT.md
    val checklist: String = "",
    val loadingChecklist: Boolean = false,
    val savingChecklist: Boolean = false,
    // logs & targets
    val logs: List<HeartbeatLog> = emptyList(),
    val loadingLogs: Boolean = false,
    val targets: List<HeartbeatTarget> = emptyList(),
    val loadingTargets: Boolean = false,
    val message: String? = null,
) {
    val hasAgent: Boolean get() = selectedAgent.isNotBlank()
}

@HiltViewModel
class HeartbeatViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val api: GoClawApi,
    private val ws: GoClawWsClient,
) : ViewModel() {

    private val _state = MutableStateFlow(HeartbeatUiState())
    val state: StateFlow<HeartbeatUiState> = _state.asStateFlow()

    init { loadAgents() }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun onIntervalMinutes(v: String) { _state.value = _state.value.copy(intervalMinutes = v.filter(Char::isDigit)) }
    fun onPrompt(v: String) { _state.value = _state.value.copy(prompt = v) }
    fun onChecklist(v: String) { _state.value = _state.value.copy(checklist = v) }
    fun selectModel(model: String) { _state.value = _state.value.copy(model = model) }

    /** Loads the backend provider list (needed before models can be loaded). */
    fun loadProviders() {
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) return@launch
            _state.value = _state.value.copy(loadingProviders = true)
            api.providers(s)
                .onSuccess { _state.value = _state.value.copy(providers = it, loadingProviders = false) }
                .onFailure { _state.value = _state.value.copy(loadingProviders = false, message = "加载供应商失败：${it.message}") }
        }
    }

    /** Selects a provider and loads its available models. */
    fun selectProvider(providerId: String) {
        val provider = _state.value.providers.firstOrNull { it.resolvedId == providerId }
        _state.value = _state.value.copy(
            selectedProviderId = providerId,
            providerName = provider?.providerName.orEmpty(),
            models = emptyList(),
            loadingModels = true,
        )
        viewModelScope.launch {
            val s = settingsStore.current()
            api.providerModels(s, providerId)
                .onSuccess {
                    _state.value = _state.value.copy(
                        models = it, loadingModels = false,
                        message = if (it.isEmpty()) "该供应商无可用模型" else null,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loadingModels = false, message = "加载模型失败：${it.message}") }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = "尚未配置后端地址 / API 密钥。")
                return@launch
            }
            _state.value = _state.value.copy(loadingAgents = true)
            api.agents(s)
                .onSuccess { list ->
                    _state.value = _state.value.copy(agents = list, loadingAgents = false)
                    // Auto-select the agent from settings, else the first one.
                    val preferred = s.agent.ifBlank { list.firstOrNull()?.resolvedKey.orEmpty() }
                    if (preferred.isNotBlank() && _state.value.selectedAgent.isBlank()) selectAgent(preferred)
                }
                .onFailure { _state.value = _state.value.copy(loadingAgents = false, message = "加载 Agent 失败：${it.message}") }
        }
    }

    fun selectAgent(agentKey: String) {
        if (agentKey.isBlank()) return
        _state.value = _state.value.copy(selectedAgent = agentKey)
        if (_state.value.providers.isEmpty()) loadProviders()
        loadConfig()
        loadChecklist()
        refreshLogs()
        refreshTargets()
    }

    private fun loadConfig() {
        val agent = _state.value.selectedAgent
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(loadingConfig = true)
            runCatching { ws.getHeartbeat(s, agent) }
                .onSuccess { cfg ->
                    _state.value = _state.value.copy(
                        loadingConfig = false,
                        enabled = cfg?.enabled ?: false,
                        intervalMinutes = ((cfg?.intervalSec ?: 300) / 60).coerceAtLeast(1).toString(),
                        prompt = cfg?.prompt.orEmpty(),
                        providerName = cfg?.providerName.orEmpty(),
                        model = cfg?.model.orEmpty(),
                    )
                }
                .onFailure { _state.value = _state.value.copy(loadingConfig = false, message = "读取配置失败：${it.message}") }
        }
    }

    fun saveConfig() {
        val st = _state.value
        val agent = st.selectedAgent
        if (agent.isBlank()) { _state.value = st.copy(message = "请先选择 Agent。"); return }
        val minutes = st.intervalMinutes.toIntOrNull() ?: 5
        if (minutes < 5) { _state.value = st.copy(message = "间隔最小为 5 分钟。"); return }
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(saving = true)
            val cfg = HeartbeatConfig(
                enabled = st.enabled,
                intervalSec = minutes * 60,
                prompt = st.prompt.trim(),
                providerName = st.providerName.trim(),
                model = st.model.trim(),
            )
            runCatching { ws.setHeartbeat(s, agent, cfg) }
                .onSuccess { ok -> _state.value = _state.value.copy(saving = false, message = if (ok) "已保存配置" else "保存失败") }
                .onFailure { _state.value = _state.value.copy(saving = false, message = "保存失败：${it.message}") }
        }
    }

    /** Immediate enable/disable via heartbeat.toggle (optimistic). */
    fun toggleEnabled(enabled: Boolean) {
        val agent = _state.value.selectedAgent
        if (agent.isBlank()) { _state.value = _state.value.copy(message = "请先选择 Agent。"); return }
        _state.value = _state.value.copy(enabled = enabled, toggling = true)
        viewModelScope.launch {
            val s = settingsStore.current()
            runCatching { ws.toggleHeartbeat(s, agent, enabled) }
                .onSuccess { ok ->
                    _state.value = _state.value.copy(
                        toggling = false,
                        enabled = if (ok) enabled else !enabled,
                        message = if (ok) (if (enabled) "已启用" else "已禁用") else "操作失败",
                    )
                }
                .onFailure { _state.value = _state.value.copy(toggling = false, enabled = !enabled, message = "操作失败：${it.message}") }
        }
    }

    fun test() {
        val agent = _state.value.selectedAgent
        if (agent.isBlank()) { _state.value = _state.value.copy(message = "请先选择 Agent。"); return }
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(testing = true)
            runCatching { ws.testHeartbeat(s, agent) }
                .onSuccess { ok -> _state.value = _state.value.copy(testing = false, message = if (ok) "已触发一次心跳" else "触发失败") }
                .onFailure { _state.value = _state.value.copy(testing = false, message = "触发失败：${it.message}") }
            refreshLogs()
        }
    }

    fun loadChecklist() {
        val agent = _state.value.selectedAgent
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(loadingChecklist = true)
            runCatching { ws.getHeartbeatChecklist(s, agent) }
                .onSuccess { _state.value = _state.value.copy(loadingChecklist = false, checklist = it.orEmpty()) }
                .onFailure { _state.value = _state.value.copy(loadingChecklist = false, message = "读取 HEARTBEAT.md 失败：${it.message}") }
        }
    }

    fun saveChecklist() {
        val st = _state.value
        val agent = st.selectedAgent
        if (agent.isBlank()) { _state.value = st.copy(message = "请先选择 Agent。"); return }
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(savingChecklist = true)
            runCatching { ws.setHeartbeatChecklist(s, agent, st.checklist) }
                .onSuccess { ok -> _state.value = _state.value.copy(savingChecklist = false, message = if (ok) "已保存 HEARTBEAT.md" else "保存失败") }
                .onFailure { _state.value = _state.value.copy(savingChecklist = false, message = "保存失败：${it.message}") }
        }
    }

    fun refreshLogs() {
        val agent = _state.value.selectedAgent
        if (agent.isBlank()) return
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(loadingLogs = true)
            runCatching { ws.heartbeatLogs(s, agent) }
                .onSuccess { _state.value = _state.value.copy(loadingLogs = false, logs = it) }
                .onFailure { _state.value = _state.value.copy(loadingLogs = false, message = "读取日志失败：${it.message}") }
        }
    }

    fun refreshTargets() {
        val agent = _state.value.selectedAgent
        if (agent.isBlank()) return
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(loadingTargets = true)
            runCatching { ws.heartbeatTargets(s, agent) }
                .onSuccess { _state.value = _state.value.copy(loadingTargets = false, targets = it) }
                .onFailure { _state.value = _state.value.copy(loadingTargets = false, message = "读取投递目标失败：${it.message}") }
        }
    }
}
