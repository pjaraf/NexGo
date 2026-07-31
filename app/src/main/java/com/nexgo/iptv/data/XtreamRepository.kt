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

data class XtreamCategory(
    val id: String,
    val name: String
)

/**
 * Cliente para paneles IPTV tipo "Xtream Codes": conecta con usuario + contraseña +
 * URL del servidor.
 *
 * IMPORTANTE (arreglo de fondo): en vez de descargar TODOS los canales del
 * panel de una sola vez (lo cual además de reventar memoria en paneles
 * grandes, no garantiza que cada categoría tenga sus canales si el total se
 * corta en algún límite), esta versión:
 *   1) Al conectar, solo trae la LISTA DE CATEGORÍAS (liviana).
 *   2) Cuando el usuario toca una categoría, recién ahí se piden SOLO los
 *      canales de esa categoría puntual (usando el parámetro category_id de
 *      la API de Xtream), que además es mucho más rápido.
 */
class XtreamRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val maxCategories = 2_000
    private val maxChannelsPerCategory = 5_000

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

    /** Trae solo la lista de categorías/grupos (liviano, rápido). */
    suspend fun loadCategories(credentials: XtreamCredentials): List<XtreamCategory> =
        withContext(Dispatchers.IO) {
            val base = normalizeServerUrl(credentials.serverUrl)
            val user = credentials.username.trim()
            val pass = credentials.password.trim()

            // Validar servidor/credenciales
            val authUrl = "$base/player_api.php?username=$user&password=$pass"
            val authRequest = Request.Builder().url(authUrl).build()
            client.newCall(authRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Servidor o credenciales inválidas (HTTP ${response.code})")
                }
            }

            val categories = mutableListOf<XtreamCategory>()
            val categoriesUrl = "$base/player_api.php?username=$user&password=$pass&action=get_live_categories"
            val request = Request.Builder().url(categoriesUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} al pedir las categorías")
                }
                val body = response.body ?: throw IllegalStateException("Respuesta vacía")
                body.charStream().use { stream ->
                    JsonReader(stream).use { reader ->
                        reader.beginArray()
                        var count = 0
                        while (reader.hasNext() && count < maxCategories) {
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
                            if (!id.isNullOrBlank()) {
                                categories += XtreamCategory(id, name ?: "Categoría $id")
                            }
                            count++
                        }
                    }
                }
            }

            if (categories.isEmpty()) {
                throw IllegalStateException("No se encontraron categorías para esta cuenta")
            }

            categories
        }

    /** Trae solo los canales de UNA categoría puntual. */
    suspend fun loadChannelsForCategory(
        credentials: XtreamCredentials,
        category: XtreamCategory
    ): List<Channel> = withContext(Dispatchers.IO) {
        val base = normalizeServerUrl(credentials.serverUrl)
        val user = credentials.username.trim()
        val pass = credentials.password.trim()

        val streamsUrl =
            "$base/player_api.php?username=$user&password=$pass&action=get_live_streams&category_id=${category.id}"
        val request = Request.Builder().url(streamsUrl).build()
        val channels = mutableListOf<Channel>()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} al pedir los canales")
            }
            val body = response.body ?: throw IllegalStateException("Respuesta vacía")
            body.charStream().use { stream ->
                JsonReader(stream).use { reader ->
                    reader.beginArray()
                    var count = 0
                    while (reader.hasNext() && count < maxChannelsPerCategory) {
                        reader.beginObject()
                        var streamId: String? = null
                        var name: String? = null
                        var logo: String? = null
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "stream_id" -> streamId = reader.nextFlexibleString()
                                "name" -> name = reader.nextFlexibleString()
                                "stream_icon" -> logo = reader.nextFlexibleString()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (!streamId.isNullOrBlank()) {
                            channels += Channel(
                                id = streamId,
                                name = name ?: "Canal $streamId",
                                group = category.name,
                                logoUrl = logo,
                                streamUrl = "$base/live/$user/$pass/$streamId.m3u8"
                            )
                        }
                        count++
                    }
                }
            }
        }

        channels
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
