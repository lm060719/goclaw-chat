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
    @ApplicationContext private val context: Context,
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
                .onFailure { _state.value = _state.value.copy(loadingProviders = false, message = context.getString(R.string.provider_load_providers_failed_fmt, it.message ?: "")) }
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
                        message = if (it.isEmpty()) context.getString(R.string.provider_no_models) else null,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loadingModels = false, message = context.getString(R.string.provider_load_models_failed_fmt, it.message ?: "")) }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            val s = settingsStore.current()
            if (!s.isConfigured) {
                _state.value = _state.value.copy(message = context.getString(R.string.err_not_configured))
                return@launch
            }
            _state.value = _state.value.copy(loadingAgents = true)
            // WS agents.list returns all agents; REST /v1/agents is a filtered subset → fallback only.
            runCatching { ws.listAgents(s).ifEmpty { api.agents(s).getOrElse { emptyList() } } }
                .onSuccess { list ->
                    _state.value = _state.value.copy(
                        agents = list, loadingAgents = false,
                        message = if (list.isEmpty()) context.getString(R.string.provider_no_agents) else null,
                    )
                    // Auto-select the agent from settings, else the first one.
                    val preferred = s.agent.ifBlank { list.firstOrNull()?.resolvedKey.orEmpty() }
                    if (preferred.isNotBlank() && _state.value.selectedAgent.isBlank()) selectAgent(preferred)
                }
                .onFailure { _state.value = _state.value.copy(loadingAgents = false, message = context.getString(R.string.heartbeat_load_agents_failed_fmt, it.message ?: "")) }
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
                .onFailure { _state.value = _state.value.copy(loadingConfig = false, message = context.getString(R.string.heartbeat_load_config_failed_fmt, it.message ?: "")) }
        }
    }

    fun saveConfig() {
        val st = _state.value
        val agent = st.selectedAgent
        if (agent.isBlank()) { _state.value = st.copy(message = context.getString(R.string.heartbeat_select_agent_msg)); return }
        val minutes = st.intervalMinutes.toIntOrNull() ?: 5
        if (minutes < 5) { _state.value = st.copy(message = context.getString(R.string.heartbeat_interval_min)); return }
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
                .onSuccess { ok -> _state.value = _state.value.copy(saving = false, message = if (ok) context.getString(R.string.heartbeat_config_saved) else context.getString(R.string.heartbeat_save_failed)) }
                .onFailure { _state.value = _state.value.copy(saving = false, message = context.getString(R.string.heartbeat_save_failed_fmt, it.message ?: "")) }
        }
    }

    /** Immediate enable/disable via heartbeat.toggle (optimistic). */
    fun toggleEnabled(enabled: Boolean) {
        val agent = _state.value.selectedAgent
        if (agent.isBlank()) { _state.value = _state.value.copy(message = context.getString(R.string.heartbeat_select_agent_msg)); return }
        _state.value = _state.value.copy(enabled = enabled, toggling = true)
        viewModelScope.launch {
            val s = settingsStore.current()
            runCatching { ws.toggleHeartbeat(s, agent, enabled) }
                .onSuccess { ok ->
                    _state.value = _state.value.copy(
                        toggling = false,
                        enabled = if (ok) enabled else !enabled,
                        message = if (ok) (if (enabled) context.getString(R.string.heartbeat_enabled) else context.getString(R.string.heartbeat_disabled)) else context.getString(R.string.err_op_failed),
                    )
                }
                .onFailure { _state.value = _state.value.copy(toggling = false, enabled = !enabled, message = context.getString(R.string.err_op_failed_fmt, it.message ?: "")) }
        }
    }

    fun test() {
        val agent = _state.value.selectedAgent
        if (agent.isBlank()) { _state.value = _state.value.copy(message = context.getString(R.string.heartbeat_select_agent_msg)); return }
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(testing = true)
            runCatching { ws.testHeartbeat(s, agent) }
                .onSuccess { ok -> _state.value = _state.value.copy(testing = false, message = if (ok) context.getString(R.string.heartbeat_triggered) else context.getString(R.string.heartbeat_trigger_failed)) }
                .onFailure { _state.value = _state.value.copy(testing = false, message = context.getString(R.string.heartbeat_trigger_failed_fmt, it.message ?: "")) }
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
                .onFailure { _state.value = _state.value.copy(loadingChecklist = false, message = context.getString(R.string.heartbeat_read_checklist_failed_fmt, it.message ?: "")) }
        }
    }

    fun saveChecklist() {
        val st = _state.value
        val agent = st.selectedAgent
        if (agent.isBlank()) { _state.value = st.copy(message = context.getString(R.string.heartbeat_select_agent_msg)); return }
        viewModelScope.launch {
            val s = settingsStore.current()
            _state.value = _state.value.copy(savingChecklist = true)
            runCatching { ws.setHeartbeatChecklist(s, agent, st.checklist) }
                .onSuccess { ok -> _state.value = _state.value.copy(savingChecklist = false, message = if (ok) context.getString(R.string.heartbeat_checklist_saved) else context.getString(R.string.heartbeat_save_failed)) }
                .onFailure { _state.value = _state.value.copy(savingChecklist = false, message = context.getString(R.string.heartbeat_save_failed_fmt, it.message ?: "")) }
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
                .onFailure { _state.value = _state.value.copy(loadingLogs = false, message = context.getString(R.string.heartbeat_read_logs_failed_fmt, it.message ?: "")) }
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
                .onFailure { _state.value = _state.value.copy(loadingTargets = false, message = context.getString(R.string.heartbeat_read_targets_failed_fmt, it.message ?: "")) }
        }
    }
}
