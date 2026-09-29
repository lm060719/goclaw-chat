package xyz.limo060719.goclaw.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import xyz.limo060719.goclaw.R
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
    val copiedMsg = stringResource(R.string.common_copied)
    val retryLabel = stringResource(R.string.chat_retry)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    var detailMessage by remember { mutableStateOf<UiMessage?>(null) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val pendingDeletes by vm.pendingDeletes.collectAsStateWithLifecycle()
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

    // Follow the bottom only while the user hasn't scrolled away: a drag turns following off,
    // and settling back at the bottom (by drag, fling or the jump button) turns it on again.
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) autoFollow = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) -> if (!scrolling && !canForward) autoFollow = true }
    }
    // Int.MAX_VALUE offset is clamped by the list → lands on the very end, even inside a long last bubble.
    suspend fun jumpToEnd(animate: Boolean) {
        val last = state.messages.lastIndex.takeIf { it >= 0 } ?: return
        if (animate) listState.animateScrollToItem(last, Int.MAX_VALUE)
        else listState.scrollToItem(last, Int.MAX_VALUE)
    }
    var lastCount by remember { mutableStateOf(0) }
    LaunchedEffect(state.messages.size) {
        val added = state.messages.size - lastCount
        lastCount = state.messages.size
        // Own message sent, or a whole conversation loaded → always go to the bottom.
        if (added != 1 || state.messages.lastOrNull()?.role == Role.USER) autoFollow = true
        if (autoFollow) jumpToEnd(animate = added == 1)
    }
    // Streaming tokens: jump instantly instead of restarting a scroll animation per token,
    // which otherwise fights itself and drops frames.
    LaunchedEffect(state.messages.lastOrNull()?.text) {
        if (state.isStreaming && autoFollow) jumpToEnd(animate = false)
    }
    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        vm.clearError()
        // A failed turn is retried from the inline row under the last message; this is just the reason.
        snackbar.showSnackbar(message = err, duration = SnackbarDuration.Short)
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
                pendingDeletes = pendingDeletes,
                onClose = { scope.launch { drawerState.close() } },
                onNewChat = { scope.launch { drawerState.close() }; vm.newConversation() },
                onOpenConversation = { id ->
                    scope.launch { drawerState.close() }; vm.openConversation(id)
                },
                onDeleteConversation = vm::deleteConversation,
                onUndoDelete = vm::undoDeleteConversation,
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
                            scope.launch { snackbar.showSnackbar(copiedMsg) }
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
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(state.messages, key = { it.id }) { msg ->
                                val isTextMessage = msg.role != Role.TOOL && msg.text.isNotBlank()
                                // Regenerate only makes sense for the latest reply, and not mid-stream.
                                val canRegenerate = !state.isStreaming &&
                                    msg.role == Role.ASSISTANT &&
                                    msg.id == state.messages.lastOrNull()?.id
                                // Branch: fork the server session from any text message (not mid-stream).
                                val canBranch = !state.isStreaming && isTextMessage
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
                                        scope.launch { snackbar.showSnackbar(copiedMsg) }
                                    },
                                    onShare = { shareText(context, vm.textOf(setOf(msg.id))) },
                                    onDelete = { pendingDelete = setOf(msg.id) },
                                    onDownloadFile = { vm.downloadAndSaveFile(it) },
                                    onRegenerate = if (canRegenerate) vm::regenerate else null,
                                    onBranch = if (canBranch) ({ vm.branchFrom(msg.id) }) else null,
                                )
                            }
                            if (state.isStreaming && state.messages.lastOrNull()?.streaming != true) {
                                item { TypingDots() }
                            }
                            if (state.turnFailed && !state.isStreaming) {
                                item(key = "turn_failed") {
                                    TurnFailedRow(retryLabel = retryLabel, onRetry = vm::regenerate)
                                }
                            }
                        }
                        // Shown whenever the user has scrolled away from the latest content.
                        // Fully-qualified: the enclosing ColumnScope overload can't be used from this Box.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = listState.canScrollForward && !autoFollow,
                            enter = fadeIn(), exit = fadeOut(),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        ) {
                            SmallFloatingActionButton(
                                onClick = { autoFollow = true; scope.launch { jumpToEnd(animate = true) } },
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.chat_scroll_to_bottom),
                                )
                            }
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
                        contentDescription = stringResource(R.string.chat_toggle_tts),
                    )
                }
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.common_menu))
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
                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.common_menu))
                }
            },
            actions = {
                IconButton(onClick = onToggleTts) {
                    Icon(
                        if (state.ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        contentDescription = stringResource(R.string.chat_toggle_tts),
                    )
                }
                IconButton(onClick = onNewConversation) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_new_conversation))
                }
            },
        )
    }
}

/** Inline marker under the last message when the latest turn failed, with a one-tap retry. */
@Composable
private fun TurnFailedRow(retryLabel: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.chat_turn_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) { Text(retryLabel) }
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
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.chat_share_to)))
}
