package com.nexgo.iptv.data

import com.nexgo.iptv.model.Channel

/**
 * Parser simple para listas M3U/M3U8 con extensión EXTINF (formato IPTV estándar).
 *
 * Ejemplo de entrada soportada:
 *
 * #EXTM3U
 * #EXTINF:-1 tvg-id="tvn" tvg-logo="https://.../tvn.png" group-title="Nacionales",TVN HD
 * https://ejemplo.com/tvn/index.m3u8
 */
object M3UParser {

    private val attrRegex = Regex("""([a-zA-Z-]+)="([^"]*)"""")

    fun parse(content: String): List<Channel> {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val channels = mutableListOf<Channel>()
        var pendingName: String? = null
        var pendingGroup = "General"
        var pendingLogo: String? = null
        var autoId = 0

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U") -> continue

                line.startsWith("#EXTINF") -> {
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    pendingGroup = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "General"
                    pendingLogo = attrs["tvg-logo"]
                    // El nombre del canal va después de la última coma de la línea EXTINF
                    pendingName = line.substringAfterLast(",").trim().ifBlank { "Canal ${autoId + 1}" }
                }

                line.startsWith("#") -> {
                    // Otras directivas (#EXTGRP, #EXTVLCOPT, etc.) se ignoran por ahora
                }

                else -> {
                    // Línea de URL del stream
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
