package com.nexgo.iptv.data

import com.nexgo.iptv.model.Channel
import java.io.Reader

/**
 * Parser en streaming para listas M3U/M3U8 con extensión EXTINF (formato IPTV
 * estándar). Lee línea por línea directo desde la conexión de red, en vez de
 * cargar el archivo completo en un solo String gigante primero (importante
 * para listas de decenas de miles de canales, para no quedarse sin memoria).
 *
 * Ejemplo de entrada soportada:
 *
 * #EXTM3U
 * #EXTINF:-1 tvg-id="tvn" tvg-logo="https://.../tvn.png" group-title="Nacionales",TVN HD
 * https://ejemplo.com/tvn/index.m3u8
 */
object M3UParser {

    private val attrRegex = Regex("""([a-zA-Z-]+)="([^"]*)"""")

    fun parse(reader: Reader): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pendingName: String? = null
        var pendingGroup = "General"
        var pendingLogo: String? = null
        var autoId = 0

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> Unit

                line.startsWith("#EXTINF") -> {
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    pendingGroup = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "General"
                    pendingLogo = attrs["tvg-logo"]
                    pendingName = line.substringAfterLast(",").trim().ifBlank { "Canal ${autoId + 1}" }
                }

                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName ?: "Canal ${autoId + 1}"
                    autoId += 1
                    channels += Channel(
                        id = autoId.toString(),
                        name = name,
                        group = pendingGroup,
                        logoUrl = pendingLogo,
                        streamUrl = line
                    )
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = "General"
                }
            }
        }

        return channels
    }
                                  }
