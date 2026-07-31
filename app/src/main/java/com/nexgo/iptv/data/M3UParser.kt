package com.nexgo.iptv.data

import com.nexgo.iptv.model.Channel
import java.io.Reader

/**
 * Parser en streaming para listas M3U/M3U8 con extensión EXTINF (formato IPTV
 * estándar). Lee línea por línea directo desde la conexión de red y entrega
 * los canales en BLOQUES PEQUEÑOS mediante onBatch, en vez de acumular una
 * lista gigante en memoria. Además pone un tope máximo de seguridad
 * (MAX_CHANNELS) para nunca quedarse sin memoria.
 *
 * Ejemplo de entrada soportada:
 *
 * #EXTM3U
 * #EXTINF:-1 tvg-id="tvn" tvg-logo="https://.../tvn.png" group-title="Nacionales",TVN HD
 * https://ejemplo.com/tvn/index.m3u8
 */
object M3UParser {

    private val attrRegex = Regex("""([a-zA-Z-]+)="([^"]*)"""")
    private const val BATCH_SIZE = 200
    private const val MAX_CHANNELS = 30_000

    fun parse(reader: Reader, onBatch: (List<Channel>) -> Unit): Int {
        val batch = mutableListOf<Channel>()
        var pendingName: String? = null
        var pendingGroup = "General"
        var pendingLogo: String? = null
        var totalCount = 0

        reader.forEachLine { rawLine ->
            if (totalCount >= MAX_CHANNELS) return@forEachLine
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> Unit

                line.startsWith("#EXTINF") -> {
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    pendingGroup = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "General"
                    pendingLogo = attrs["tvg-logo"]
                    pendingName = line.substringAfterLast(",").trim().ifBlank { "Canal ${totalCount + 1}" }
                }

                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName ?: "Canal ${totalCount + 1}"
                    totalCount += 1
                    batch += Channel(
                        id = totalCount.toString(),
                        name = name,
                        group = pendingGroup,
                        logoUrl = pendingLogo,
                        streamUrl = line
                    )
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = "General"

                    if (batch.size >= BATCH_SIZE) {
                        onBatch(batch.toList())
                        batch.clear()
                    }
                }
            }
        }

        if (batch.isNotEmpty()) {
            onBatch(batch.toList())
        }

        return totalCount
    }
                                  }
