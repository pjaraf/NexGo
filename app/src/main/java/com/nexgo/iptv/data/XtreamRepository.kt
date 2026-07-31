package com.nexgo.iptv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexgo.iptv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

private val Context.xtreamDataStore by preferencesDataStore(name = "nexgo_xtream_prefs")
private val SERVER_KEY = stringPreferencesKey("xtream_server")
private val USER_KEY = stringPreferencesKey("xtream_user")
private val PASS_KEY = stringPreferencesKey("xtream_pass")

data class XtreamCredentials(
    val serverUrl: String,
    val username: String,
    val password: String
)

/**
 * Cliente para paneles IPTV tipo "Xtream Codes": conecta con usuario + contraseña +
 * URL del servidor y trae la lista de canales en vivo vía su API JSON
 * (player_api.php), lo cual es mucho más rápido que descargar y parsear un M3U
 * completo cada vez.
 */
class XtreamRepository(private val context: Context) {

    private val client = OkHttpClient()

    suspend fun getSavedCredentials(): XtreamCredentials? {
        val prefs = context.xtreamDataStore.data.first()
        val server = prefs[SERVER_KEY]
        val user = prefs[USER_KEY]
        val pass = prefs[PASS_KEY]
        return if (!server.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank()) {
            XtreamCredentials(server, user, pass)
        } else null
    }

    suspend fun saveCredentials(credentials: XtreamCredentials) {
        context.xtreamDataStore.edit {
            it[SERVER_KEY] = credentials.serverUrl
            it[USER_KEY] = credentials.username
            it[PASS_KEY] = credentials.password
        }
    }

    suspend fun clearCredentials() {
        context.xtreamDataStore.edit { it.clear() }
    }

    private fun normalizeServerUrl(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url.trimEnd('/')
    }

    suspend fun loadChannels(credentials: XtreamCredentials): List<Channel> = withContext(Dispatchers.IO) {
        val base = normalizeServerUrl(credentials.serverUrl)
        val user = credentials.username.trim()
        val pass = credentials.password.trim()

        // 1) Validar servidor/credenciales
        val authUrl = "$base/player_api.php?username=$user&password=$pass"
        val authBody = fetch(authUrl)
        if (authBody.isBlank() || !authBody.trimStart().startsWith("{")) {
            throw IllegalStateException("Servidor o credenciales inválidas")
        }

        // 2) Categorías, para agrupar los canales igual que el "group-title" de un M3U
        val categoriesUrl = "$base/player_api.php?username=$user&password=$pass&action=get_live_categories"
        val categoryNames = mutableMapOf<String, String>()
        try {
            val categoriesJson = JSONArray(fetch(categoriesUrl))
            for (i in 0 until categoriesJson.length()) {
                val cat = categoriesJson.getJSONObject(i)
                categoryNames[cat.optString("category_id")] = cat.optString("category_name", "General")
            }
        } catch (_: Exception) {
            // Si el panel no expone categorías, seguimos sin agrupar
        }

        // 3) Canales en vivo
        val streamsUrl = "$base/player_api.php?username=$user&password=$pass&action=get_live_streams"
        val streamsJson = JSONArray(fetch(streamsUrl))
        val channels = mutableListOf<Channel>()
        for (i in 0 until streamsJson.length()) {
            val item = streamsJson.getJSONObject(i)
            val streamId = item.optString("stream_id")
            if (streamId.isBlank()) continue
            val categoryId = item.optString("category_id")
            val name = item.optString("name", "Canal $streamId")
            val logo = item.optString("stream_icon").takeIf { it.isNotBlank() }
            val group = categoryNames[categoryId] ?: "General"
            val streamUrl = "$base/live/$user/$pass/$streamId.m3u8"

            channels += Channel(
                id = streamId,
                name = name,
                group = group,
                logoUrl = logo,
                streamUrl = streamUrl
            )
        }

        if (channels.isEmpty()) {
            throw IllegalStateException("No se encontraron canales para esta cuenta")
        }

        channels
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }
}
