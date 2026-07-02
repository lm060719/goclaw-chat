package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.data.remote.LogLine

private val LEVELS = listOf("" to "全部", "debug" to "DEBUG", "info" to "INFO", "warn" to "WARN", "error" to "ERROR")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    vm: LogsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    fun copy(text: String) {
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbar.showSnackbar("已复制") }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    // Follow the tail as new lines arrive.
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("实时日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { copy(state.lines.joinToString("\n") { formatLine(it) }) },
                        enabled = state.lines.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制全部")
                    }
                    IconButton(onClick = vm::clearLines, enabled = state.lines.isNotEmpty()) {
                        Icon(Icons.Filled.ClearAll, contentDescription = "清空")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::toggle,
                icon = {
                    Icon(
                        if (state.streaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                },
                text = { Text(if (state.streaming) "停止" else "开始") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LevelFilterRow(
                selected = state.level,
                onSelect = vm::setLevel,
            )
            HorizontalDivider()
            Box(Modifier.fillMaxSize()) {
                if (state.lines.isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Subject,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (state.streaming) "正在等待日志…" else "点击「开始」拉取实时日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(state.lines) { line -> LogRow(line, onCopy = { copy(formatLine(line)) }) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelFilterRow(selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LEVELS.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/** One log line rendered as plain text (used for copy-all and per-row copy). */
private fun formatLine(line: LogLine): String = buildString {
    if (line.timestamp.isNotBlank()) append(line.timestamp).append(' ')
    if (line.level.isNotBlank()) append(line.level.uppercase()).append(' ')
    if (line.source.isNotBlank()) append('[').append(line.source).append("] ")
    append(line.message)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(line: LogLine, onCopy: () -> Unit) {
    val color = levelColor(line.level)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onCopy),
    ) {
        if (line.timestamp.isNotBlank()) {
            Text(
                line.timestamp,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        if (line.level.isNotBlank()) {
            Text(
                line.level.uppercase().take(5).padEnd(5),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Text(
            buildString {
                if (line.source.isNotBlank()) append("[${line.source}] ")
                append(line.message)
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun levelColor(level: String): Color = when {
    level.startsWith("err", ignoreCase = true) || level.startsWith("fatal", ignoreCase = true) ->
        MaterialTheme.colorScheme.error
    level.startsWith("warn", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
    level.startsWith("info", ignoreCase = true) -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
