package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.remote.DevicePairing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePairingScreen(
    onBack: () -> Unit,
    vm: DevicePairingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showRequest by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    if (showRequest) {
        RequestPairingDialog(
            requesting = state.requesting,
            onDismiss = { showRequest = false },
            onConfirm = { channel, chatId -> vm.requestPairing(channel, chatId) },
        )
    }
    state.requestedCode?.let { code ->
        CodeResultDialog(code = code, onDismiss = { vm.clearRequestedCode(); showRequest = false })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extras_pairing_title)) },
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
                onClick = { showRequest = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.pairing_request_code)) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.pairings.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.pairings.isEmpty() ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.pairing_empty), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.pairing_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.pairings, key = { it.stableKey }) { item ->
                        PairingCard(
                            item = item,
                            busy = state.busyKey == item.stableKey,
                            enabled = state.busyKey == null,
                            onApprove = { vm.approve(item) },
                            onDeny = { vm.deny(item) },
                            onRevoke = { vm.revoke(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingCard(
    item: DevicePairing,
    busy: Boolean,
    enabled: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onRevoke: () -> Unit,
) {
    // Pending pairings are approved/denied by code; approved ones are revoked by channel/senderId.
    val approved = item.isApproved || (item.senderId.isNotBlank() && !item.isPending && item.code.isBlank())
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.channel.isNotBlank()) {
                    Text(
                        item.channel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                }
                StatusChip(approved)
            }
            if (item.code.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    item.code,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item.chatId.takeIf { it.isNotBlank() }?.let { KeyValue("Chat ID", it) }
            item.senderId.takeIf { it.isNotBlank() }?.let { KeyValue("Sender", it) }
            item.approvedBy.takeIf { it.isNotBlank() }?.let { KeyValue(stringResource(R.string.pairing_approved_by), it) }
            item.createdAt.takeIf { it.isNotBlank() }?.let { KeyValue(stringResource(R.string.pairing_created_at), it) }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.weight(1f))
                }
                if (approved) {
                    OutlinedButton(onClick = onRevoke, enabled = enabled) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_revoke))
                    }
                } else {
                    OutlinedButton(onClick = onDeny, enabled = enabled) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_reject))
                    }
                    Button(onClick = onApprove, enabled = enabled) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_approve))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(approved: Boolean) {
    val label = if (approved) stringResource(R.string.pairing_status_approved) else stringResource(R.string.pairing_status_pending)
    val color = if (approved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Spacer(Modifier.height(4.dp))
    Row {
        Text(
            "$key：",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RequestPairingDialog(
    requesting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (channel: String, chatId: String) -> Unit,
) {
    var channel by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!requesting) onDismiss() },
        title = { Text(stringResource(R.string.pairing_request_code)) },
        text = {
            Column {
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it },
                    label = { Text(stringResource(R.string.pairing_channel)) },
                    placeholder = { Text(stringResource(R.string.pairing_channel_hint)) },
                    singleLine = true,
                    enabled = !requesting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("Chat ID") },
                    singleLine = true,
                    enabled = !requesting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(channel, chatId) },
                enabled = !requesting && channel.isNotBlank() && chatId.isNotBlank(),
            ) {
                if (requesting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.pairing_request))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !requesting) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun CodeResultDialog(code: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pairing_code_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pairing_code_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
    )
}
