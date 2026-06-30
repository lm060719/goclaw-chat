package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.limo060719.goclaw.data.remote.TraceInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracesScreen(
    onBack: () -> Unit,
    vm: TracesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("执行轨迹") },
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
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.traces.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.traces.isEmpty() ->
                    Text("暂无轨迹", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.traces, key = { it.id }) { t -> TraceRow(t) { vm.openDetail(t.id) } }
                }
            }
        }
    }

    if (state.detailLoading || state.detail != null) {
        AlertDialog(
            onDismissRequest = vm::closeDetail,
            confirmButton = { TextButton(onClick = vm::closeDetail) { Text("关闭") } },
            title = { Text("轨迹详情") },
            text = {
                if (state.detailLoading) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                } else {
                    Text(
                        state.detail.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            },
        )
    }
}

@Composable
private fun TraceRow(t: TraceInfo, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.model.ifBlank { t.id },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (t.status.isNotBlank()) {
                    AssistChip(onClick = onClick, label = { Text(t.status) })
                }
            }
            Spacer(Modifier.height(4.dp))
            val meta = buildList {
                if (t.agent.isNotBlank()) add(t.agent)
                if (t.tokens > 0) add("${t.tokens} tok")
                if (t.costUsd > 0) add("$" + String.format("%.4f", t.costUsd))
                if (t.createdAt.isNotBlank()) add(t.createdAt)
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
        }
    }
}
