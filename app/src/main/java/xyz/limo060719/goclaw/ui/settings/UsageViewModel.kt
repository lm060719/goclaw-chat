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
import xyz.limo060719.goclaw.data.remote.UsageBreakdownRow
import xyz.limo060719.goclaw.data.remote.UsagePoint
import xyz.limo060719.goclaw.data.remote.UsageSummary
import javax.inject.Inject

data class UsageUiState(
    val usage: UsageSummary? = null,
    /** Usage over time; empty when the gateway build has no timeseries endpoint. */
    val timeseries: List<UsagePoint> = emptyList(),
    /** Usage grouped by model/agent; empty when unavailable. */
    val breakdown: List<UsageBreakdownRow> = emptyList(),
    /** Diagnostic shown when no chart renders: the raw timeseries/breakdown bodies or HTTP errors. */
    val trendDebug: String? = null,
    val raw: String? = null,
    val loading: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class UsageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val api: GoClawApi,
) : ViewModel() {

    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

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
            val raw = api.usageRaw(s).getOrNull()
            // Trend + breakdown are best-effort: a build without those endpoints just shows no chart.
            val tsResult = api.usageTimeseries(s)
            val bdResult = api.usageBreakdown(s)
            val timeseries = tsResult.getOrDefault(emptyList())
            val breakdown = bdResult.getOrDefault(emptyList())
            // Only surface the raw diagnostic when a trend fetch actually FAILED (HTTP/parse error).
            // A successful-but-empty window is just "no usage", not a problem to debug.
            val trendDebug = if (tsResult.isFailure || bdResult.isFailure) buildString {
                append("timeseries → ")
                append(api.usageTimeseriesRaw(s).fold({ it.take(700) }, { "ERROR: ${it.message}" }))
                append("\n\nbreakdown → ")
                append(api.usageBreakdownRaw(s).fold({ it.take(700) }, { "ERROR: ${it.message}" }))
            } else null
            api.usageSummary(s)
                .onSuccess { _state.value = _state.value.copy(usage = it, timeseries = timeseries, breakdown = breakdown, trendDebug = trendDebug, raw = raw, loading = false) }
                .onFailure { _state.value = _state.value.copy(timeseries = timeseries, breakdown = breakdown, trendDebug = trendDebug, raw = raw, loading = false, message = context.getString(R.string.err_load_failed, it.message ?: "")) }
        }
    }
}
