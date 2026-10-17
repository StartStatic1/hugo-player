package com.hugo.player.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Cache do catálogo em disco (filesDir/catalog-cache/), para abrir a lista
 * instantaneamente entre sessões. Cada arquivo guarda o JSON do servidor
 * dentro de um envelope com data de gravação e nome da playlist.
 */
object DiskCache {
    private const val VERSION = 1
    private var dir: File? = null

    data class Entry(val catalog: JSONObject, val savedAt: Long, val playlistName: String?) {
        fun ageMs(now: Long = System.currentTimeMillis()) = now - savedAt
    }

    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.filesDir, "catalog-cache").apply { mkdirs() }
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun file(playlistId: String, variant: String) = dir?.let { File(it, "catalog-${safe(playlistId)}-$variant.json") }

    fun read(playlistId: String, variant: String): Entry? {
        val f = file(playlistId, variant) ?: return null
        if (!f.exists()) return null
        return try {
            val text = f.readText()
            // envelope: {"v":1,"savedAt":...,"playlistName":"...","catalog":{...}}
            val env = JSONObject(text)
            if (env.optInt("v") != VERSION) return null
            Entry(env.getJSONObject("catalog"), env.optLong("savedAt"), env.optString("playlistName", "").ifBlank { null })
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    /** Data de gravação sem ler o arquivo (null = não existe). */
    fun savedAt(playlistId: String, variant: String): Long? =
        file(playlistId, variant)?.takeIf { it.exists() }?.lastModified()

    fun write(playlistId: String, variant: String, catalogJson: JSONObject, playlistName: String?) {
        val f = file(playlistId, variant) ?: return
        try {
            val env = JSONObject()
                .put("v", VERSION)
                .put("savedAt", System.currentTimeMillis())
                .put("playlistName", playlistName ?: "")
                .put("catalog", catalogJson)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(env.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Exception) {
            // disco cheio etc.: o cache é só uma otimização
        }
    }

    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0L
}
