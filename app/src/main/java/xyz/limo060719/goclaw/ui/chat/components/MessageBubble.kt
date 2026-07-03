package xyz.limo060719.goclaw.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.limo060719.goclaw.domain.model.FileRef
import xyz.limo060719.goclaw.domain.model.Role
import xyz.limo060719.goclaw.domain.model.UiMessage
import xyz.limo060719.goclaw.ui.chat.WechatProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息列表的一行：包裹多选态、长按菜单与双击查看详情，
 * 内容分发给 [ChatBubble]（文本消息）或 [ToolCardView]（工具消息）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageRow(
    msg: UiMessage,
    wechat: Boolean,
    profile: WechatProfile,
    selectionMode: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onDoubleTap: (() -> Unit)?,
    onStartSelection: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDownloadFile: ((FileRef) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val rowBg =
        if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent

    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(rowBg)
            .combinedClickable(
                onClick = { if (selectionMode) onTap() },
                onLongClick = { if (selectionMode) onTap() else menuOpen = true },
                onDoubleClick = if (!selectionMode) onDoubleTap else null,
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onTap() })
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.weight(1f)) { MessageItem(msg, wechat, profile, onDownloadFile) }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("复制") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { menuOpen = false; onCopy() },
            )
            DropdownMenuItem(
                text = { Text("分享") },
                leadingIcon = { Icon(Icons.Filled.Share, null) },
                onClick = { menuOpen = false; onShare() },
            )
            DropdownMenuItem(
                text = { Text("多选") },
                leadingIcon = { Icon(Icons.Filled.Checklist, null) },
                onClick = { menuOpen = false; onStartSelection() },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

@Composable
private fun MessageItem(
    msg: UiMessage,
    wechat: Boolean,
    profile: WechatProfile,
    onDownloadFile: ((FileRef) -> Unit)? = null,
) {
    when (msg.role) {
        Role.TOOL -> ToolCardView(msg, onDownload = onDownloadFile)
        else -> ChatBubble(msg, wechat, profile, onDownloadFile)
    }
}

/**
 * 聊天气泡。单色语言下的核心反转：
 * 用户气泡 = primary（暗色下为白底黑字），助手气泡 = 灰阶容器 + 发丝边框。
 * 微信模式保留经典绿色气泡，不套用单色规则。
 */
@Composable
private fun ChatBubble(
    msg: UiMessage,
    wechat: Boolean,
    profile: WechatProfile,
    onDownloadFile: ((FileRef) -> Unit)? = null,
) {
    val isUser = msg.role == Role.USER
    val bubbleColor = when {
        wechat && isUser -> Color(0xFF95EC69)
        wechat -> MaterialTheme.colorScheme.surfaceContainerHighest
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val bubbleContent = when {
        wechat && isUser -> Color(0xFF111111)
        wechat -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val bubbleBorder = if (!wechat && !isUser) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else null
    val bubbleShape = if (wechat) RoundedCornerShape(8.dp) else RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (isUser) 20.dp else 6.dp,
        bottomEnd = if (isUser) 6.dp else 20.dp,
    )
    val name = if (isUser) profile.selfName else profile.assistantName
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            AssistantAvatar(if (wechat) profile.assistantAvatar else "")
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            if (wechat && name.isNotBlank()) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
                )
            }
            Surface(
                color = bubbleColor,
                contentColor = bubbleContent,
                shape = bubbleShape,
                border = bubbleBorder,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Column(Modifier.padding(if (wechat) 10.dp else 14.dp)) {
                    if (!isUser && msg.thinking.isNotBlank()) {
                        ThinkingBlock(
                            text = msg.thinking,
                            active = msg.streaming && msg.text.isBlank(),
                        )
                        if (msg.text.isNotEmpty()) Spacer(Modifier.height(8.dp))
                    }
                    msg.attachments.forEach { att ->
                        AsyncImage(
                            model = att.previewUri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .padding(bottom = 6.dp),
                        )
                    }
                    msg.fileNames.forEach { fileName ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (msg.text.isNotEmpty()) {
                        MessageText(msg.text)
                    }
                    if (msg.files.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        msg.files.forEach { file ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(bubbleContent.copy(alpha = 0.06f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                FileRow(file, showPath = false, onDownload = onDownloadFile)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    if (msg.streaming) {
                        Spacer(Modifier.height(2.dp))
                        Text("▍", color = bubbleContent)
                    }
                }
            }
            if (!wechat) {
                Spacer(Modifier.height(4.dp))
                Text(
                    formatTime(msg.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (wechat && isUser) {
            Spacer(Modifier.width(8.dp))
            UserAvatar(profile.selfAvatar)
        }
    }
}

@Composable
internal fun UserAvatar(path: String = "") {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(34.dp),
    ) {
        if (path.isNotBlank()) {
            val file = remember(path) { File(path) }
            AsyncImage(
                model = file, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun AssistantAvatar(path: String = "") {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(34.dp),
    ) {
        if (path.isNotBlank()) {
            val file = remember(path) { File(path) }
            AsyncImage(
                model = file, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Collapsible extended-thinking / reasoning block shown above an assistant reply. */
@Composable
private fun ThinkingBlock(text: String, active: Boolean) {
    // Always collapsed by default (consistent across blocks); tap to expand. The brief "thinking"
    // phase is covered by the typing indicator, so there's no need to auto-open this.
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (active) "正在思考…" else "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起思考" else "展开思考",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** 助手正在生成时的占位气泡。 */
@Composable
internal fun TypingDots() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistantAvatar()
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text("…", Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
internal fun formatTime(epochMs: Long): String = timeFormat.format(Date(epochMs))
