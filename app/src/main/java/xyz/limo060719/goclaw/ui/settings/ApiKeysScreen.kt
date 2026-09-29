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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.limo060719.goclaw.R
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
            title = stringResource(R.string.apikeys_save_now_title),
            note = stringResource(R.string.apikeys_save_now_note),
            noteError = true,
            onDismiss = { vm.clearCreatedSecret(); showCreate = false },
        )
    }
    viewingSecret?.let { secret ->
        SecretDialog(
            secret = secret,
            title = stringResource(R.string.apikeys_key_title),
            note = stringResource(R.string.apikeys_key_note),
            noteError = false,
            onDismiss = { viewingSecret = null },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extras_api_keys_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.apikeys_create_key)) },
            )
        },
    ) { padding ->
        RefreshableBox(
            refreshing = state.loading && state.keys.isNotEmpty(),
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                state.loading && state.keys.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.keys.isEmpty() ->
                    ScrollableCenter {
                        Icon(
                            Icons.Filled.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.apikeys_empty), style = MaterialTheme.typography.bodyMedium)
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
                    AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.common_revoked)) })
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
                Text(stringResource(R.string.apikeys_expires_fmt, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.createdAt.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.apikeys_created_fmt, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (savedSecret != null) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.apikeys_stored_locally), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                            Text(stringResource(R.string.apikeys_view_key))
                        }
                    }
                    if (!item.revoked) {
                        OutlinedButton(onClick = onRevoke, enabled = enabled) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.common_revoke))
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
    "operator.admin" to R.string.apikeys_scope_admin,
    "operator.read" to R.string.apikeys_scope_read,
    "operator.write" to R.string.apikeys_scope_write,
    "operator.approvals" to R.string.apikeys_scope_approvals,
    "operator.pairing" to R.string.apikeys_scope_pairing,
    "operator.provision" to R.string.apikeys_scope_provision,
)

/** Expiry presets in days; null = custom (manual input). */
private val EXPIRY_PRESETS = listOf<Int?>(7, 30, 90, null)

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
        title = { Text(stringResource(R.string.apikeys_create_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.apikeys_name)) }, singleLine = true, enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.apikeys_scopes), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SCOPES.forEach { (code, labelRes) ->
                        val selected = code in selectedScopes
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (selected) selectedScopes.remove(code) else selectedScopes.add(code)
                            },
                            label = { Text(stringResource(labelRes)) },
                            enabled = !creating,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.apikeys_validity), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXPIRY_PRESETS.forEach { days ->
                        val selected = if (days == null) isCustom else (!isCustom && expiryPreset == days)
                        val label = if (days == null) stringResource(R.string.apikeys_custom)
                            else stringResource(R.string.apikeys_days_fmt, days)
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
                        label = { Text(stringResource(R.string.apikeys_custom_days)) },
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
                if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.apikeys_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text(stringResource(R.string.common_cancel)) } },
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
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(secret)) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.common_copy))
            }
        },
    )
}
