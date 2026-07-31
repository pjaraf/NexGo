package com.nexgo.iptv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexgo.iptv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
 * URL del servidor, y guarda los canales directo en la base de datos (SQLite)
 * en bloques pequeños a medida que los va leyendo del JSON, en vez de
 * acumular una lista completa en memoria RAM. Así soporta paneles con
 * decenas o cientos de miles de canales sin quedarse sin memoria.
 */
class XtreamRepository(private val context: Context, private val db: ChannelDatabase) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val batchSize = 300

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

    /** Devuelve la cantidad total de canales cargados. */
    suspend fun loadChannels(credentials: XtreamCredentials): Int = withContext(Dispatchers.IO) {
        val base = normalizeServerUrl(credentials.serverUrl)
        val user = credentials.username.trim()
        val pass = credentials.password.trim()

        // 1) Validar servidor/credenciales con una petición liviana
        val authUrl = "$base/player_api.php?username=$user&password=$pass"
        val authRequest = Request.Builder().url(authUrl).build()
        client.newCall(authRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Servidor o credenciales inválidas (HTTP ${response.code})")
            }
        }

        // 2) Categorías (para agrupar), en streaming
        val categoryNames = mutableMapOf<String, String>()
        try {
            val categoriesUrl = "$base/player_api.php?username=$user&password=$pass&action=get_live_categories"
            val request = Request.Builder().url(categoriesUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.charStream()?.use { stream ->
                        JsonReader(stream).use { reader ->
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginObject()
                                var id: String? = null
                                var name: String? = null
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "category_id" -> id = reader.nextFlexibleString()
                                        "category_name" -> name = reader.nextFlexibleString()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (id != null) categoryNames[id] = name ?: "General"
                            }
                            reader.endArray()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Si el panel no expone categorías, seguimos sin agrupar
        }

        // 3) Canales en vivo, guardados directo en la base de datos en bloques
        db.clear()
        val streamsUrl = "$base/player_api.php?username=$user&password=$pass&action=get_live_streams"
        val streamsRequest = Request.Builder().url(streamsUrl).build()
        var totalCount = 0
        val batch = mutableListOf<Channel>()

        client.newCall(streamsRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} al pedir los canales")
            }
            val body = response.body ?: throw IllegalStateException("Respuesta vacía")
            body.charStream().use { stream ->
                JsonReader(stream).use { reader ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var streamId: String? = null
                        var name: String? = null
                        var categoryId: String? = null
                        var logo: String? = null
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "stream_id" -> streamId = reader.nextFlexibleString()
                                "name" -> name = reader.nextFlexibleString()
                                "category_id" -> categoryId = reader.nextFlexibleString()
                                "stream_icon" -> logo = reader.nextFlexibleString()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (!streamId.isNullOrBlank()) {
                            totalCount += 1
                            batch += Channel(
                                id = streamId,
                                name = name ?: "Canal $streamId",
                                group = categoryNames[categoryId] ?: "General",
                                logoUrl = logo,
                                streamUrl = "$base/live/$user/$pass/$streamId.m3u8"
                            )
                            if (batch.size >= batchSize) {
                                db.insertBatch(batch.toList())
                                batch.clear()
                            }
                        }
                    }
                    reader.endArray()
                }
            }
        }

        if (batch.isNotEmpty()) {
            db.insertBatch(batch.toList())
        }

        if (totalCount == 0) {
            throw IllegalStateException("No se encontraron canales para esta cuenta")
        }

        totalCount
    }
}

/**
 * Lee el siguiente valor como String sin importar si el panel lo mandó como
 * texto, número o null (algunos paneles Xtream son inconsistentes con esto).
 */
private fun JsonReader.nextFlexibleString(): String? {
    return when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        else -> nextString()
    }
}
