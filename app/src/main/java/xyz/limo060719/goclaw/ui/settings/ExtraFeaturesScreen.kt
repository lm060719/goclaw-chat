package xyz.limo060719.goclaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.limo060719.goclaw.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraFeaturesScreen(
    onBack: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenApprovals: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenTraces: () -> Unit,
    onOpenBackendSkills: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenHeartbeat: () -> Unit,
    onOpenApiKeys: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extras_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            FeatureRow(
                icon = Icons.Filled.Extension,
                title = stringResource(R.string.extras_skills_title),
                subtitle = stringResource(R.string.extras_skills_desc),
                onClick = onOpenSkills,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.Bolt,
                title = stringResource(R.string.extras_backend_skills_title),
                subtitle = stringResource(R.string.extras_backend_skills_desc),
                onClick = onOpenBackendSkills,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.VerifiedUser,
                title = stringResource(R.string.extras_approvals_title),
                subtitle = stringResource(R.string.extras_approvals_desc),
                onClick = onOpenApprovals,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.Forum,
                title = stringResource(R.string.extras_sessions_title),
                subtitle = stringResource(R.string.extras_sessions_desc),
                onClick = onOpenSessions,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.QueryStats,
                title = stringResource(R.string.extras_usage_title),
                subtitle = stringResource(R.string.extras_usage_desc),
                onClick = onOpenUsage,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.Insights,
                title = stringResource(R.string.extras_traces_title),
                subtitle = stringResource(R.string.extras_traces_desc),
                onClick = onOpenTraces,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.Devices,
                title = stringResource(R.string.extras_pairing_title),
                subtitle = stringResource(R.string.extras_pairing_desc),
                onClick = onOpenPairing,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.Subject,
                title = stringResource(R.string.extras_logs_title),
                subtitle = stringResource(R.string.extras_logs_desc),
                onClick = onOpenLogs,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.MonitorHeart,
                title = stringResource(R.string.extras_heartbeat_title),
                subtitle = stringResource(R.string.extras_heartbeat_desc),
                onClick = onOpenHeartbeat,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            FeatureRow(
                icon = Icons.Filled.Key,
                title = stringResource(R.string.extras_api_keys_title),
                subtitle = stringResource(R.string.extras_api_keys_desc),
                onClick = onOpenApiKeys,
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
