package xyz.limo060719.goclaw

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import xyz.limo060719.goclaw.data.GoClawSettings
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.work.ApprovalNotifier
import xyz.limo060719.goclaw.work.ReplyNotifier
import xyz.limo060719.goclaw.ui.chat.ChatScreen
import xyz.limo060719.goclaw.ui.chat.SharedContent
import xyz.limo060719.goclaw.ui.settings.AiProviderScreen
import xyz.limo060719.goclaw.ui.settings.ApprovalScreen
import xyz.limo060719.goclaw.ui.settings.ApiKeysScreen
import xyz.limo060719.goclaw.ui.settings.BackendSkillsScreen
import xyz.limo060719.goclaw.ui.settings.DevicePairingScreen
import xyz.limo060719.goclaw.ui.settings.ExtraFeaturesScreen
import xyz.limo060719.goclaw.ui.settings.HeartbeatScreen
import xyz.limo060719.goclaw.ui.settings.LogsScreen
import xyz.limo060719.goclaw.ui.settings.SessionsScreen
import xyz.limo060719.goclaw.ui.settings.SettingsScreen
import xyz.limo060719.goclaw.ui.settings.SkillsScreen
import xyz.limo060719.goclaw.ui.settings.TracesScreen
import xyz.limo060719.goclaw.ui.settings.UsageScreen
import xyz.limo060719.goclaw.ui.theme.GoClawTheme
import xyz.limo060719.goclaw.util.LocaleManager
import javax.inject.Inject

/**
 * Pops the back stack only if the current destination is still RESUMED. A pop immediately moves
 * the outgoing screen out of RESUMED, so a rapid double-tap on the back arrow can't pop twice —
 * the second pop used to also remove the root "chat" destination, leaving an empty NavHost
 * (black screen).
 */
private fun NavHostController.safePopBackStack() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) popBackStack()
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsStore: SettingsStore

    /** Route a notification tap asked us to open; consumed once by the NavHost. */
    private val pendingRoute = MutableStateFlow<String?>(null)
    /** Conversation a reply notification asked us to open; consumed once by the chat screen. */
    private val pendingConversation = MutableStateFlow<String?>(null)
    /** Content shared in from another app; consumed once by the chat screen. */
    private val pendingShare = MutableStateFlow<SharedContent?>(null)

    // Apply the in-app language before the Activity's resources are created.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onStart() {
        super.onStart()
        ReplyNotifier.appInForeground = true
    }

    override fun onStop() {
        ReplyNotifier.appInForeground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        intent ?: return
        pendingRoute.value = intent.getStringExtra(ApprovalNotifier.EXTRA_OPEN_ROUTE)
        intent.getStringExtra(ReplyNotifier.EXTRA_OPEN_CONVERSATION)?.let { pendingConversation.value = it }
        SharedContent.from(intent) { contentResolver.getType(it) }?.let { pendingShare.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前安装:启动窗口以 Splash 主题(固定浅色背景)绘制,
        // 内容首帧就绪后自动淡出并切到 Theme.GoClawChat。
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 冷启动时同步读一次已保存的设置,让第一帧就用用户选择的明暗主题。
        // 否则 DataStore 异步读出真实值之前,会先用占位值 themeMode="system" 退回系统深浅,
        // 在「系统深色 + 软件浅色」时先渲染成黑再切白,造成进入软件时的黑白闪烁。
        val bootSettings = runBlocking { settingsStore.current() }
        // Only on a real launch: after a rotation/recreate the same intent would be replayed.
        if (savedInstanceState == null) readIntent(intent)
        setContent {
            val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = bootSettings)
            val dark = when (settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // 系统栏图标明暗跟随实际主题,避免浅色背景上出现浅色图标(反之亦然)看不清。
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            GoClawTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    // A notification tap (or any deep link) drops a route here; navigate once.
                    val route by pendingRoute.collectAsStateWithLifecycle()
                    LaunchedEffect(route) {
                        route?.let { nav.navigate(it); pendingRoute.value = null }
                    }
                    // Opening a conversation / receiving a share happens on the chat screen.
                    val openConversation by pendingConversation.collectAsStateWithLifecycle()
                    val share by pendingShare.collectAsStateWithLifecycle()
                    LaunchedEffect(openConversation, share) {
                        if (openConversation != null || share != null) nav.popBackStack("chat", inclusive = false)
                    }
                    NavHost(navController = nav, startDestination = "chat") {
                        composable("chat") {
                            ChatScreen(
                                onOpenSettings = { nav.navigate("settings") },
                                onOpenExtras = { nav.navigate("extras") },
                                openConversationId = openConversation,
                                onConversationOpened = { pendingConversation.value = null },
                                share = share,
                                onShareConsumed = { pendingShare.value = null },
                                onOpenProvider = { nav.navigate("ai_provider") },
                            )
                        }
                        composable("extras") {
                            ExtraFeaturesScreen(
                                onBack = { nav.safePopBackStack() },
                                onOpenSkills = { nav.navigate("skills") },
                                onOpenApprovals = { nav.navigate("approvals") },
                                onOpenSessions = { nav.navigate("sessions") },
                                onOpenUsage = { nav.navigate("usage") },
                                onOpenTraces = { nav.navigate("traces") },
                                onOpenBackendSkills = { nav.navigate("backend_skills") },
                                onOpenPairing = { nav.navigate("pairing") },
                                onOpenLogs = { nav.navigate("logs") },
                                onOpenHeartbeat = { nav.navigate("heartbeat") },
                                onOpenApiKeys = { nav.navigate("api_keys") },
                            )
                        }
                        composable("approvals") {
                            ApprovalScreen(onBack = { nav.safePopBackStack() })
                        }
                        composable("sessions") {
                            SessionsScreen(onBack = { nav.safePopBackStack() })
                        }
                        composable("usage") {
                            UsageScreen(onBack = { nav.safePopBackStack() })
                        }
                        composable("traces") {
                            TracesScreen(onBack = { nav.safePopBackStack() })
                        }
                        composable("backend_skills") {
                            BackendSkillsScreen(onBack = { nav.safePopBackStack() })
                        }
                        composable("pairing") {
                            DevicePairingScreen(onBack = { nav.safePopBackStack() })
                        }
                        composable("logs") {
                            LogsScreen(onBack = { nav.safePopBackStack() })
                        }
                        composable("heartbeat") {
                            HeartbeatScreen(onBack = { nav.safePopBackStack() })
                        }
                        composable("api_keys") {
                            ApiKeysScreen(onBack = { nav.safePopBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { nav.safePopBackStack() },
                                onOpenProvider = { nav.navigate("ai_provider") },
                            )
                        }
                        composable("ai_provider") {
                            AiProviderScreen(onBack = { nav.safePopBackStack() })
                        }
                        composable("skills") {
                            SkillsScreen(onBack = { nav.safePopBackStack() })
                        }
                    }
                }
            }
        }
    }
}
