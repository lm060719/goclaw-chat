package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.remote.SessionSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onBack: () -> Unit,
    vm: SessionsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extras_sessions_title)) },
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
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.sessions.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.sessions.isEmpty() ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.sessions_empty), style = MaterialTheme.typography.bodyMedium)
                    }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.sessions, key = { it.key }) { s ->
                        SessionCard(
                            session = s,
                            busy = state.busyKey == s.key,
                            enabled = state.busyKey == null,
                            onCompact = { vm.compact(s.key) },
                            onReset = { vm.reset(s.key) },
                            onDelete = { vm.delete(s.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionSummary,
    busy: Boolean,
    enabled: Boolean,
    onCompact: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                session.title.ifBlank { session.key },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val meta = buildList {
                if (session.agent.isNotBlank()) add(session.agent)
                if (session.messageCount > 0) add(stringResource(R.string.sessions_msg_count_fmt, session.messageCount))
                if (session.updated.isNotBlank()) add(session.updated)
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onCompact, enabled = enabled) {
                    Icon(Icons.Filled.Compress, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.sessions_compact))
                }
                TextButton(onClick = onReset, enabled = enabled) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.sessions_clear))
                }
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.common_delete))
                }
            }
        }
    }
}
