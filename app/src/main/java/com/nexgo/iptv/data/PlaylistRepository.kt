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
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "nexgo_prefs")
private val PLAYLIST_URL_KEY = stringPreferencesKey("playlist_url")

class PlaylistRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun getSavedPlaylistUrl(): String? {
        return context.dataStore.data.first()[PLAYLIST_URL_KEY]
    }

    suspend fun savePlaylistUrl(url: String) {
        context.dataStore.edit { it[PLAYLIST_URL_KEY] = url }
    }

    /**
     * Descarga y parsea la lista M3U/M3U8 indicada, leyendo en streaming
     * directamente desde la conexión (sin cargar el archivo completo en un
     * solo String), para soportar listas muy grandes sin quedarse sin memoria.
     */
    suspend fun loadChannels(url: String): List<Channel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} al descargar la lista")
            }
            val body = response.body ?: throw IllegalStateException("Respuesta vacía")
            body.charStream().use { stream ->
                M3UParser.parse(stream)
            }
        }
    }
}
