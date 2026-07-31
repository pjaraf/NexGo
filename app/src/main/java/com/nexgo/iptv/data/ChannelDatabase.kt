package com.nexgo.iptv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nexgo.iptv.model.Channel

/**
 * Guarda los canales en una base de datos SQLite en el almacenamiento del
 * teléfono en vez de mantenerlos todos en la memoria RAM (que es lo que
 * causaba el OutOfMemoryError con listas de decenas/cientos de miles de
 * canales). La pantalla solo pide a la base de datos los canales del grupo
 * que se está viendo en cada momento, nunca la lista completa.
 */
class ChannelDatabase(context: Context) : SQLiteOpenHelper(context, "nexgo_channels.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE channels (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                group_name TEXT NOT NULL,
                logo_url TEXT,
                stream_url TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_channels_group ON channels(group_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS channels")
        onCreate(db)
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM channels")
    }

    /**
     * Inserta un bloque de canales dentro de una transacción. Se llama muchas
     * veces con bloques pequeños (no con la lista completa) a medida que se
     * van leyendo del servidor.
     */
    fun insertBatch(batch: List<Channel>) {
        if (batch.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO channels (id, name, group_name, logo_url, stream_url) VALUES (?, ?, ?, ?, ?)"
            )
            for (channel in batch) {
                stmt.clearBindings()
                stmt.bindString(1, channel.id)
                stmt.bindString(2, channel.name)
                stmt.bindString(3, channel.group)
                if (channel.logoUrl != null) stmt.bindString(4, channel.logoUrl) else stmt.bindNull(4)
                stmt.bindString(5, channel.streamUrl)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getGroups(): List<String> {
        val groups = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT DISTINCT group_name FROM channels ORDER BY group_name", null).use { cursor ->
            while (cursor.moveToNext()) groups += cursor.getString(0)
        }
        return groups
    }

    fun getChannelCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM channels", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * Trae solo los canales de un grupo puntual, con un límite razonable para
     * que aunque ese grupo tenga miles de canales, nunca se cargue de golpe
     * algo que no entra en pantalla igual.
     */
    fun getChannels(group: String, limit: Int = 2000): List<Channel> {
        val channels = mutableListOf<Channel>()
        readableDatabase.rawQuery(
            "SELECT id, name, group_name, logo_url, stream_url FROM channels WHERE group_name = ? ORDER BY name LIMIT ?",
            arrayOf(group, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                channels += Channel(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    group = cursor.getString(2),
                    logoUrl = cursor.getString(3),
                    streamUrl = cursor.getString(4)
                )
            }
        }
        return channels
    }
}
