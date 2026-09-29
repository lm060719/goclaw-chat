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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.data.ConversationMeta
import xyz.limo060719.goclaw.ui.chat.components.AgentTag
import xyz.limo060719.goclaw.ui.chat.components.RenameDialog

/** 侧边抽屉：搜索（标题 + 内容）/ 新建对话 / 分组历史（置顶、按时间）/ 多选 / 附加功能与设置入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatDrawer(
    conversations: List<ConversationMeta>,
    pendingDeletes: Set<String>,
    searchHits: List<SearchHit>?,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onOpenConversation: (id: String, focusMessageId: String?) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onDeleteConversations: (Set<String>) -> Unit,
    onUndoDelete: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onExport: (String) -> Unit,
    onOpenExtras: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ConversationMeta?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    // Content search reads every conversation file → debounce keystrokes.
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(250)
        onSearch(query)
    }
    val searching = query.isNotBlank()
    val hitsById = remember(searchHits) { searchHits.orEmpty().associateBy { it.conversationId } }
    val shown = remember(conversations, query, searchHits) {
        when {
            !searching -> conversations
            // Until the content search lands, filter by title so typing feels instant.
            searchHits == null -> conversations.filter { it.title.contains(query.trim(), ignoreCase = true) }
            else -> conversations.filter { it.id in hitsById }
        }
    }
    val groups = remember(shown, searching) {
        if (searching) listOf(null to shown) else groupConversations(shown).map { (g, list) -> g to list }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.title,
            onConfirm = { newTitle -> onRenameConversation(target.id, newTitle); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }

    fun exitSelection() { selecting = false; selected = emptySet() }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerTonalElevation = 0.dp,
    ) {
        // Header (normal) / selection bar (multi-select)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Text(
                    stringResource(R.string.dialog_selected_count, selected.size),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selected = shown.map { it.id }.toSet() }) {
                    Text(stringResource(R.string.drawer_select_all))
                }
                IconButton(
                    onClick = { onDeleteConversations(selected); exitSelection() },
                    enabled = selected.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
                }
                IconButton(onClick = ::exitSelection) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                }
            } else {
                Text("GoClaw Chat", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
        }

        // Search (titles + message content)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.drawer_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_clear))
                    }
                }
            } else null,
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
            Text(stringResource(R.string.chat_new_conversation), style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(8.dp))

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (conversations.isEmpty()) R.string.drawer_history_empty else R.string.drawer_history_no_match),
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
                groups.forEach { (group, list) ->
                    item(key = "header_${group?.name ?: "search"}") {
                        Text(
                            stringResource(groupLabel(group)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(list, key = { it.id }) { conv ->
                        if (conv.id in pendingDeletes) {
                            UndoDeleteRow(
                                title = conv.title.ifBlank { stringResource(R.string.chat_untitled) },
                                onUndo = { onUndoDelete(conv.id) },
                            )
                        } else {
                            ConversationRow(
                                conv = conv,
                                snippet = hitsById[conv.id]?.snippet,
                                selecting = selecting,
                                selected = conv.id in selected,
                                onClick = {
                                    if (selecting) {
                                        selected = if (conv.id in selected) selected - conv.id else selected + conv.id
                                    } else {
                                        onOpenConversation(conv.id, hitsById[conv.id]?.messageId)
                                    }
                                },
                                onRename = { renameTarget = conv },
                                onTogglePin = { onSetPinned(conv.id, !conv.pinned) },
                                onExport = { onExport(conv.id) },
                                onStartSelection = { selecting = true; selected = setOf(conv.id) },
                                onDelete = { onDeleteConversation(conv.id) },
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        DrawerEntry(Icons.Filled.Apps, stringResource(R.string.drawer_extras), onOpenExtras)
        HorizontalDivider(
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        DrawerEntry(Icons.Filled.Settings, stringResource(R.string.settings_title), onOpenSettings)
        Spacer(Modifier.height(12.dp))
    }
}

private fun groupLabel(group: ConversationGroup?): Int = when (group) {
    null -> R.string.drawer_search_results
    ConversationGroup.PINNED -> R.string.drawer_group_pinned
    ConversationGroup.TODAY -> R.string.drawer_group_today
    ConversationGroup.YESTERDAY -> R.string.drawer_group_yesterday
    ConversationGroup.LAST_7_DAYS -> R.string.drawer_group_week
    ConversationGroup.OLDER -> R.string.drawer_group_older
}

/** One history entry: tap opens (or toggles in multi-select), long-press opens the action menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: ConversationMeta,
    snippet: String?,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
    onStartSelection: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Surface(
            color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { if (selecting) onClick() else menuOpen = true },
                    )
                    .padding(start = if (selecting) 4.dp else 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                if (selecting) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    Icon(
                        if (conv.pinned) Icons.Filled.PushPin else Icons.Filled.ChatBubbleOutline,
                        contentDescription = if (conv.pinned) stringResource(R.string.drawer_group_pinned) else null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(
                        conv.title.ifBlank { stringResource(R.string.chat_untitled) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    snippet?.let {
                        Text(
                            it,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    conv.agentKey?.takeIf { it.isNotBlank() }?.let { agent ->
                        Spacer(Modifier.height(3.dp))
                        AgentTag(agent)
                    }
                }
                if (!selecting) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.drawer_delete_conversation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            MenuItem(Icons.Filled.Edit, stringResource(R.string.drawer_rename)) { menuOpen = false; onRename() }
            MenuItem(
                if (conv.pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                stringResource(if (conv.pinned) R.string.drawer_unpin else R.string.drawer_pin),
            ) { menuOpen = false; onTogglePin() }
            MenuItem(Icons.Filled.IosShare, stringResource(R.string.drawer_export)) { menuOpen = false; onExport() }
            MenuItem(Icons.Filled.Checklist, stringResource(R.string.msg_multiselect)) { menuOpen = false; onStartSelection() }
            MenuItem(Icons.Filled.Delete, stringResource(R.string.common_delete)) { menuOpen = false; onDelete() }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** Placeholder for a just-deleted conversation while its undo window is open. */
@Composable
private fun UndoDeleteRow(title: String, onUndo: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                stringResource(R.string.drawer_deleted, title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo) { Text(stringResource(R.string.drawer_undo)) }
        }
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
