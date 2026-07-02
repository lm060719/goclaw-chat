package xyz.limo060719.goclaw.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.apiKeySecretStore by preferencesDataStore(name = "goclaw_api_key_secrets")

/**
 * Locally persists the plaintext of API keys created *inside this app* (keyId → raw secret).
 * The backend only returns a key's secret once, at creation — this lets the user view/copy it
 * later. Keys created elsewhere are never stored here (their secret is unknown to us).
 */
@Singleton
class ApiKeySecretStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val secretsKey = stringPreferencesKey("secrets_json")
    private val mapSer = MapSerializer(String.serializer(), String.serializer())

    val secrets: Flow<Map<String, String>> = context.apiKeySecretStore.data.map { p -> decode(p[secretsKey]) }

    suspend fun save(id: String, secret: String) {
        if (id.isBlank() || secret.isBlank()) return
        context.apiKeySecretStore.edit { p ->
            p[secretsKey] = json.encodeToString(mapSer, decode(p[secretsKey]) + (id to secret))
        }
    }

    suspend fun remove(id: String) {
        context.apiKeySecretStore.edit { p ->
            p[secretsKey] = json.encodeToString(mapSer, decode(p[secretsKey]) - id)
        }
    }

    private fun decode(raw: String?): Map<String, String> =
        raw?.let { runCatching { json.decodeFromString(mapSer, it) }.getOrNull() }.orEmpty()
}
