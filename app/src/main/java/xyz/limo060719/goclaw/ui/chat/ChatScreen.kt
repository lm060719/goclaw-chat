package xyz.limo060719.goclaw.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import xyz.limo060719.goclaw.data.ConversationMeta
import xyz.limo060719.goclaw.domain.model.Role
import xyz.limo060719.goclaw.domain.model.UiMessage
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenExtras: () -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    var detailMessage by remember { mutableStateOf<UiMessage?>(null) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val wechat by vm.wechatUi.collectAsStateWithLifecycle()
    val profile by vm.wechatProfile.collectAsStateWithLifecycle()
    val savedAgents by vm.savedAgents.collectAsStateWithLifecycle()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(vm::addImage) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::addFile) }

    var hasRecordPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasRecordPerm = granted }

    // A new message: animate to the bottom once.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    // Streaming tokens: jump instantly instead of restarting a scroll animation per token,
    // which otherwise fights itself and drops frames.
    LaunchedEffect(state.messages.lastOrNull()?.text) {
        if (state.isStreaming && state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }
    LaunchedEffect(state.error) {
        state.error?.let { scope.launch { snackbar.showSnackbar(it) }; vm.clearError() }
    }
    // When returning to the foreground, recover any reply that finished while we were away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.syncPending() }

    // In multi-select, the system Back press exits selection instead of leaving the screen.
    BackHandler(enabled = state.selectionMode) { vm.clearSelection() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !state.selectionMode,
        drawerContent = {
            ChatDrawer(
                conversations = conversations,
                onClose = { scope.launch { drawerState.close() } },
                onNewChat = { scope.launch { drawerState.close() }; vm.newConversation() },
                onOpenConversation = { id ->
                    scope.launch { drawerState.close() }; vm.openConversation(id)
                },
                onDeleteConversation = vm::deleteConversation,
                onRenameConversation = vm::renameConversation,
                onOpenExtras = { scope.launch { drawerState.close(); onOpenExtras() } },
                onOpenSettings = { scope.launch { drawerState.close(); onOpenSettings() } },
            )
        },
    ) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    count = state.selectedIds.size,
                    onClose = vm::clearSelection,
                    onShare = { shareText(context, vm.textOf(state.selectedIds)) },
                    onCopy = {
                        clipboard.setText(AnnotatedString(vm.textOf(state.selectedIds)))
                        scope.launch { snackbar.showSnackbar("已复制") }
                    },
                    onDelete = { pendingDelete = state.selectedIds },
                )
            } else if (wechat) {
                val title = profile.assistantName.ifBlank {
                    state.messages.firstOrNull { it.role == Role.USER }
                        ?.text?.trim()?.take(14)?.takeIf { it.isNotBlank() } ?: "GoClaw"
                }
                CenterAlignedTopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                    actions = {
                        IconButton(onClick = vm::toggleTts) {
                            Icon(
                                if (state.ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = "切换语音播报",
                            )
                        }
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.MoreHoriz, contentDescription = "菜单")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("GoClaw Chat", style = MaterialTheme.typography.titleLarge)
                            if (state.messages.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                AiBadge()
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "菜单")
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::toggleTts) {
                            Icon(
                                if (state.ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = "切换语音播报",
                            )
                        }
                        IconButton(onClick = vm::newConversation) {
                            Icon(Icons.Filled.Add, contentDescription = "新建对话")
                        }
                    },
                )
            }
        },
        bottomBar = {
            Column {
                if (savedAgents.isNotEmpty()) {
                    AgentSwitcher(
                        agent = state.agent,
                        savedAgents = savedAgents,
                        locked = state.messages.isNotEmpty(),
                        onSelect = vm::selectAgent,
                    )
                }
                InputBar(
                    input = state.input,
                    attachmentCount = state.attachments.size + state.files.size,
                    isStreaming = state.isStreaming,
                    hasMessages = state.messages.isNotEmpty(),
                    onInputChange = vm::onInputChange,
                    onSend = vm::send,
                    onStop = vm::stopStreaming,
                    onPickImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    recording = state.isRecording,
                    canRecord = hasRecordPerm,
                    onRequestRecord = { recordPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onVoiceStart = vm::startVoiceMessage,
                    onVoiceEnd = vm::finishVoiceMessage,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.attachments.isNotEmpty() || state.files.isNotEmpty()) {
                AttachmentStrip(state, vm)
            }
            if (state.messages.isEmpty() && !state.isStreaming) {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    onSuggestion = { prompt -> vm.onInputChange(prompt) },
                )
            } else
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    val isTextMessage = msg.role != Role.TOOL && msg.text.isNotBlank()
                    MessageRow(
                        msg = msg,
                        wechat = wechat,
                        profile = profile,
                        selectionMode = state.selectionMode,
                        selected = msg.id in state.selectedIds,
                        onTap = { vm.toggleSelected(msg.id) },
                        onDoubleTap = if (isTextMessage) ({ detailMessage = msg }) else null,
                        onStartSelection = { vm.startSelection(msg.id) },
                        onCopy = {
                            clipboard.setText(AnnotatedString(vm.textOf(setOf(msg.id))))
                            scope.launch { snackbar.showSnackbar("已复制") }
                        },
                        onShare = { shareText(context, vm.textOf(setOf(msg.id))) },
                        onDelete = { pendingDelete = setOf(msg.id) },
                        onDownloadFile = { vm.downloadAndSaveFile(it) },
                    )
                }
                if (state.isStreaming && state.messages.lastOrNull()?.streaming != true) {
                    item { TypingDots() }
                }
            }
        }
    }

        pendingDelete?.let { ids ->
            DeleteConfirmDialog(
                count = ids.size,
                onConfirm = { vm.deleteMessages(ids); pendingDelete = null },
                onDismiss = { pendingDelete = null },
            )
        }

        detailMessage?.let { msg ->
            MessageDetailView(text = msg.text, onClose = { detailMessage = null })
        }

        if (state.isRecording) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Mic, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("正在录音…", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "松开发送",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatDrawer(
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
            Text("GoClaw Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(12.dp))

        // New chat (bordered card)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                .clickable(onClick = onNewChat)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(
                                    onClick = { onOpenConversation(conv.id) },
                                    onLongClick = { renameTarget = conv },
                                )
                                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        ) {
                            Icon(
                                Icons.Filled.ChatBubbleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
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

        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        DrawerEntry(Icons.Filled.Apps, "附加功能", onOpenExtras)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
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
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentTag(agent: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        contentColor = MaterialTheme.colorScheme.primary,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSwitcher(
    agent: String,
    savedAgents: List<String>,
    locked: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 14.dp, top = 2.dp)) {
        AssistChip(
            onClick = { if (!locked) open = true },
            label = {
                Text(agent.ifBlank { "default" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = {
                Icon(Icons.Filled.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            trailingIcon = {
                Icon(
                    if (locked) Icons.Filled.Lock else Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            savedAgents.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a) },
                    onClick = { open = false; onSelect(a) },
                    trailingIcon = if (a == agent) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名对话") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeleteConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除消息") },
        text = { Text("确定删除选中的 $count 条消息吗？此操作无法撤销。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageDetailView(text: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("消息详情") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
            SelectionContainer(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun AttachmentStrip(state: ChatUiState, vm: ChatViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.attachments.forEach { att ->
            Box {
                AsyncImage(
                    model = att.previewUri,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    IconButton(onClick = { vm.removeAttachment(att) }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "移除", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        state.files.forEach { f ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp, end = 2.dp),
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        f.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    IconButton(onClick = { vm.removeFile(f) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "移除", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val enabled = count > 0
    TopAppBar(
        title = { Text("已选择 $count 项") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "取消")
            }
        },
        actions = {
            IconButton(onClick = onCopy, enabled = enabled) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
            }
            IconButton(onClick = onShare, enabled = enabled) {
                Icon(Icons.Filled.Share, contentDescription = "分享")
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
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
    onDownloadFile: ((xyz.limo060719.goclaw.domain.model.FileRef) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val rowBg =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
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

private fun shareText(context: Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享到"))
}

@Composable
private fun MessageItem(msg: UiMessage, wechat: Boolean, profile: WechatProfile, onDownloadFile: ((xyz.limo060719.goclaw.domain.model.FileRef) -> Unit)? = null) {
    when (msg.role) {
        Role.TOOL -> ToolCardView(msg, onDownload = onDownloadFile)
        else -> ChatBubble(msg, wechat, profile, onDownloadFile)
    }
}

@Composable
private fun ChatBubble(msg: UiMessage, wechat: Boolean, profile: WechatProfile, onDownloadFile: ((xyz.limo060719.goclaw.domain.model.FileRef) -> Unit)? = null) {
    val isUser = msg.role == Role.USER
    val bubbleColor = when {
        wechat && isUser -> Color(0xFF95EC69)
        wechat -> MaterialTheme.colorScheme.surfaceContainerHighest
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val bubbleContent = when {
        wechat && isUser -> Color(0xFF111111)
        wechat -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
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
                shape = RoundedCornerShape(if (wechat) 8.dp else 18.dp),
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
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(bottom = 6.dp),
                        )
                    }
                    msg.fileNames.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (msg.text.isNotEmpty()) {
                        MessageText(msg.text)
                    }
                    if (msg.files.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        msg.files.forEach { file ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .let { if (onDownloadFile != null) it.clickable { onDownloadFile(file) } else it }
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    fileIcon(file.mimeType),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    file.filename.ifBlank { file.path.substringAfterLast('/') },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (onDownloadFile != null) {
                                    Icon(
                                        Icons.Filled.Download,
                                        contentDescription = "下载",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
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
private fun UserAvatar(path: String = "") {
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
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantAvatar(path: String = "") {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
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
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/* ---- message body: plain text + fenced code blocks with a copy button ---- */

private sealed interface MsgSegment
private data class TextSegment(val text: String) : MsgSegment
private data class CodeSegment(val code: String, val lang: String) : MsgSegment
private data class TableSegment(val header: List<String>, val rows: List<List<String>>) : MsgSegment

private val codeFenceRegex = Regex("```([a-zA-Z0-9+#._-]*)\\r?\\n?([\\s\\S]*?)```")

/** A Markdown table separator row, e.g. `|---|:--:|---:|` — only pipes, dashes, colons, spaces. */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.contains('-') && t.contains('|') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private fun parseSegments(text: String): List<MsgSegment> {
    val out = mutableListOf<MsgSegment>()
    var last = 0
    for (m in codeFenceRegex.findAll(text)) {
        if (m.range.first > last) {
            text.substring(last, m.range.first).trim('\n', '\r')
                .takeIf { it.isNotBlank() }?.let { splitTables(it, out) }
        }
        out.add(CodeSegment(m.groupValues[2].trimEnd('\n', '\r'), m.groupValues[1].trim()))
        last = m.range.last + 1
    }
    if (last < text.length) {
        text.substring(last).trim('\n', '\r').takeIf { it.isNotBlank() }?.let { splitTables(it, out) }
    }
    if (out.isEmpty()) out.add(TextSegment(text))
    return out
}

/** Split a (code-free) block into plain-text and Markdown-table segments. */
private fun splitTables(block: String, out: MutableList<MsgSegment>) {
    val lines = block.split('\n')
    val buf = StringBuilder()
    fun flushText() {
        buf.toString().trim('\n', '\r').takeIf { it.isNotBlank() }?.let { out.add(TextSegment(it)) }
        buf.setLength(0)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val next = lines.getOrNull(i + 1)
        // A header row followed by a `---` separator row starts a table.
        if (line.contains('|') && next != null && isTableSeparator(next)) {
            flushText()
            val header = splitCells(line)
            val rows = mutableListOf<List<String>>()
            var j = i + 2
            while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                rows.add(splitCells(lines[j])); j++
            }
            out.add(TableSegment(header, rows))
            i = j
        } else {
            buf.append(line).append('\n'); i++
        }
    }
    flushText()
}

private fun splitCells(row: String): List<String> {
    var s = row.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    return s.split('|').map { it.trim() }
}

/** Collapsible extended-thinking / reasoning block shown above an assistant reply. */
@Composable
private fun ThinkingBlock(text: String, active: Boolean) {
    // Always collapsed by default (consistent across blocks); tap to expand. The brief "thinking"
    // phase is covered by the typing indicator, so there's no need to auto-open this.
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("💭", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
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

@Composable
private fun MessageText(text: String) {
    val segments = remember(text) { parseSegments(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is TextSegment -> MarkdownText(seg.text)
                is CodeSegment -> CodeBlock(seg.code, seg.lang)
                is TableSegment -> TableBlock(seg.header, seg.rows)
            }
        }
    }
}

/* ---- lightweight Markdown rendering (headings, lists, quotes, inline styles) ---- */

private sealed interface MdBlock
private data class MdHeading(val level: Int, val text: String) : MdBlock
private data class MdBullet(val text: String, val marker: String) : MdBlock
private data class MdQuote(val text: String) : MdBlock
private data class MdParagraph(val text: String) : MdBlock
private object MdDivider : MdBlock

private val orderedItemRegex = Regex("^\\d+\\. ")
private val dividerRegex = Regex("^(-{3,}|\\*{3,}|_{3,})$")

private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        para.toString().trim().takeIf { it.isNotBlank() }?.let { blocks.add(MdParagraph(it)) }
        para.setLength(0)
    }
    fun appendPara(s: String) { if (para.isNotEmpty()) para.append(' '); para.append(s.trim()) }
    for (raw in text.split('\n')) {
        val t = raw.trim()
        val hashes = t.takeWhile { it == '#' }.length
        when {
            t.isBlank() -> flush()
            dividerRegex.matches(t) -> { flush(); blocks.add(MdDivider) }
            hashes in 1..6 && t.getOrNull(hashes) == ' ' -> {
                flush(); blocks.add(MdHeading(hashes.coerceAtMost(4), t.drop(hashes).trim()))
            }
            t.startsWith("> ") -> { flush(); blocks.add(MdQuote(t.removePrefix("> ").trim())) }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") ->
                { flush(); blocks.add(MdBullet(t.drop(2).trim(), "•")) }
            orderedItemRegex.containsMatchIn(t) ->
                { flush(); blocks.add(MdBullet(t.substringAfter(' ').trim(), t.takeWhile { it != ' ' })) }
            else -> appendPara(raw)
        }
    }
    flush()
    return blocks
}

/** Append text with inline Markdown (`**bold**`, `*italic*`, `` `code` ``, `~~strike~~`, links). */
private fun AnnotatedString.Builder.appendInline(text: String, codeBg: Color, linkColor: Color) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendInline(text.substring(i + 2, end), codeBg, linkColor); pop()
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendInline(text.substring(i + 1, end), codeBg, linkColor); pop()
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end >= 0) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    appendInline(text.substring(i + 2, end), codeBg, linkColor); pop()
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg))
                    append(text.substring(i + 1, end)); pop()
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val close = text.indexOf(']', i + 1)
                if (close >= 0 && text.getOrNull(close + 1) == '(') {
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd >= 0) {
                        val url = text.substring(close + 2, urlEnd).trim()
                        val linkStyle = TextLinkStyles(
                            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                        )
                        // LinkAnnotation makes the span clickable; Text opens it via the platform UriHandler.
                        withLink(LinkAnnotation.Url(url, linkStyle)) {
                            appendInline(text.substring(i + 1, close), codeBg, linkColor)
                        }
                        i = urlEnd + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
private fun MarkdownText(text: String) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val linkColor = MaterialTheme.colorScheme.primary
    fun inline(s: String) = buildAnnotatedString { appendInline(s, codeBg, linkColor) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdHeading -> Text(
                    inline(b.text),
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    fontWeight = FontWeight.Bold,
                )
                is MdParagraph -> Text(inline(b.text), style = MaterialTheme.typography.bodyLarge)
                is MdBullet -> Row(verticalAlignment = Alignment.Top) {
                    Text("${b.marker} ", style = MaterialTheme.typography.bodyLarge)
                    Text(inline(b.text), style = MaterialTheme.typography.bodyLarge)
                }
                is MdQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier.width(3.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inline(b.text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MdDivider -> HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun TableBlock(header: List<String>, rows: List<List<String>>) {
    val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val linkColor = MaterialTheme.colorScheme.primary

    @Composable
    fun RowOfCells(cells: List<String>, isHeader: Boolean) {
        Row(
            Modifier
                .height(IntrinsicSize.Min)
                .background(if (isHeader) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
        ) {
            for (c in 0 until cols) {
                if (c > 0) VerticalDivider(color = border)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(
                        buildAnnotatedString { appendInline(cells.getOrNull(c).orEmpty(), codeBg, linkColor) },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }

    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp)),
    ) {
        RowOfCells(header, isHeader = true)
        rows.forEach { row ->
            HorizontalDivider(color = border)
            RowOfCells(row, isHeader = false)
        }
    }
}

@Composable
private fun CodeBlock(code: String, lang: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }

    Surface(
        color = Color(0xFF0B0D12),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8A8FA0),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(code)); copied = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = "复制代码",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider(color = Color(0xFF20242E))
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD6D8E0),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun AiBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            "AI 助手",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatTime(epochMs: Long): String = timeFormat.format(java.util.Date(epochMs))

private data class Suggestion(val title: String, val subtitle: String, val icon: ImageVector, val prompt: String)

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        Suggestion("写一段文案", "帮你创作各种风格文案", Icons.Filled.EditNote, "帮我写一段文案："),
        Suggestion("总结内容", "提炼要点，快速总结", Icons.Filled.Description, "帮我总结以下内容："),
        Suggestion("生成代码", "生成代码片段或完整函数", Icons.Filled.Code, "帮我写一段代码，需求是："),
        Suggestion("头脑风暴", "激发灵感，拓展思路", Icons.Filled.Lightbulb, "和我一起头脑风暴，主题是："),
    )
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "你好，今天想聊点什么？",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "我是 GoClaw，你的 AI 助手，随时为你提供帮助。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
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
private fun SuggestionCard(s: Suggestion, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.height(120.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            ) {
                Icon(
                    s.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(8.dp).size(22.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun ToolCardView(msg: UiMessage, onDownload: ((xyz.limo060719.goclaw.domain.model.FileRef) -> Unit)? = null) {
    val tool = msg.tool ?: return
    android.util.Log.d("GoClawChat", "ToolCardView name=${tool.name} files=${tool.files.size} args=${tool.arguments.take(100)}")
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("工具 · ${tool.name}", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "展开/收起",
                    )
                }
            }
            if (!expanded) {
                Text(
                    tool.result,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Text("参数", style = MaterialTheme.typography.labelSmall)
                    Text(tool.arguments, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("结果", style = MaterialTheme.typography.labelSmall)
                    Text(tool.result, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (tool.files.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))
                Text("输出文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.height(4.dp))
                tool.files.forEach { file ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .let { if (onDownload != null) it.clickable { onDownload(file) } else it }
                            .padding(vertical = 4.dp),
                    ) {
                        Icon(
                            fileIcon(file.mimeType),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.filename.ifBlank { file.path.substringAfterLast('/') },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                file.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (onDownload != null) {
                            IconButton(onClick = { onDownload(file) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = "下载",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fileIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Filled.Image
    mimeType.startsWith("video/") -> Icons.Filled.VideoFile
    mimeType.startsWith("audio/") -> Icons.Filled.AudioFile
    mimeType.contains("pdf") -> Icons.Filled.PictureAsPdf
    mimeType.contains("zip") || mimeType.contains("archive") -> Icons.Filled.FolderZip
    mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("javascript") -> Icons.Filled.Code
    else -> Icons.Filled.InsertDriveFile
}

@Composable
private fun TypingDots() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistantAvatar()
        Spacer(Modifier.width(8.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
            Text("…", Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    input: String,
    attachmentCount: Int,
    isStreaming: Boolean,
    hasMessages: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    recording: Boolean,
    canRecord: Boolean,
    onRequestRecord: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceEnd: () -> Unit,
) {
    val canSend = input.isNotBlank() || attachmentCount > 0
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxWidth().padding(10.dp).navigationBarsPadding().imePadding()) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPickImage) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = "添加图片",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onPickFile) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = "添加文件",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Hold to record a voice message; release to send.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(canRecord) {
                                detectTapGestures(
                                    onPress = {
                                        if (!canRecord) {
                                            onRequestRecord()
                                            return@detectTapGestures
                                        }
                                        onVoiceStart()
                                        tryAwaitRelease()
                                        onVoiceEnd()
                                    },
                                )
                            },
                    ) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "按住发送语音",
                            tint = if (recording) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextField(
                        value = input,
                        onValueChange = onInputChange,
                        placeholder = {
                            Text(if (hasMessages) "继续输入…" else "输入消息")
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    FilledIconButton(
                        onClick = if (isStreaming) onStop else onSend,
                        enabled = isStreaming || canSend,
                        shape = CircleShape,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            if (isStreaming) Icons.Filled.Stop else Icons.Filled.Send,
                            contentDescription = if (isStreaming) "停止" else "发送",
                        )
                    }
                }
            }
        }
    }
}
