package xyz.limo060719.goclaw.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.limo060719.goclaw.R

private data class Suggestion(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val prompt: String,
)

/** 无消息时的欢迎页 + 提示词建议卡片。 */
@Composable
internal fun EmptyState(
    modifier: Modifier = Modifier,
    configured: Boolean = true,
    onConfigure: () -> Unit = {},
    onSuggestion: (String) -> Unit,
) {
    val suggestions = listOf(
        Suggestion(stringResource(R.string.empty_sugg_copywriting_title), stringResource(R.string.empty_sugg_copywriting_desc), Icons.Filled.EditNote, stringResource(R.string.empty_sugg_copywriting_prompt)),
        Suggestion(stringResource(R.string.empty_sugg_summary_title), stringResource(R.string.empty_sugg_summary_desc), Icons.Filled.Description, stringResource(R.string.empty_sugg_summary_prompt)),
        Suggestion(stringResource(R.string.empty_sugg_code_title), stringResource(R.string.empty_sugg_code_desc), Icons.Filled.Code, stringResource(R.string.empty_sugg_code_prompt)),
        Suggestion(stringResource(R.string.empty_sugg_brainstorm_title), stringResource(R.string.empty_sugg_brainstorm_desc), Icons.Filled.Lightbulb, stringResource(R.string.empty_sugg_brainstorm_prompt)),
    )
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.empty_greeting),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        // First run: nothing works until a gateway is set, so say so up front with a way there.
        if (!configured) {
            SetupCard(onConfigure)
            Spacer(Modifier.height(16.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            suggestions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { s ->
                        SuggestionCard(s, Modifier.weight(1f)) { onSuggestion(s.prompt) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(onConfigure: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.setup_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_action))
            }
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(120.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Icon(
                    s.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(8.dp).size(22.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(s.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                s.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 顶栏 "AI 助手" 徽标：发丝边框单色胶囊。 */
@Composable
internal fun AiBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            stringResource(R.string.ai_badge),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** 单色胶囊标签，用于抽屉里的 Agent 标识。 */
@Composable
internal fun AgentTag(agent: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            agent,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
