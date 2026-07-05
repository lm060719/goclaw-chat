package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.remote.HeartbeatLog
import xyz.limo060719.goclaw.data.remote.HeartbeatTarget
import xyz.limo060719.goclaw.data.remote.dto.AgentInfo
import xyz.limo060719.goclaw.data.remote.dto.ProviderInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartbeatScreen(
    onBack: () -> Unit,
    vm: HeartbeatViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extras_heartbeat_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::loadAgents) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.heartbeat_refresh_agents))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AgentPicker(
                agents = state.agents,
                selected = state.selectedAgent,
                loading = state.loadingAgents,
                onSelect = vm::selectAgent,
            )

            if (!state.hasAgent) {
                Text(
                    stringResource(R.string.heartbeat_select_agent_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            ConfigCard(state = state, vm = vm)
            ChecklistCard(state = state, vm = vm)
            LogsCard(state = state, onRefresh = vm::refreshLogs)
            TargetsCard(state = state, onRefresh = vm::refreshTargets)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentPicker(
    agents: List<AgentInfo>,
    selected: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = agents.firstOrNull { it.resolvedKey == selected }?.display
        ?: selected.ifBlank { stringResource(R.string.heartbeat_select_agent) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Agent") },
            trailingIcon = {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            agents.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.display) },
                    onClick = { expanded = false; onSelect(agent.resolvedKey) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(
    providers: List<ProviderInfo>,
    selectedId: String?,
    currentName: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = providers.firstOrNull { it.resolvedId == selectedId }?.label
        ?: currentName.ifBlank { stringResource(R.string.heartbeat_select_provider) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.heartbeat_provider)) },
            trailingIcon = {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (providers.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.heartbeat_no_providers)) }, onClick = { expanded = false }, enabled = false)
            }
            providers.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = { expanded = false; onSelect(p.resolvedId) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    models: List<String>,
    selected: String,
    loading: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selected.ifBlank { stringResource(if (enabled) R.string.heartbeat_select_model else R.string.heartbeat_select_provider_first) },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.heartbeat_model)) },
            trailingIcon = {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            if (models.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.heartbeat_no_models)) }, onClick = { expanded = false }, enabled = false)
            }
            models.forEach { m ->
                DropdownMenuItem(text = { Text(m) }, onClick = { expanded = false; onSelect(m) })
            }
        }
    }
}

@Composable
private fun ConfigCard(state: HeartbeatUiState, vm: HeartbeatViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(R.string.heartbeat_config), loading = state.loadingConfig)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.heartbeat_enable), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (state.toggling) {
                    CircularProgressIndicator(Modifier.size(20.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Switch(checked = state.enabled, onCheckedChange = vm::toggleEnabled, enabled = !state.toggling)
            }
            OutlinedTextField(
                value = state.intervalMinutes,
                onValueChange = vm::onIntervalMinutes,
                label = { Text(stringResource(R.string.heartbeat_interval)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.prompt,
                onValueChange = vm::onPrompt,
                label = { Text(stringResource(R.string.heartbeat_prompt)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            ProviderDropdown(
                providers = state.providers,
                selectedId = state.selectedProviderId,
                currentName = state.providerName,
                loading = state.loadingProviders,
                onSelect = vm::selectProvider,
            )
            ModelDropdown(
                models = state.models,
                selected = state.model,
                loading = state.loadingModels,
                enabled = state.selectedProviderId != null,
                onSelect = vm::selectModel,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::test, enabled = !state.testing, modifier = Modifier.weight(1f)) {
                    if (state.testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.heartbeat_test_now))
                }
                Button(onClick = vm::saveConfig, enabled = !state.saving, modifier = Modifier.weight(1f)) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.heartbeat_save_config))
                }
            }
        }
    }
}

@Composable
private fun ChecklistCard(state: HeartbeatUiState, vm: HeartbeatViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("HEARTBEAT.md", loading = state.loadingChecklist)
            OutlinedTextField(
                value = state.checklist,
                onValueChange = vm::onChecklist,
                label = { Text(stringResource(R.string.heartbeat_context_content)) },
                minLines = 4,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::loadChecklist, enabled = !state.loadingChecklist, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.heartbeat_reload))
                }
                Button(onClick = vm::saveChecklist, enabled = !state.savingChecklist, modifier = Modifier.weight(1f)) {
                    if (state.savingChecklist) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}

@Composable
private fun LogsCard(state: HeartbeatUiState, onRefresh: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(R.string.heartbeat_logs), loading = state.loadingLogs, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh)) }
            }
            if (state.logs.isEmpty() && !state.loadingLogs) {
                Text(stringResource(R.string.heartbeat_no_logs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.logs.forEach { LogItem(it) }
            }
        }
    }
}

@Composable
private fun LogItem(log: HeartbeatLog) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (log.status.isNotBlank()) {
                Text(
                    log.status.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (log.status.startsWith("err", true) || log.status.startsWith("fail", true))
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(log.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (log.message.isNotBlank()) {
            Text(log.message, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TargetsCard(state: HeartbeatUiState, onRefresh: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(R.string.heartbeat_targets), loading = state.loadingTargets, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh)) }
            }
            if (state.targets.isEmpty() && !state.loadingTargets) {
                Text(stringResource(R.string.heartbeat_no_targets), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.targets.forEach { TargetItem(it) }
            }
        }
    }
}

@Composable
private fun TargetItem(t: HeartbeatTarget) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.channel.isNotBlank()) {
            Text(
                t.channel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            t.label.ifBlank { t.target },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SectionHeader(title: String, loading: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (loading) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}
