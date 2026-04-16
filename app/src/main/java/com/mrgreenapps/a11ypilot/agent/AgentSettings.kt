package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.dataStore by preferencesDataStore(name = "agent_settings")

object AgentSettings {

    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_MAX_STEPS = intPreferencesKey("max_steps")
    private val KEY_MCP_ENABLED = booleanPreferencesKey("mcp_enabled")
    private val KEY_MCP_PORT = intPreferencesKey("mcp_port")

    private const val SECRETS_FILE = "agent_secrets"
    private const val SECRET_API_KEY = "anthropic_api_key"
    private const val SECRET_MCP_TOKEN = "mcp_bearer_token"

    const val DEFAULT_MODEL = "claude-sonnet-4-5"
    const val DEFAULT_MAX_STEPS = 25
    const val DEFAULT_MCP_PORT = 8765

    fun model(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[KEY_MODEL] ?: DEFAULT_MODEL }

    fun maxSteps(ctx: Context): Flow<Int> =
        ctx.dataStore.data.map { it[KEY_MAX_STEPS] ?: DEFAULT_MAX_STEPS }

    fun mcpEnabled(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[KEY_MCP_ENABLED] ?: false }

    fun mcpPort(ctx: Context): Flow<Int> =
        ctx.dataStore.data.map { it[KEY_MCP_PORT] ?: DEFAULT_MCP_PORT }

    suspend fun setModel(ctx: Context, value: String) =
        ctx.dataStore.edit { it[KEY_MODEL] = value }

    suspend fun setMaxSteps(ctx: Context, value: Int) =
        ctx.dataStore.edit { it[KEY_MAX_STEPS] = value.coerceIn(1, 100) }

    suspend fun setMcpEnabled(ctx: Context, value: Boolean) =
        ctx.dataStore.edit { it[KEY_MCP_ENABLED] = value }

    suspend fun setMcpPort(ctx: Context, value: Int) =
        ctx.dataStore.edit { it[KEY_MCP_PORT] = value.coerceIn(1024, 65535) }

    suspend fun snapshot(ctx: Context): Snapshot {
        val prefs = ctx.dataStore.data.first()
        return Snapshot(
            model = prefs[KEY_MODEL] ?: DEFAULT_MODEL,
            maxSteps = prefs[KEY_MAX_STEPS] ?: DEFAULT_MAX_STEPS,
            mcpEnabled = prefs[KEY_MCP_ENABLED] ?: false,
            mcpPort = prefs[KEY_MCP_PORT] ?: DEFAULT_MCP_PORT,
            apiKey = readSecret(ctx, SECRET_API_KEY),
            mcpToken = readSecret(ctx, SECRET_MCP_TOKEN)
        )
    }

    data class Snapshot(
        val model: String,
        val maxSteps: Int,
        val mcpEnabled: Boolean,
        val mcpPort: Int,
        val apiKey: String,
        val mcpToken: String
    )

    fun secrets(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            SECRETS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun readSecret(ctx: Context, name: String): String =
        secrets(ctx).getString(name, "").orEmpty()

    fun writeSecret(ctx: Context, name: String, value: String) {
        secrets(ctx).edit().putString(name, value).apply()
    }

    fun apiKey(ctx: Context): String = readSecret(ctx, SECRET_API_KEY)
    fun setApiKey(ctx: Context, value: String) = writeSecret(ctx, SECRET_API_KEY, value)

    fun mcpToken(ctx: Context): String = readSecret(ctx, SECRET_MCP_TOKEN)
    fun setMcpToken(ctx: Context, value: String) = writeSecret(ctx, SECRET_MCP_TOKEN, value)

    fun ensureMcpToken(ctx: Context): String {
        val existing = mcpToken(ctx)
        if (existing.isNotEmpty()) return existing
        val random = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = android.util.Base64.encodeToString(
            random,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        setMcpToken(ctx, token)
        return token
    }

    @Suppress("UNUSED_PARAMETER")
    private fun unused(p: Preferences) {} // keep import
}
