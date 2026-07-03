package xyz.limo060719.goclaw.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.limo060719.goclaw.data.ConversationMeta
import xyz.limo060719.goclaw.ui.chat.components.AgentTag
import xyz.limo060719.goclaw.ui.chat.components.RenameDialog

/** 侧边抽屉：搜索 / 新建对话 / 历史列表 / 附加功能与设置入口。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChatDrawer(
    conversations: List<ConversationMeta>,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onOpenExtras: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ConversationMeta?>(null) }
    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations
        else conversations.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.title,
            onConfirm = { newTitle -> onRenameConversation(target.id, newTitle); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerTonalElevation = 0.dp,
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("GoClaw Chat", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
        }

        // Search
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索历史对话") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(12.dp))

        // New chat (bordered card)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(MaterialTheme.shapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                .clickable(onClick = onNewChat)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Text("新建对话", style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "历史对话",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (conversations.isEmpty()) "暂无历史对话" else "未找到匹配的对话",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.id }) { conv ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .combinedClickable(
                                    onClick = { onOpenConversation(conv.id) },
                                    onLongClick = { renameTarget = conv },
                                )
                                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        ) {
                            Icon(
                                Icons.Filled.ChatBubbleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    conv.title.ifBlank { "新对话" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                conv.agentKey?.takeIf { it.isNotBlank() }?.let { agent ->
                                    Spacer(Modifier.height(3.dp))
                                    AgentTag(agent)
                                }
                            }
                            IconButton(onClick = { onDeleteConversation(conv.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除对话",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        DrawerEntry(Icons.Filled.Apps, "附加功能", onOpenExtras)
        HorizontalDivider(
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        DrawerEntry(Icons.Filled.Settings, "设置", onOpenSettings)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DrawerEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
