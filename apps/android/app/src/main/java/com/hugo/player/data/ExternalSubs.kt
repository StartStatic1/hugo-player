package com.hugo.player.data

import android.content.Context
import com.hugo.player.player.PlaybackRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Legenda automática (OpenSubtitles) pelo servidor do Hugo — o APK não leva chave nenhuma.
 * Só busca português (pt-BR, depois pt-PT). O .srt fica num cache pequeno no aparelho.
 */
object ExternalSubs {
    data class Query(val title: String, val year: Int?, val season: Int?, val episode: Int?)

    data class Option(val fileId: Long, val lang: String, val release: String, val machine: Boolean) {
        val label: String
            get() = buildString {
                append(if (lang == "pt-PT") "Português (Portugal)" else "Português")
                append(" (automática)")
            }
    }

    sealed class Result {
        data class Found(val options: List<Option>) : Result()
        object None : Result()
        object Quota : Result()
        object NotConfigured : Result()
        data class Error(val message: String) : Result()
    }

    private const val PREFS = "hugo_subs"
    private const val MAX_FILES = 12
    private val epRe = Regex("""T(\d{1,2})\s*·\s*E(\d{1,3})""")
    private val sxeRe = Regex("""S(\d{1,2})\s*E(\d{1,3})""", RegexOption.IGNORE_CASE)

    /** Monta a busca a partir do que está tocando (filme: título + ano; episódio: série + T/E). */
    fun queryFor(r: PlaybackRequest): Query? {
        if (r.isLive == true) return null
        val m = r.subtitle?.let { epRe.find(it) } ?: sxeRe.find(r.title)
        val base = TmdbMatch.cleanTitle(r.title.replace(sxeRe, " ")).ifBlank { return null }
        return Query(
            title = base,
            year = if (m == null) TmdbMatch.yearOf(r.title) else null,
            season = m?.groupValues?.get(1)?.toInt(),
            episode = m?.groupValues?.get(2)?.toInt(),
        )
    }

    /** Usuário escolheu "Desligada": não busca legenda sozinho até ele escolher uma de novo. */
    fun autoOff(context: Context): Boolean = prefs(context).getBoolean("auto_off", false)
    fun setAutoOff(context: Context, off: Boolean) { prefs(context).edit().putBoolean("auto_off", off).apply() }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun search(context: Context, q: Query): Result = withContext(Dispatchers.IO) {
        val idt = DeviceIdentity.get(context)
        val path = buildString {
            append("/subtitles/search?title=").append(HugoApi.enc(q.title))
            q.year?.let { append("&year=").append(it) }
            if (q.season != null && q.episode != null) append("&season=").append(q.season).append("&episode=").append(q.episode)
        }
        try {
            val arr = JSONObject(HugoApi.get(path, idt.deviceId, idt.deviceKey, readTimeoutMs = 15_000)).optJSONArray("items")
            val list = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
                Option(o.optLong("fileId"), o.optString("lang", "pt-BR"), o.optString("release"), o.optBoolean("machine"))
            }.filter { it.fileId > 0 }
            if (list.isEmpty()) Result.None else Result.Found(list)
        } catch (e: ApiException) {
            when {
                e.apiError == "subtitle_quota" || e.status == 429 -> Result.Quota
                e.apiError == "subtitles_not_configured" -> Result.NotConfigured
                else -> Result.Error(e.message ?: "Erro")
            }
        } catch (e: Exception) {
            Result.Error("Sem conexão")
        }
    }

    /** Baixa o .srt (ou usa o do cache). Lança ApiException em caso de cota/erro. */
    suspend fun file(context: Context, opt: Option): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "subs").apply { mkdirs() }
        val f = File(dir, "${opt.fileId}.srt")
        if (f.exists() && f.length() > 20) { f.setLastModified(System.currentTimeMillis()); return@withContext f }
        val idt = DeviceIdentity.get(context)
        val text = HugoApi.get("/subtitles/file/${opt.fileId}", idt.deviceId, idt.deviceKey, readTimeoutMs = 20_000)
        f.writeText(text)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
        f
    }
}
