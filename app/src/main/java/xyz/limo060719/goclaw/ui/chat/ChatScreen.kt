package xyz.limo060719.goclaw.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.domain.model.Role
import xyz.limo060719.goclaw.domain.model.UiMessage
import xyz.limo060719.goclaw.ui.chat.components.AgentSwitcher
import xyz.limo060719.goclaw.ui.chat.components.AiBadge
import xyz.limo060719.goclaw.ui.chat.components.AttachmentStrip
import xyz.limo060719.goclaw.ui.chat.components.DeleteConfirmDialog
import xyz.limo060719.goclaw.ui.chat.components.EmptyState
import xyz.limo060719.goclaw.ui.chat.components.InputBar
import xyz.limo060719.goclaw.ui.chat.components.MessageDetailView
import xyz.limo060719.goclaw.ui.chat.components.MessageRow
import xyz.limo060719.goclaw.ui.chat.components.RecordingOverlay
import xyz.limo060719.goclaw.ui.chat.components.SelectionTopBar
import xyz.limo060719.goclaw.ui.chat.components.TypingDots

/**
 * 聊天主屏。只负责：状态收集、系统交互（权限 / 选择器 / 返回键）、Scaffold 组装。
 * 所有可见组件都在 ui/chat/components 下，抽屉在 [ChatDrawer]。
 */
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
    val showConnectionDot by vm.showConnectionStatus.collectAsStateWithLifecycle()
    val connectionOnline by vm.connectionOnline.collectAsStateWithLifecycle()

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
                    ChatTopBar(
                        state = state,
                        wechat = wechat,
                        assistantName = profile.assistantName,
                        showConnectionDot = showConnectionDot,
                        connectionOnline = connectionOnline,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onToggleTts = vm::toggleTts,
                        onNewConversation = vm::newConversation,
                        onCloseSelection = vm::clearSelection,
                        onShareSelection = { shareText(context, vm.textOf(state.selectedIds)) },
                        onCopySelection = {
                            clipboard.setText(AnnotatedString(vm.textOf(state.selectedIds)))
                            scope.launch { snackbar.showSnackbar("已复制") }
                        },
                        onDeleteSelection = { pendingDelete = state.selectedIds },
                    )
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
                        AttachmentStrip(
                            attachments = state.attachments,
                            files = state.files,
                            onRemoveAttachment = vm::removeAttachment,
                            onRemoveFile = vm::removeFile,
                        )
                    }
                    if (state.messages.isEmpty() && !state.isStreaming) {
                        EmptyState(
                            modifier = Modifier.weight(1f),
                            onSuggestion = { prompt -> vm.onInputChange(prompt) },
                        )
                    } else {
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
                RecordingOverlay()
            }
        }
    }
}

/** 顶栏三态：多选 / 微信风格 / 默认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    state: ChatUiState,
    wechat: Boolean,
    assistantName: String,
    showConnectionDot: Boolean,
    connectionOnline: Boolean?,
    onOpenDrawer: () -> Unit,
    onToggleTts: () -> Unit,
    onNewConversation: () -> Unit,
    onCloseSelection: () -> Unit,
    onShareSelection: () -> Unit,
    onCopySelection: () -> Unit,
    onDeleteSelection: () -> Unit,
) {
    if (state.selectionMode) {
        SelectionTopBar(
            count = state.selectedIds.size,
            onClose = onCloseSelection,
            onShare = onShareSelection,
            onCopy = onCopySelection,
            onDelete = onDeleteSelection,
        )
    } else if (wechat) {
        val title = assistantName.ifBlank {
            state.messages.firstOrNull { it.role == Role.USER }
                ?.text?.trim()?.take(14)?.takeIf { it.isNotBlank() } ?: "GoClaw"
        }
        CenterAlignedTopAppBar(
            title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                if (showConnectionDot) {
                    ConnectionDot(connectionOnline, Modifier.padding(start = 16.dp))
                }
            },
            actions = {
                IconButton(onClick = onToggleTts) {
                    Icon(
                        if (state.ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        contentDescription = "切换语音播报",
                    )
                }
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.MoreHoriz, contentDescription = "菜单")
                }
            },
        )
    } else {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showConnectionDot) {
                        ConnectionDot(connectionOnline)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("GoClaw Chat", style = MaterialTheme.typography.titleLarge)
                    if (state.messages.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        AiBadge()
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.Menu, contentDescription = "菜单")
                }
            },
            actions = {
                IconButton(onClick = onToggleTts) {
                    Icon(
                        if (state.ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        contentDescription = "切换语音播报",
                    )
                }
                IconButton(onClick = onNewConversation) {
                    Icon(Icons.Filled.Add, contentDescription = "新建对话")
                }
            },
        )
    }
}

/** Connection-status dot: green = online, red = offline, gray = unknown/checking. */
@Composable
private fun ConnectionDot(online: Boolean?, modifier: Modifier = Modifier) {
    val color = when (online) {
        true -> Color(0xFF22C55E)
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

internal fun shareText(context: Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享到"))
}
