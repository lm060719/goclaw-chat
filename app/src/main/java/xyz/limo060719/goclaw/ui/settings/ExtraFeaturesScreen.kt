package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraFeaturesScreen(
    onBack: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenApprovals: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenTraces: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("附加功能") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FeatureRow(
                icon = Icons.Filled.Extension,
                title = "技能",
                subtitle = "导入并管理注入到对话的技能",
                onClick = onOpenSkills,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.VerifiedUser,
                title = "审批管理",
                subtitle = "审批 Agent 待处理的 shell 命令",
                onClick = onOpenApprovals,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.Forum,
                title = "会话管理",
                subtitle = "浏览服务端会话，压缩超长历史、清空或删除",
                onClick = onOpenSessions,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.QueryStats,
                title = "用量 & 费用",
                subtitle = "查看 Token 消耗与费用统计",
                onClick = onOpenUsage,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.Insights,
                title = "执行轨迹",
                subtitle = "查看 Agent 运行的 LLM 调用轨迹",
                onClick = onOpenTraces,
            )
}
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
