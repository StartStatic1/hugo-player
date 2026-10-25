package com.hugo.player.ui

/** Informações extras de um episódio (vêm do servidor; apps antigos ignoram). */
data class EpisodeMeta(val plot: String? = null, val durationSecs: Int? = null)

private val epCode = Regex("([Ss]\\d{1,2}[\\s._-]*[Ee]\\d{1,3}|\\b\\d{1,2}x\\d{1,3}\\b|[Tt]emp(?:orada)?\\.?\\s*\\d+\\s*[Ee]p(?:is[oó]dio)?\\.?\\s*\\d+)")

/**
 * Título "de verdade" do episódio. Painéis mandam "Série (2020) S01E01 - Título" ou só "Série S01E01":
 * tira o nome da série e o código SxxEyy; se não sobrar nada, devolve "" (a tela mostra "Episódio N").
 */
fun cleanEpisodeTitle(name: String, seriesName: String?): String {
    var t = name.trim()
    val m = epCode.find(t)
    if (m != null) {
        val after = t.substring(m.range.last + 1)
        val before = t.substring(0, m.range.first)
        val beforeIsSeries = seriesName == null || before.isBlank() ||
            normalizeForCompare(before).startsWith(normalizeForCompare(seriesName).take(6))
        t = if (beforeIsSeries) after else (before + " " + after)
    } else if (seriesName != null && t.startsWith(seriesName, ignoreCase = true)) {
        t = t.substring(seriesName.length)
    }
    t = t.trim().trimStart('-', '|', ':', '.', '_', '–').trim()
    if (seriesName != null && normalizeForCompare(t) == normalizeForCompare(seriesName)) return ""
    return t
}

private fun normalizeForCompare(s: String): String =
    s.lowercase().replace(Regex("\\(\\d{4}\\)"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")

/** 2550 -> "42 min"; 4000 -> "1 h 06 min" */
fun formatEpisodeDuration(secs: Int?): String? {
    if (secs == null || secs <= 0) return null
    val m = (secs + 30) / 60
    return if (m >= 60) "%d h %02d min".format(m / 60, m % 60) else "$m min"
}
