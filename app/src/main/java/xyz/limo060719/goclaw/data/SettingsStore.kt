package xyz.limo060719.goclaw.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "goclaw_settings")

data class GoClawSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val userId: String = "",
    val agent: String = "",
    val model: String = "",
    /** Agent keys the user saved for quick switching. */
    val savedAgents: List<String> = emptyList(),
    /** Agent picked for the most recent new conversation; default for the next new chat. */
    val lastAgent: String = "",
    /** Render the chat in a WeChat-like style. */
    val wechatUi: Boolean = false,
    /** "system" | "light" | "dark" */
    val themeMode: String = "system",
    /** WeChat-mode display customization. */
    val selfName: String = "",
    val assistantName: String = "",
    val selfAvatar: String = "",       // local file path
    val assistantAvatar: String = "",  // local file path
    /** Use the backend `/v1/tts/synthesize` for replies instead of on-device TTS. */
    val ttsBackend: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
}

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val USER_ID = stringPreferencesKey("user_id")
        val AGENT = stringPreferencesKey("agent")
        val MODEL = stringPreferencesKey("model")
        val SAVED_AGENTS = stringPreferencesKey("saved_agents")
        val LAST_AGENT = stringPreferencesKey("last_agent")
        val WECHAT_UI = booleanPreferencesKey("wechat_ui")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SELF_NAME = stringPreferencesKey("self_name")
        val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        val SELF_AVATAR = stringPreferencesKey("self_avatar")
        val ASSISTANT_AVATAR = stringPreferencesKey("assistant_avatar")
        val TTS_BACKEND = booleanPreferencesKey("tts_backend")
    }

    val settings: Flow<GoClawSettings> = context.dataStore.data.map { p ->
        GoClawSettings(
            baseUrl = p[Keys.BASE_URL].orEmpty(),
            apiKey = p[Keys.API_KEY].orEmpty(),
            userId = p[Keys.USER_ID].orEmpty(),
            agent = p[Keys.AGENT].orEmpty(),
            model = p[Keys.MODEL].orEmpty(),
            savedAgents = p[Keys.SAVED_AGENTS].orEmpty().split('\n').filter { it.isNotBlank() },
            lastAgent = p[Keys.LAST_AGENT].orEmpty(),
            wechatUi = p[Keys.WECHAT_UI] ?: false,
            themeMode = p[Keys.THEME_MODE] ?: "system",
            selfName = p[Keys.SELF_NAME].orEmpty(),
            assistantName = p[Keys.ASSISTANT_NAME].orEmpty(),
            selfAvatar = p[Keys.SELF_AVATAR].orEmpty(),
            assistantAvatar = p[Keys.ASSISTANT_AVATAR].orEmpty(),
            ttsBackend = p[Keys.TTS_BACKEND] ?: false,
        )
    }

    suspend fun current(): GoClawSettings = settings.first()

    suspend fun update(s: GoClawSettings) {
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = s.baseUrl.trim().trimEnd('/')
            p[Keys.API_KEY] = s.apiKey.trim()
            p[Keys.USER_ID] = s.userId.trim()
            p[Keys.AGENT] = s.agent.trim()
            p[Keys.MODEL] = s.model.trim()
        }
    }

    suspend fun updateWechatUi(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.WECHAT_UI] = enabled }
    }

    suspend fun updateThemeMode(mode: String) {
        context.dataStore.edit { p -> p[Keys.THEME_MODE] = mode }
    }

    suspend fun updateTtsBackend(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.TTS_BACKEND] = enabled }
    }

    suspend fun updateWechatProfile(
        selfName: String,
        assistantName: String,
    ) {
        context.dataStore.edit { p ->
            p[Keys.SELF_NAME] = selfName.trim()
            p[Keys.ASSISTANT_NAME] = assistantName.trim()
        }
    }

    suspend fun updateAvatar(self: Boolean, path: String) {
        context.dataStore.edit { p ->
            if (self) p[Keys.SELF_AVATAR] = path else p[Keys.ASSISTANT_AVATAR] = path
        }
    }

    /** Remember the agent chosen for the latest new conversation. */
    suspend fun updateLastAgent(key: String) {
        context.dataStore.edit { p -> p[Keys.LAST_AGENT] = key.trim() }
    }

    /** Persist the saved-agents list (independent of the main Save button). */
    suspend fun updateSavedAgents(agents: List<String>) {
        context.dataStore.edit { p ->
            p[Keys.SAVED_AGENTS] = agents.map { it.trim() }.filter { it.isNotBlank() }
                .distinct().joinToString("\n")
        }
    }
}
