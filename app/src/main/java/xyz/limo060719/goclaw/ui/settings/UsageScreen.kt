package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.remote.UsageBreakdownRow
import xyz.limo060719.goclaw.data.remote.UsagePoint

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

                    if (state.timeseries.any { it.tokens > 0 }) {
                        UsageTrendChart(state.timeseries)
                    }
                    if (state.breakdown.isNotEmpty()) {
                        UsageBreakdownSection(state.breakdown)
                    }

                    state.trendDebug?.let { dbg ->
                        HorizontalDivider()
                        Text(stringResource(R.string.usage_trend_debug_title), style = MaterialTheme.typography.labelLarge)
                        Text(
                            stringResource(R.string.usage_trend_debug_desc),
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
                                    dbg,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .heightIn(max = 300.dp)
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                        .padding(10.dp),
                                )
                            }
                        }
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

/** Hand-drawn token-per-bucket bar chart (no chart library on the classpath). */
@Composable
private fun UsageTrendChart(points: List<UsagePoint>) {
    val barColor = MaterialTheme.colorScheme.primary
    val maxTokens = remember(points) { points.maxOfOrNull { it.tokens }?.coerceAtLeast(1L) ?: 1L }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.usage_trend_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.usage_trend_peak_fmt, maxTokens.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val slot = size.width / points.size
                val barW = (slot * 0.6f).coerceAtMost(48f)
                points.forEachIndexed { i, p ->
                    val h = (p.tokens.toFloat() / maxTokens) * size.height
                    val left = i * slot + (slot - barW) / 2f
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(left, size.height - h),
                        size = Size(barW, h.coerceAtLeast(1f)),
                        cornerRadius = CornerRadius(barW * 0.2f, barW * 0.2f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val labelStyle = MaterialTheme.typography.labelSmall
                val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                Text(points.first().label.take(16), style = labelStyle, color = labelColor)
                if (points.size > 1) {
                    Text(points.last().label.take(16), style = labelStyle, color = labelColor)
                }
            }
        }
    }
}

/** Proportional horizontal bars per model / agent, sorted by token usage. */
@Composable
private fun UsageBreakdownSection(rows: List<UsageBreakdownRow>) {
    val maxTokens = remember(rows) { rows.maxOfOrNull { it.tokens }?.coerceAtLeast(1L) ?: 1L }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.usage_breakdown_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            rows.take(8).forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        r.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(r.tokens.toString(), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height(6.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    val fraction = (r.tokens.toFloat() / maxTokens).coerceIn(0.02f, 1f)
                    Box(
                        Modifier.fillMaxWidth(fraction).height(6.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
