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
import xyz.limo060719.goclaw.data.remote.dto.AgentInfo
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
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val api: GoClawApi,
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
            api.agents(s)
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

    fun save() {
        val st = _state.value
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
