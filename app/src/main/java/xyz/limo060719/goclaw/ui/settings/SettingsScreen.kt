package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.limo060719.goclaw.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Model/provider config is hidden by default — the model is already configured on the backend.
    var showProviderConfig by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_ai_provider)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = vm::onBaseUrl,
                label = { Text(stringResource(R.string.provider_backend_url)) },
                placeholder = { Text("https://your-goclaw-backend") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = vm::onApiKey,
                label = { Text(stringResource(R.string.provider_api_key)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.userId,
                onValueChange = vm::onUserId,
                label = { Text(stringResource(R.string.provider_user_id)) },
                placeholder = { Text("X-GoClaw-User-Id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.agent,
                onValueChange = vm::onAgent,
                label = { Text(stringResource(R.string.provider_agent_key)) },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = vm::saveCurrentAgent, enabled = state.agent.isNotBlank()) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.common_save))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.savedAgents.isNotEmpty()) {
                SavedAgentChips(
                    saved = state.savedAgents,
                    active = state.agent,
                    onSelect = vm::selectSavedAgent,
                    onRemove = vm::removeSavedAgent,
                )
            }
            // Model is already configured on the backend; the field only shows when modifying.
            if (showProviderConfig) {
                OutlinedTextField(
                    value = state.model,
                    onValueChange = vm::onModel,
                    label = { Text(stringResource(R.string.provider_model_required)) },
                    isError = state.model.isBlank(),
                    supportingText = if (state.model.isBlank()) {
                        { Text(stringResource(R.string.provider_model_required_hint)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::testConnection, enabled = !state.testingConnection) {
                    if (state.testingConnection) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.provider_test_connection))
                }
                state.gatewayOnline?.let { online ->
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (online) Color(0xFF22C55E) else MaterialTheme.colorScheme.error)
                    )
                    Text(
                        stringResource(if (online) R.string.provider_online else R.string.provider_offline),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(onClick = vm::fetchAgents, enabled = !state.loadingAgents) {
                if (state.loadingAgents) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.provider_load_agents))
            }

            if (state.agents.isNotEmpty()) {
                Text(stringResource(R.string.provider_tap_select), style = MaterialTheme.typography.labelLarge)
                state.agents.forEach { a ->
                    val selected = a.resolvedKey == state.agent
                    ElevatedCard(
                        onClick = { vm.onAgent(a.resolvedKey) },
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(a.display, style = MaterialTheme.typography.titleSmall)
                            a.summary?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Text(a.resolvedKey, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (showProviderConfig) {
                ModelConfigSection(
                    state = state,
                    onLoadProviders = vm::fetchProviders,
                    onSelectProvider = vm::selectProvider,
                    onSelectModel = vm::selectModel,
                    onApply = vm::applyModelToAgent,
                )
            } else {
                OutlinedButton(
                    onClick = { showProviderConfig = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.provider_edit_provider))
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_save)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ModelConfigSection(
    state: SettingsUiState,
    onLoadProviders: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onApply: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.provider_model_config_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.provider_model_config_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(onClick = onLoadProviders, enabled = !state.loadingProviders) {
            if (state.loadingProviders) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.provider_load_providers))
        }

        if (state.providers.isNotEmpty()) {
            Text(stringResource(R.string.provider_providers_label), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.providers.forEach { p ->
                    FilterChip(
                        selected = p.resolvedId == state.selectedProviderId,
                        onClick = { onSelectProvider(p.resolvedId) },
                        label = { Text(p.label) },
                    )
                }
            }
        }

        if (state.loadingModels) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.provider_loading_models), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.models.isNotEmpty()) {
            Text(stringResource(R.string.provider_models_label), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.models.forEach { m ->
                    FilterChip(
                        selected = m == state.model,
                        onClick = { onSelectModel(m) },
                        label = { Text(m) },
                    )
                }
            }
            Button(
                onClick = onApply,
                enabled = !state.applyingModel && state.model.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.applyingModel) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.provider_apply_model))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SavedAgentChips(
    saved: List<String>,
    active: String,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.provider_saved_agents),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            saved.forEach { key ->
                InputChip(
                    selected = key == active,
                    onClick = { onSelect(key) },
                    label = { Text(key) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.common_remove),
                            modifier = Modifier.size(16.dp).clickable { onRemove(key) },
                        )
                    },
                )
            }
        }
    }
}
