package com.nexgo.iptv.model

/**
 * Representa un canal extraído de una lista M3U/M3U8.
 */
data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val logoUrl: String?,
    val streamUrl: String
)
