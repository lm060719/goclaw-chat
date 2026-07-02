package xyz.limo060719.goclaw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import xyz.limo060719.goclaw.data.GoClawSettings
import xyz.limo060719.goclaw.data.SettingsStore
import xyz.limo060719.goclaw.ui.chat.ChatScreen
import xyz.limo060719.goclaw.ui.settings.AiProviderScreen
import xyz.limo060719.goclaw.ui.settings.ApprovalScreen
import xyz.limo060719.goclaw.ui.settings.BackendSkillsScreen
import xyz.limo060719.goclaw.ui.settings.DevicePairingScreen
import xyz.limo060719.goclaw.ui.settings.ExtraFeaturesScreen
import xyz.limo060719.goclaw.ui.settings.SessionsScreen
import xyz.limo060719.goclaw.ui.settings.SettingsScreen
import xyz.limo060719.goclaw.ui.settings.SkillsScreen
import xyz.limo060719.goclaw.ui.settings.TracesScreen
import xyz.limo060719.goclaw.ui.settings.UsageScreen
import xyz.limo060719.goclaw.ui.theme.GoClawTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = GoClawSettings())
            val dark = when (settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            GoClawTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "chat") {
                        composable("chat") {
                            ChatScreen(
                                onOpenSettings = { nav.navigate("settings") },
                                onOpenExtras = { nav.navigate("extras") },
                            )
                        }
                        composable("extras") {
                            ExtraFeaturesScreen(
                                onBack = { nav.popBackStack() },
                                onOpenSkills = { nav.navigate("skills") },
                                onOpenApprovals = { nav.navigate("approvals") },
                                onOpenSessions = { nav.navigate("sessions") },
                                onOpenUsage = { nav.navigate("usage") },
                                onOpenTraces = { nav.navigate("traces") },
                                onOpenBackendSkills = { nav.navigate("backend_skills") },
                                onOpenPairing = { nav.navigate("pairing") },
                            )
                        }
                        composable("approvals") {
                            ApprovalScreen(onBack = { nav.popBackStack() })
                        }
                        composable("sessions") {
                            SessionsScreen(onBack = { nav.popBackStack() })
                        }
                        composable("usage") {
                            UsageScreen(onBack = { nav.popBackStack() })
                        }
                        composable("traces") {
                            TracesScreen(onBack = { nav.popBackStack() })
                        }
                        composable("backend_skills") {
                            BackendSkillsScreen(onBack = { nav.popBackStack() })
                        }
                        composable("pairing") {
                            DevicePairingScreen(onBack = { nav.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { nav.popBackStack() },
                                onOpenProvider = { nav.navigate("ai_provider") },
                            )
                        }
                        composable("ai_provider") {
                            AiProviderScreen(onBack = { nav.popBackStack() })
                        }
                        composable("skills") {
                            SkillsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
