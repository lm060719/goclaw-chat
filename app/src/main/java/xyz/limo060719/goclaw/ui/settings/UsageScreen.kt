package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    onBack: () -> Unit,
    vm: UsageViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("用量 & 费用") },
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
            val u = state.usage
            when {
                state.loading && u == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (u != null) {
                        if (u.period.isNotBlank()) {
                            Text("周期：${u.period}", style = MaterialTheme.typography.labelLarge)
                        }
                        StatCard("总 Token", u.totalTokens.toString())
                        StatCard("输入 Token", u.promptTokens.toString())
                        StatCard("输出 Token", u.completionTokens.toString())
                        StatCard("请求数", u.requests.toString())
                        StatCard("费用 (USD)", "$" + String.format("%.4f", u.costUsd))
                        if (u.llmCalls > 0) StatCard("LLM 调用", u.llmCalls.toString())
                        if (u.toolCalls > 0) StatCard("工具调用", u.toolCalls.toString())
                        if (u.uniqueUsers > 0) StatCard("独立用户", u.uniqueUsers.toString())
                        if (u.errors > 0) StatCard("错误数", u.errors.toString())
                    } else {
                        Text("暂无用量数据", style = MaterialTheme.typography.bodyMedium)
                    }

                    val zeros = u == null || (u.totalTokens == 0L && u.costUsd == 0.0)
                    if (zeros && state.raw != null) {
                        HorizontalDivider()
                        Text("原始返回（调试 · 字段对齐用）", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "数值为 0 多半是字段名不同。把下面这段发给开发者即可精确对齐：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    state.raw.orEmpty(),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .heightIn(max = 360.dp)
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                        .padding(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
