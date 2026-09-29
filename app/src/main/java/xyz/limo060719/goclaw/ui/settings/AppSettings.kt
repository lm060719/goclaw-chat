package xyz.limo060719.goclaw.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.app.Activity
import xyz.limo060719.goclaw.R
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
import xyz.limo060719.goclaw.work.ApprovalWorkScheduler
import xyz.limo060719.goclaw.util.LocaleManager
import java.io.File
import javax.inject.Inject

@HiltViewModel
class AppSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val localeManager: LocaleManager,
) : ViewModel() {
    val settings: StateFlow<GoClawSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, GoClawSettings())

    /** Current app-language selection ("system" / "zh" / "en"). */
    val language: String get() = localeManager.language

    /** Persists the language; caller must recreate the Activity for it to take effect. */
    fun setLanguage(code: String) { localeManager.language = code }

    fun setWechatUi(on: Boolean) = viewModelScope.launch { store.updateWechatUi(on) }
    fun setThemeMode(mode: String) = viewModelScope.launch { store.updateThemeMode(mode) }
    fun setTtsBackend(on: Boolean) = viewModelScope.launch { store.updateTtsBackend(on) }
    fun setShowConnectionStatus(on: Boolean) = viewModelScope.launch { store.updateShowConnectionStatus(on) }
    fun setEnterToSend(on: Boolean) = viewModelScope.launch { store.updateEnterToSend(on) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { store.updateDynamicColor(on) }

    /** Persists the approval-notification preference and (un)schedules the background poll. */
    fun setApprovalNotifications(on: Boolean) = viewModelScope.launch {
        store.updateApprovalNotifications(on)
        if (on) ApprovalWorkScheduler.schedule(context) else ApprovalWorkScheduler.cancel(context)
    }
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
    val context = LocalContext.current

    // On Android 13+ the background poll can only notify after POST_NOTIFICATIONS is granted, so
    // the toggle turns on only once permission is secured.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.setApprovalNotifications(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                        Text(stringResource(R.string.settings_ai_provider), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_ai_provider_desc),
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
                    Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    val modes = listOf(
                        "system" to stringResource(R.string.settings_follow_system),
                        "light" to stringResource(R.string.settings_theme_light),
                        "dark" to stringResource(R.string.settings_theme_dark),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modes.forEach { (key, label) ->
                            FilterChip(
                                selected = s.themeMode == key,
                                onClick = { vm.setThemeMode(key) },
                                label = { Text(label) },
                            )
                        }
                    }
                    // Wallpaper colors exist only on Android 12+; below that the switch is disabled.
                    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_dynamic_color), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(
                                    if (dynamicSupported) R.string.settings_dynamic_color_desc
                                    else R.string.settings_dynamic_color_unsupported
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = s.dynamicColor && dynamicSupported,
                            onCheckedChange = vm::setDynamicColor,
                            enabled = dynamicSupported,
                        )
                    }
                }
            }

            // 回车发送
            SettingCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_enter_send), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_enter_send_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.enterToSend, onCheckedChange = vm::setEnterToSend)
                }
            }

            // 语言
            SettingCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    val current = vm.language
                    val langs = listOf(
                        LocaleManager.SYSTEM to stringResource(R.string.settings_follow_system),
                        LocaleManager.ZH to stringResource(R.string.settings_language_zh),
                        LocaleManager.EN to stringResource(R.string.settings_language_en),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        langs.forEach { (code, label) ->
                            FilterChip(
                                selected = current == code,
                                onClick = {
                                    if (current != code) {
                                        vm.setLanguage(code)
                                        // Recreate so the new locale applies to all resources.
                                        (context as? Activity)?.recreate()
                                    }
                                },
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
                        Text(stringResource(R.string.settings_wechat_ui), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_wechat_ui_desc),
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
                        Text(stringResource(R.string.settings_backend_tts), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_backend_tts_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.ttsBackend, onCheckedChange = vm::setTtsBackend)
                }
            }

            // 连接状态显示
            SettingCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_conn_status), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_conn_status_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.showConnectionStatus, onCheckedChange = vm::setShowConnectionStatus)
                }
            }

            // 审批通知
            SettingCard(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_approval_notif), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_approval_notif_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = s.approvalNotifications,
                        onCheckedChange = { on ->
                            when {
                                !on -> vm.setApprovalNotifications(false)
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED ->
                                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else -> vm.setApprovalNotifications(true)
                            }
                        },
                    )
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
            Text(stringResource(R.string.settings_wechat_profile), style = MaterialTheme.typography.titleSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AvatarPicker(stringResource(R.string.settings_avatar_assistant), s.assistantAvatar) {
                    asstPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                AvatarPicker(stringResource(R.string.settings_avatar_self), s.selfAvatar) {
                    selfPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }

            OutlinedTextField(
                value = assistantName,
                onValueChange = { assistantName = it },
                label = { Text(stringResource(R.string.settings_name_assistant)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = selfName,
                onValueChange = { selfName = it },
                label = { Text(stringResource(R.string.settings_name_self)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.saveProfile(selfName, assistantName) },
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.settings_save_names)) }
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
