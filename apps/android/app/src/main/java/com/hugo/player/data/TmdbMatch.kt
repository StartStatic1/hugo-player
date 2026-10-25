package com.hugo.player.data

import java.text.Normalizer

/**
 * Escolhe o resultado do TMDB que é mesmo o título da lista: nome parecido E ano igual (±1).
 * Sem ano na lista: só aceita nome igual. Na dúvida devolve null (vale o que o provedor mandou).
 */
object TmdbMatch {
    data class Candidate(val index: Int, val names: List<String>, val year: Int?)

    private val yearRe = Regex("\\((19|20)\\d{2}\\)|(?<!\\d)(19|20)\\d{2}(?!\\d)")
    private val tagRe = Regex("\\[[^]]*]|\\b(4k|uhd|fhd|hd|sd|1080p|720p|dublado|legendado|dual|nacional)\\b", RegexOption.IGNORE_CASE)

    fun yearOf(title: String): Int? =
        Regex("\\(((19|20)\\d{2})\\)").find(title)?.groupValues?.get(1)?.toInt()
            ?: Regex("(?<!\\d)((19|20)\\d{2})\\s*$").find(title.trim())?.groupValues?.get(1)?.toInt()

    fun cleanTitle(title: String): String =
        title.replace(tagRe, " ").replace(yearRe, " ").replace(Regex("\\s+"), " ").trim().trim('-', '|', ':', '.').trim()

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace("&", " e ").replace(Regex("[^a-z0-9]+"), " ").trim()

    /** 2 = igual, 1 = muito parecido, 0 = diferente */
    private fun nameScore(a: String, b: String): Int {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0
        if (x == y) return 2
        val tx = x.split(' ').toSet(); val ty = y.split(' ').toSet()
        val inter = tx.intersect(ty).size.toDouble()
        val jac = inter / (tx.union(ty).size)
        return if (jac >= 0.6 || (minOf(x.length, y.length) >= 6 && (x.startsWith(y) || y.startsWith(x)))) 1 else 0
    }

    fun pick(listTitle: String, cands: List<Candidate>): Candidate? {
        val year = yearOf(listTitle)
        val q = cleanTitle(listTitle)
        var best: Candidate? = null
        var bestScore = Int.MIN_VALUE
        for (c in cands) {
            val ns = c.names.maxOfOrNull { nameScore(q, it) } ?: 0
            if (ns == 0) continue
            val score = if (year != null) {
                val d = c.year?.let { kotlin.math.abs(it - year) } ?: continue
                if (d > 1) continue
                ns * 10 + (if (d == 0) 5 else 2)
            } else {
                if (ns < 2) continue
                ns * 10
            }
            if (score > bestScore) { best = c; bestScore = score }
        }
        return best
    }
}
