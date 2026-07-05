package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.remote.TraceDetail
import xyz.limo060719.goclaw.data.remote.TraceInfo
import xyz.limo060719.goclaw.data.remote.TraceStep
import xyz.limo060719.goclaw.data.remote.TraceStepKind

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
                title = { Text(stringResource(R.string.extras_traces_title)) },
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
                state.loading && state.traces.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.traces.isEmpty() ->
                    Text(stringResource(R.string.traces_empty), Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)

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
        var showRaw by remember(state.detail) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = vm::closeDetail,
            confirmButton = { TextButton(onClick = vm::closeDetail) { Text(stringResource(R.string.common_close)) } },
            dismissButton = {
                if (state.detail != null) {
                    TextButton(onClick = { showRaw = !showRaw }) {
                        Text(stringResource(if (showRaw) R.string.traces_view_visual else R.string.traces_view_raw))
                    }
                }
            },
            title = { Text(stringResource(R.string.traces_detail_title)) },
            text = {
                val detail = state.detail
                when {
                    state.detailLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                    detail == null -> {}
                    showRaw -> Text(
                        detail.raw,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                    else -> TraceDetailView(detail)
                }
            },
        )
    }
}

@Composable
private fun TraceDetailView(detail: TraceDetail) {
    Column(
        Modifier
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (detail.meta.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    detail.meta.forEach { (label, value) ->
                        Row {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(56.dp),
                            )
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (detail.steps.isEmpty()) {
            Text(
                stringResource(R.string.traces_no_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            detail.steps.forEachIndexed { i, step -> TraceStepCard(step, expandedDefault = i == 0) }
        }
    }
}

@Composable
private fun TraceStepCard(step: TraceStep, expandedDefault: Boolean) {
    var expanded by remember { mutableStateOf(expandedDefault) }
    val accent = step.kind.accent()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (step.subtitle.isNotBlank()) {
                        Text(
                            step.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (step.body.isNotBlank()) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded && step.body.isNotBlank()) {
                Text(
                    step.body,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 27.dp, end = 10.dp, bottom = 10.dp)
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun TraceStepKind.accent(): Color = when (this) {
    TraceStepKind.USER -> Color(0xFF3B82F6)
    TraceStepKind.ASSISTANT -> Color(0xFF10B981)
    TraceStepKind.SYSTEM -> Color(0xFF6B7280)
    TraceStepKind.THINKING -> Color(0xFF8B5CF6)
    TraceStepKind.TOOL_CALL -> Color(0xFFF59E0B)
    TraceStepKind.TOOL_RESULT -> Color(0xFFEAB308)
    TraceStepKind.LLM -> Color(0xFF14B8A6)
    TraceStepKind.EVENT -> Color(0xFF64748B)
    TraceStepKind.OTHER -> Color(0xFF9CA3AF)
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
