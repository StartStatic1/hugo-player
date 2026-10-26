package com.hugo.player.data

import java.text.Normalizer

/**
 * Séries que o painel manda em duas versões: "Nome (Ano)" (dublada) e "Nome [L] (Ano)" (legendada).
 * Junta só pares claros: mesmo título normalizado (sem [L]/LEG/DUB, acentos, pontuação) e ano igual (±1),
 * ou os dois sem ano. Se houver mais de um candidato empatado, não junta.
 */
object SeriesTwins {
    private val legWord = Regex("\\[\\s*(?:L|LEG|LEGENDADO)\\s*]|\\(\\s*(?:L|LEG|LEGENDADO)\\s*\\)|\\bLEG\\b|(?i:\\blegendad[oa]\\b)")
    private val dubTag = Regex("\\[\\s*(?:D|DUB|DUBLADO)\\s*]|\\(\\s*(?:DUB|DUBLADO)\\s*\\)|\\bDUB\\b|(?i:\\bdublad[oa]\\b)")
    private val yearRe = Regex("\\(((?:19|20)\\d{2})\\)")

    data class Key(val norm: String, val year: Int?, val legendado: Boolean)

    fun key(name: String): Key {
        val leg = legWord.containsMatchIn(name) || Regex("\\[\\s*L\\s*]", RegexOption.IGNORE_CASE).containsMatchIn(name)
        val year = yearRe.find(name)?.groupValues?.get(1)?.toIntOrNull()
        var t = name.replace(legWord, " ").replace(Regex("\\[\\s*L\\s*]", RegexOption.IGNORE_CASE), " ").replace(dubTag, " ").replace(yearRe, " ")
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace("&", " e ").replace(Regex("[^a-z0-9]+"), " ").trim()
        return Key(t, year, leg)
    }

    /** Pares (legendada -> dublada) dentro da mesma lista de nomes. */
    fun pairs(names: Collection<String>): Map<String, String> {
        val dubs = HashMap<String, MutableList<Pair<String, Int?>>>()
        val legs = ArrayList<Pair<String, Key>>()
        for (n in names) {
            val k = key(n)
            if (k.norm.isEmpty()) continue
            if (k.legendado) legs.add(n to k) else dubs.getOrPut(k.norm) { ArrayList() }.add(n to k.year)
        }
        val out = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        for ((leg, k) in legs) {
            val cands = dubs[k.norm] ?: continue
            val ok = cands.mapNotNull { (d, y) ->
                when {
                    k.year == null && y == null -> d to 0
                    k.year != null && y != null && kotlin.math.abs(k.year - y) <= 1 -> d to kotlin.math.abs(k.year - y)
                    else -> null
                }
            }
            if (ok.isEmpty()) continue
            val best = ok.minOf { it.second }
            val top = ok.filter { it.second == best }
            if (top.size != 1) continue
            val dub = top[0].first
            if (!used.add(dub)) continue
            out[leg] = dub
        }
        return out
    }
}
