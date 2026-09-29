package xyz.limo060719.goclaw.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.res.stringResource
import xyz.limo060719.goclaw.R
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.limo060719.goclaw.domain.model.Attachment
import xyz.limo060719.goclaw.ui.chat.PickedFile

/** 底部输入栏：图片 / 文件 / 按住录音 / 文本输入 / 发送（流式时变为停止）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InputBar(
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
    onVoiceCancel: () -> Unit,
    onVoiceCancelArmed: (Boolean) -> Unit,
) {
    val cancelDistance = with(LocalDensity.current) { 72.dp.toPx() }
    val canSend = input.isNotBlank() || attachmentCount > 0
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxWidth().padding(10.dp).navigationBarsPadding().imePadding()) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    Modifier.padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPickImage) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = stringResource(R.string.chat_add_image),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onPickFile) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = stringResource(R.string.chat_add_file),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Hold to record a voice message; release to send, slide up (then release) to cancel.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(canRecord) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    if (!canRecord) {
                                        onRequestRecord()
                                        return@awaitEachGesture
                                    }
                                    onVoiceStart()
                                    var armed = false
                                    try {
                                        while (true) {
                                            val change = awaitPointerEvent().changes
                                                .firstOrNull { it.id == down.id } ?: break
                                            change.consume()
                                            if (!change.pressed) break
                                            val nowArmed = down.position.y - change.position.y > cancelDistance
                                            if (nowArmed != armed) { armed = nowArmed; onVoiceCancelArmed(armed) }
                                        }
                                    } catch (e: CancellationException) {
                                        // Gesture torn down mid-press (e.g. recomposition) → never send by accident.
                                        onVoiceCancel()
                                        throw e
                                    }
                                    if (armed) onVoiceCancel() else onVoiceEnd()
                                }
                            },
                    ) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = stringResource(R.string.chat_hold_to_talk),
                            tint = if (recording) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextField(
                        value = input,
                        onValueChange = onInputChange,
                        placeholder = {
                            Text(stringResource(if (hasMessages) R.string.chat_input_continue else R.string.chat_input_hint))
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
                            contentDescription = stringResource(if (isStreaming) R.string.chat_stop else R.string.chat_send),
                        )
                    }
                }
            }
        }
    }
}

/** 输入栏上方的 Agent 快速切换；有消息后锁定不可切换。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSwitcher(
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

/** 输入区上方待发送的图片 / 文件预览条。 */
@Composable
internal fun AttachmentStrip(
    attachments: List<Attachment>,
    files: List<PickedFile>,
    onRemoveAttachment: (Attachment) -> Unit,
    onRemoveFile: (PickedFile) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { att ->
            Box {
                AsyncImage(
                    model = att.previewUri,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.extraSmall),
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    IconButton(onClick = { onRemoveAttachment(att) }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_remove), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        files.forEach { f ->
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    IconButton(onClick = { onRemoveFile(f) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_remove), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/** 按住录音时的全屏遮罩提示：计时 + 上滑取消状态。 */
@Composable
internal fun RecordingOverlay(cancelArmed: Boolean) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); seconds++ } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    if (cancelArmed) Icons.Filled.Close else Icons.Filled.Mic, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "%d:%02d".format(seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(if (cancelArmed) R.string.chat_release_to_cancel else R.string.chat_swipe_up_cancel),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cancelArmed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
