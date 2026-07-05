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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.limo060719.goclaw.R

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
                title = { Text(stringResource(R.string.extras_usage_title)) },
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
            val u = state.usage
            when {
                state.loading && u == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (u != null) {
                        if (u.period.isNotBlank()) {
                            Text(stringResource(R.string.usage_period_fmt, u.period), style = MaterialTheme.typography.labelLarge)
                        }
                        StatCard(stringResource(R.string.usage_total_tokens), u.totalTokens.toString())
                        StatCard(stringResource(R.string.usage_input_tokens), u.promptTokens.toString())
                        StatCard(stringResource(R.string.usage_output_tokens), u.completionTokens.toString())
                        StatCard(stringResource(R.string.usage_requests), u.requests.toString())
                        StatCard(stringResource(R.string.usage_cost_usd), "$" + String.format("%.4f", u.costUsd))
                        if (u.llmCalls > 0) StatCard(stringResource(R.string.usage_llm_calls), u.llmCalls.toString())
                        if (u.toolCalls > 0) StatCard(stringResource(R.string.usage_tool_calls), u.toolCalls.toString())
                        if (u.uniqueUsers > 0) StatCard(stringResource(R.string.usage_unique_users), u.uniqueUsers.toString())
                        if (u.errors > 0) StatCard(stringResource(R.string.usage_errors), u.errors.toString())
                    } else {
                        Text(stringResource(R.string.usage_empty), style = MaterialTheme.typography.bodyMedium)
                    }

                    val zeros = u == null || (u.totalTokens == 0L && u.costUsd == 0.0)
                    if (zeros && state.raw != null) {
                        HorizontalDivider()
                        Text(stringResource(R.string.usage_raw_title), style = MaterialTheme.typography.labelLarge)
                        Text(
                            stringResource(R.string.usage_raw_desc),
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
