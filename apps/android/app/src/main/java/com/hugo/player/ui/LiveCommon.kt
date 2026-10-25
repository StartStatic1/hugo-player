package com.hugo.player.ui

import android.app.ActivityManager
import android.content.Context
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.player.StreamTypes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** De onde veio a lista que o player usa para zapear (mesma ordem/numeração da tela). */
data class LiveListSource(
    val playlistId: String,
    /** "__fav", "__rec", "__all" ou o nome da categoria */
    val filter: String,
    val query: String,
    val title: String,
)

/** Pedido do player ao vivo: canal + lista para CH+/-, números e lista lateral. */
data class LivePlayRequest(
    val source: LiveListSource,
    val channel: ChannelEntity,
    /** número (1-based) do canal na lista da tela */
    val number: Int,
    /** só favoritos/recentes: a lista inteira já vem pronta */
    val fixedList: List<ChannelEntity>? = null,
)

object LiveSession {
    @Volatile var current: LivePlayRequest? = null
    /** último canal assistido no player (a tela volta com o foco nele) */
    @Volatile var lastPlayedUrl: String? = null
}

object LiveFilters {
    const val FAV = "__fav"
    const val REC = "__rec"
    const val ALL = "__all"
    fun group(filter: String): String? = if (filter == FAV || filter == REC || filter == ALL) null else filter
}

/** Prévia ao vivo só em aparelhos com memória suficiente (Fire TV Lite / 1 GB fica sem). */
fun livePreviewAllowed(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    if (am.isLowRamDevice) return false
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    return mi.totalMem >= 1_300_000_000L && am.memoryClass >= 128
}

private val q4k = Regex("\\b(4k|uhd|2160p?)\\b", RegexOption.IGNORE_CASE)
private val qFhd = Regex("\\b(fhd|full ?hd|1080p?)\\b", RegexOption.IGNORE_CASE)
private val qHd = Regex("\\b(hd|720p?)\\b", RegexOption.IGNORE_CASE)
private val qSd = Regex("\\bsd\\b", RegexOption.IGNORE_CASE)
private val qHevc = Regex("\\b(h\\.?265|hevc)\\b", RegexOption.IGNORE_CASE)

/** Etiquetas de qualidade pelo nome do canal + formato do stream. */
fun qualityChips(c: ChannelEntity, videoHeight: Int = 0): List<String> {
    val out = ArrayList<String>()
    when {
        videoHeight >= 2000 -> out.add("4K")
        videoHeight >= 1000 -> out.add("FHD")
        videoHeight >= 700 -> out.add("HD")
        videoHeight in 1..699 -> out.add("SD")
        q4k.containsMatchIn(c.name) -> out.add("4K")
        qFhd.containsMatchIn(c.name) -> out.add("FHD")
        qHd.containsMatchIn(c.name) -> out.add("HD")
        qSd.containsMatchIn(c.name) -> out.add("SD")
    }
    if (videoHeight > 0) out.add("${videoHeight}p")
    if (qHevc.containsMatchIn(c.name)) out.add("H.265")
    when (StreamTypes.detect(c.url)) {
        StreamTypes.Kind.HLS -> out.add("HLS")
        StreamTypes.Kind.TS -> out.add("TS")
        else -> Unit
    }
    return out
}

/** Xtream: mesmo canal em .ts <-> .m3u8 (alguns painéis só entregam um dos dois). */
fun liveAltVariant(url: String): String? {
    val q = url.indexOf('?').let { if (it < 0) url.length else it }
    val path = url.substring(0, q)
    val rest = url.substring(q)
    return when {
        path.endsWith(".ts", true) -> path.dropLast(3) + ".m3u8" + rest
        path.endsWith(".m3u8", true) -> path.dropLast(5) + ".ts" + rest
        else -> null
    }
}

fun formatHm(ms: Long): String = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(ms))

/** Formato escolhido no painel de fonte do player ao vivo. */
enum class LiveFormat(val label: String) { AUTO("Automático"), TS("Forçar TS (.ts)"), HLS("Forçar HLS (.m3u8)") }

/** Uma tentativa de abrir o canal: URL + se força HLS (MIME m3u8). */
data class LiveAttempt(val url: String, val forceHls: Boolean)

/**
 * Lembra por servidor qual extensão funcionou (ex.: painel XUI que devolve página HTML no .m3u8
 * e só entrega vídeo no .ts). Assim o próximo canal do mesmo servidor já abre no formato certo.
 */
object LiveFormatMemory {
    private val preferred = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun host(url: String): String? = runCatching { java.net.URI(url).let { "${it.host}:${it.port}" } }.getOrNull()
    private fun ext(url: String): String? = when (StreamTypes.detect(url)) {
        StreamTypes.Kind.HLS -> "m3u8"; StreamTypes.Kind.TS -> "ts"; else -> null
    }
    fun preferredExt(url: String): String? = host(url)?.let { preferred[it] }
    fun remember(url: String) { val h = host(url) ?: return; val e = ext(url) ?: return; preferred[h] = e }
    fun clear() = preferred.clear()
}

/**
 * Ordem de tentativas para um canal ao vivo. No automático: o formato que já funcionou neste
 * servidor primeiro, depois o outro (.m3u8 <-> .ts); sem extensão: detecta pelo conteúdo, depois HLS.
 */
fun liveAttempts(base: String, format: LiveFormat = LiveFormat.AUTO): List<LiveAttempt> {
    val kind = StreamTypes.detect(base)
    val alt = liveAltVariant(base)
    val ts = if (kind == StreamTypes.Kind.TS) base else if (kind == StreamTypes.Kind.HLS) alt else null
    val hls = if (kind == StreamTypes.Kind.HLS) base else if (kind == StreamTypes.Kind.TS) alt else null
    return when (format) {
        LiveFormat.TS -> listOf(LiveAttempt(ts ?: base, false))
        LiveFormat.HLS -> listOf(LiveAttempt(hls ?: base, true))
        LiveFormat.AUTO -> when {
            ts != null && hls != null -> {
                val tsFirst = when (LiveFormatMemory.preferredExt(base)) { "ts" -> true; "m3u8" -> false; else -> kind == StreamTypes.Kind.TS }
                if (tsFirst) listOf(LiveAttempt(ts, false), LiveAttempt(hls, true)) else listOf(LiveAttempt(hls, true), LiveAttempt(ts, false))
            }
            else -> listOf(LiveAttempt(base, false), LiveAttempt(base, true))
        }
    }
}

/**
 * Falha de "formato" (vale tentar a outra variante): conteúdo não reconhecido / manifesto inválido
 * (painel devolvendo HTML), content-type errado ou HTTP 4xx do canal.
 */
fun isLiveFormatFailure(errorCode: Int, httpStatus: Int?): Boolean = when (errorCode) {
    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> true
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> httpStatus != null && httpStatus in 400..499
    else -> false
}
