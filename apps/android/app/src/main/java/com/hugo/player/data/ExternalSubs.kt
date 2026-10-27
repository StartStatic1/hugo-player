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
    /** hint = título + nome do arquivo (só para o servidor escolher o release que sincroniza melhor) */
    data class Query(val title: String, val year: Int?, val season: Int?, val episode: Int?, val hint: String? = null)

    data class Option(val fileId: Long, val lang: String, val release: String, val machine: Boolean, val hearingImpaired: Boolean = false) {
        val label: String
            get() = buildString {
                append(if (lang == "pt-PT") "Português (Portugal)" else "Português")
                append(" (internet)")
                if (hearingImpaired) append(" · descreve sons")
            }
    }

    /** Uma fala do .srt (tempos em ms). */
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

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
            hint = releaseHint(r.title, r.url),
        )
    }

    /**
     * Título + nome do arquivo do link (ex.: "Filme.2024.1080p.BluRay.mkv"). Só o último pedaço do
     * caminho, sem "?..." — nunca manda usuário/senha da lista.
     */
    fun releaseHint(title: String, url: String?): String {
        val last = url?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        val file = last.takeIf { it.substringBeforeLast('.').count { c -> c.isLetter() } >= 3 && '@' !in it && '=' !in it && !it.startsWith("get.php") }
        return listOfNotNull(title, file).joinToString(" ").take(180)
    }

    private val timeRe = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val tagRe = Regex("""<[^>]+>|\{\\[^}]*\}""")

    /** Lê o .srt em falas ordenadas (sem tags/estilos embutidos). */
    fun parseSrt(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        fun ms(h: String, m: String, s: String, f: String) =
            ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').take(3).toLong()
        while (i < lines.size) {
            val t = timeRe.find(lines[i])
            if (t == null) { i++; continue }
            val g = t.groupValues
            val start = ms(g[1], g[2], g[3], g[4]); val end = ms(g[5], g[6], g[7], g[8])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && timeRe.find(lines[i]) == null) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].replace(tagRe, "").trim())
                i++
            }
            val txt = body.toString().trim()
            if (txt.isNotEmpty() && end > start) out.add(Cue(start, end, txt))
        }
        out.sortBy { it.startMs }
        return out
    }

    /**
     * Fala na tela no instante [positionMs] com o ajuste [offsetMs]
     * (positivo = legenda aparece mais tarde; negativo = mais cedo).
     */
    fun cueAt(cues: List<Cue>, positionMs: Long, offsetMs: Long): Cue? {
        val t = positionMs - offsetMs
        var lo = 0; var hi = cues.size - 1; var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        // falas podem se sobrepor: olha algumas para trás
        var k = idx
        while (k >= 0 && k > idx - 4) {
            val c = cues[k]
            if (t < c.endMs) return c
            k--
        }
        return null
    }

    /** "+1,25s", "−0,5s", "0s" */
    fun formatOffset(ms: Long): String {
        if (ms == 0L) return "0s"
        val sign = if (ms > 0) "+" else "−"
        val v = kotlin.math.abs(ms) / 1000.0
        val txt = if (v == kotlin.math.floor(v)) v.toLong().toString() else "%.2f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.').replace('.', ',')
        return "$sign${txt}s"
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun search(context: Context, q: Query): Result = withContext(Dispatchers.IO) {
        val idt = DeviceIdentity.get(context)
        val path = buildString {
            append("/subtitles/search?title=").append(HugoApi.enc(q.title))
            q.year?.let { append("&year=").append(it) }
            if (q.season != null && q.episode != null) append("&season=").append(q.season).append("&episode=").append(q.episode)
            q.hint?.takeIf { it.isNotBlank() }?.let { append("&hint=").append(HugoApi.enc(it)) }
        }
        try {
            val arr = JSONObject(HugoApi.get(path, idt.deviceId, idt.deviceKey, readTimeoutMs = 15_000)).optJSONArray("items")
            val list = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
                Option(o.optLong("fileId"), o.optString("lang", "pt-BR"), o.optString("release"), o.optBoolean("machine"), o.optBoolean("hearingImpaired"))
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
