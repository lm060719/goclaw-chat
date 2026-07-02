package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.limo060719.goclaw.data.remote.ApiKeyInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(
    onBack: () -> Unit,
    vm: ApiKeysViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var viewingSecret by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    if (showCreate) {
        CreateKeyDialog(
            creating = state.creating,
            onDismiss = { showCreate = false },
            onConfirm = { name, scopes, expires -> vm.create(name, scopes, expires) },
        )
    }
    state.createdSecret?.let { secret ->
        SecretDialog(
            secret = secret,
            title = "请立即保存此 Key",
            note = "已自动保存到本机，之后可在列表点「查看密钥」再次查看/复制。",
            noteError = true,
            onDismiss = { vm.clearCreatedSecret(); showCreate = false },
        )
    }
    viewingSecret?.let { secret ->
        SecretDialog(
            secret = secret,
            title = "密钥",
            note = "此密钥保存在本机，请妥善保管。",
            noteError = false,
            onDismiss = { viewingSecret = null },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("API Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("创建 Key") },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.keys.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.keys.isEmpty() ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("暂无 API Key", style = MaterialTheme.typography.bodyMedium)
                    }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.keys, key = { it.id }) { item ->
                        val secret = state.savedSecrets[item.id]
                        ApiKeyCard(
                            item = item,
                            savedSecret = secret,
                            busy = state.revokingId == item.id,
                            enabled = state.revokingId == null,
                            onRevoke = { vm.revoke(item.id) },
                            onViewSecret = { secret?.let { viewingSecret = it } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiKeyCard(
    item: ApiKeyInfo,
    savedSecret: String?,
    busy: Boolean,
    enabled: Boolean,
    onRevoke: () -> Unit,
    onViewSecret: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name.ifBlank { item.id },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (item.revoked) {
                    AssistChip(onClick = {}, enabled = false, label = { Text("已撤销") })
                }
            }
            if (item.prefix.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${item.prefix}…",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.scopes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "scopes: ${item.scopes.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.expiresAt.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text("到期：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.createdAt.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text("创建于：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (savedSecret != null) {
                Spacer(Modifier.height(4.dp))
                Text("🔒 密钥已存于本机", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!item.revoked || savedSecret != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.weight(1f))
                    }
                    if (savedSecret != null) {
                        OutlinedButton(onClick = onViewSecret, enabled = enabled) {
                            Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("查看密钥")
                        }
                    }
                    if (!item.revoked) {
                        OutlinedButton(onClick = onRevoke, enabled = enabled) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("撤销")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Predefined permission scopes (wire code → 中文说明). Scopes are `operator.*`-prefixed —
 * confirmed from live api_keys.list (operator.admin/approvals/pairing); bare names are rejected
 * with "invalid scope". read/write/provision follow the same prefix convention.
 */
private val SCOPES = listOf(
    "operator.admin" to "完全管理员权限",
    "operator.read" to "只读权限",
    "operator.write" to "读写权限",
    "operator.approvals" to "管理执行审批",
    "operator.pairing" to "管理设备配对",
    "operator.provision" to "开通新租户",
)

/** Expiry presets in days; null = 自定义(需手动输入). */
private val EXPIRY_PRESETS = listOf(7 to "7 天", 30 to "30 天", 90 to "90 天", null to "自定义")

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CreateKeyDialog(
    creating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, scopes: List<String>, expiresInDays: Int?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val selectedScopes = remember { mutableStateListOf<String>() }
    var expiryPreset by remember { mutableStateOf<Int?>(30) }
    var isCustom by remember { mutableStateOf(false) }
    var customDays by remember { mutableStateOf("") }

    val effectiveDays = if (isCustom) customDays.toIntOrNull() else expiryPreset

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("创建 API Key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true, enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("权限范围（可多选）", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SCOPES.forEach { (code, label) ->
                        val selected = code in selectedScopes
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (selected) selectedScopes.remove(code) else selectedScopes.add(code)
                            },
                            label = { Text(label) },
                            enabled = !creating,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("有效期", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXPIRY_PRESETS.forEach { (days, label) ->
                        val selected = if (days == null) isCustom else (!isCustom && expiryPreset == days)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (days == null) { isCustom = true } else { isCustom = false; expiryPreset = days }
                            },
                            label = { Text(label) },
                            enabled = !creating,
                        )
                    }
                }
                if (isCustom) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customDays,
                        onValueChange = { customDays = it.filter(Char::isDigit) },
                        label = { Text("自定义天数") },
                        singleLine = true, enabled = !creating,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, selectedScopes.toList(), effectiveDays) },
                enabled = !creating && name.isNotBlank() && selectedScopes.isNotEmpty() &&
                    (!isCustom || customDays.toIntOrNull() != null),
            ) {
                if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("创建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") } },
    )
}

@Composable
private fun SecretDialog(
    secret: String,
    title: String,
    note: String,
    noteError: Boolean,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (noteError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        secret,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(secret)) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制")
            }
        },
    )
}
