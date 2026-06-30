package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("AI 供应商") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                label = { Text("后端地址") },
                placeholder = { Text("https://your-goclaw-backend") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = vm::onApiKey,
                label = { Text("API 密钥") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.userId,
                onValueChange = vm::onUserId,
                label = { Text("用户 ID") },
                placeholder = { Text("X-GoClaw-User-Id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.agent,
                onValueChange = vm::onAgent,
                label = { Text("Agent 标识") },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = vm::saveCurrentAgent, enabled = state.agent.isNotBlank()) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("保存")
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
            OutlinedTextField(
                value = state.model,
                onValueChange = vm::onModel,
                label = { Text("模型(可选)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(onClick = vm::fetchAgents, enabled = !state.loadingAgents) {
                if (state.loadingAgents) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("从后端加载 Agent")
            }

            if (state.agents.isNotEmpty()) {
                Text("点击选择:", style = MaterialTheme.typography.labelLarge)
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

            Spacer(Modifier.height(4.dp))
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("保存") }
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
            "已保存的 Agent（点击切换）",
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
                            contentDescription = "移除",
                            modifier = Modifier.size(16.dp).clickable { onRemove(key) },
                        )
                    },
                )
            }
        }
    }
}
