package xyz.limo060719.goclaw.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.limo060719.goclaw.data.GoClawSettings
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.util.ImageUtil
import java.io.File
import javax.inject.Inject

@HiltViewModel
class AppSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
) : ViewModel() {
    val settings: StateFlow<GoClawSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, GoClawSettings())

    fun setWechatUi(on: Boolean) = viewModelScope.launch { store.updateWechatUi(on) }
    fun setThemeMode(mode: String) = viewModelScope.launch { store.updateThemeMode(mode) }
    fun setTtsBackend(on: Boolean) = viewModelScope.launch { store.updateTtsBackend(on) }
    fun saveProfile(selfName: String, assistantName: String) =
        viewModelScope.launch { store.updateWechatProfile(selfName, assistantName) }

    fun pickAvatar(self: Boolean, uri: Uri) = viewModelScope.launch {
        ImageUtil.saveAvatar(context, uri, if (self) "self" else "assistant")
            ?.let { store.updateAvatar(self, it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProvider: () -> Unit,
    vm: AppSettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // AI 供应商 入口
            SettingCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.clickable(onClick = onOpenProvider).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AI 供应商", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "后端地址、API 密钥、用户 ID、Agent、模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 外观
            SettingCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp)) {
                    Text("外观", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    val modes = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modes.forEach { (key, label) ->
                            FilterChip(
                                selected = s.themeMode == key,
                                onClick = { vm.setThemeMode(key) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            // 仿微信 UI
            SettingCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("仿微信 UI", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "聊天界面切换为微信样式，侧边栏从右上角「···」打开",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.wechatUi, onCheckedChange = vm::setWechatUi)
                }
            }

            if (s.wechatUi) {
                WechatProfileCard(s, vm)
            }

            // 语音合成
            SettingCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("使用后端 TTS（高音质）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "朗读回复时用后端语音合成（需后端配置 TTS provider），失败自动回退到设备 TTS。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.ttsBackend, onCheckedChange = vm::setTtsBackend)
                }
            }
        }
    }
}

@Composable
private fun SettingCard(color: Color, content: @Composable () -> Unit) {
    Surface(color = color, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun WechatProfileCard(s: GoClawSettings, vm: AppSettingsViewModel) {
    var selfName by remember(s.selfName) { mutableStateOf(s.selfName) }
    var assistantName by remember(s.assistantName) { mutableStateOf(s.assistantName) }

    val selfPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.pickAvatar(self = true, uri = it) }
    }
    val asstPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.pickAvatar(self = false, uri = it) }
    }

    SettingCard(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("微信样式 · 头像与名字", style = MaterialTheme.typography.titleSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AvatarPicker("对方头像", s.assistantAvatar) {
                    asstPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                AvatarPicker("我的头像", s.selfAvatar) {
                    selfPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }

            OutlinedTextField(
                value = assistantName,
                onValueChange = { assistantName = it },
                label = { Text("对方名字（顶部标题）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = selfName,
                onValueChange = { selfName = it },
                label = { Text("我的名字") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.saveProfile(selfName, assistantName) },
                modifier = Modifier.align(Alignment.End),
            ) { Text("保存名字") }
        }
    }
}

@Composable
private fun AvatarPicker(label: String, path: String, onPick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(64.dp).clip(CircleShape).clickable(onClick = onPick),
        ) {
            if (path.isNotBlank()) {
                AsyncImage(
                    model = File(path), contentDescription = label,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Person, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
