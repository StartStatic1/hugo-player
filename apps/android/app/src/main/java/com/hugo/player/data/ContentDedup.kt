package com.hugo.player.data

import java.text.Normalizer

/** Linha mínima para agrupar (sem URL: rápido mesmo com 20k+ itens). */
data class DedupRow(val id: Long, val name: String, val group: String, val type: String, val epgId: String?)

/** Resultado por id: a quem ele pertence (null = é o item visível) e os selos do grupo (só no item visível). */
data class DedupMark(val dupOf: Long?, val dupOfAll: Long?, val tags: String?, val tagsAll: String?)

/**
 * Regex que nunca derruba o app: o Android usa o motor ICU, mais rígido que o do Java
 * (ex.: "]" e "}" soltos precisam de "\\"). Se um padrão falhar, vira um que não casa com nada.
 */
internal fun rx(pattern: String): Regex =
    try { Regex(pattern) } catch (t: Throwable) {
        runCatching { android.util.Log.w("Hugo", "regex inválida: $pattern", t) }
        Regex("(?!)")
    }

private val combining = rx("\\p{Mn}+")
private val nonAlnum = rx("[^\\p{L}\\p{N}]+")
private val spaces = rx("\\s+")
private val tokenSep = rx("[ ._-]")
/** minúsculas, sem acento, só letras/números separados por 1 espaço */
internal fun foldKey(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(combining, "").lowercase()
        .replace(nonAlnum, " ").trim()

/** emoji/símbolos decorativos (⭐ ✔ ❤ ♦ ☠ …) e marcas invisíveis */
private val decor = rx("[\\p{So}\\p{Sk}\\p{Cs}\\p{Co}\\uFE0E\\uFE0F\\u200B-\\u200D\\u2060]")

/**
 * Canais ao vivo: o mesmo canal em várias qualidades vira 1 só.
 * Só tira as marcas de qualidade (SD/HD/FHD/4K/H265/ALT…); o resto do nome precisa ser igual
 * ("Telecine Action" ≠ "Telecine Pipoca", "Globo SP" ≠ "Globo RJ").
 */
object LiveQuality {
    data class Info(val key: String, val base: String, val rank: Int, val hevc: Boolean, val hdr: Boolean, val alt: Boolean) {
        /** "SD" / "HD" / "FHD" / "4K" (0 = sem marca → "Padrão") */
        val qualityLabel: String get() = RANK_LABEL[rank]
        val label: String get() = buildString {
            append(qualityLabel)
            if (hdr) append(" HDR")
            if (hevc) append(" H.265")
            if (alt) append(" ALT")
        }
    }

    val RANK_LABEL = listOf("Padrão", "SD", "HD", "FHD", "4K")
    /** Qualidade preferida (Configurações): "best", "FHD", "HD", "SD" */
    val PREFERRED = listOf("best" to "Melhor disponível", "FHD" to "FHD", "HD" to "HD", "SD" to "SD")

    private val supers = listOf("ᶠᴴᴰ" to " FHD ", "ᵁᴴᴰ" to " UHD ", "ᴴᴰ" to " HD ", "ˢᴰ" to " SD ", "⁴ᴷ" to " 4K ")
    private val superAlt = rx("(?i)(hd|fhd|sd|4k|uhd)\\s*[²³¹]")
    private val superDigit = mapOf('¹' to " 1", '²' to " 2", '³' to " 3")
    private val token = rx(
        "(?i)(?<![\\p{L}\\p{N}])(full[ ._-]?hd|fhdr|uhd|fhd|hdr|hd|sd|hq|4k|8k|2160p?|1080[pi]?|720p?|576p?|480p?|" +
            "h[ ._-]?26[45]|hevc|x26[45]|alt(?:ernativo)?[ ._-]?\\d?|backup|reserva|\\d{2}[ ]?fps)(?![\\p{L}\\p{N}])"
    )
    private val emptyBrackets = rx("\\[\\s*\\]|\\(\\s*\\)|\\{\\s*\\}|<\\s*>")

    private fun prep(name: String): String {
        var s = name
        supers.forEach { (a, b) -> s = s.replace(a, b) }
        s = superAlt.replace(s) { "${it.groupValues[1]} ALT" }
        s = buildString { s.forEach { c -> append(superDigit[c] ?: c.toString()) } }
        return decor.replace(s, " ")
    }

    /** Nunca lança: se algo der errado, o canal fica com o nome original e sem agrupar. */
    fun parse(name: String): Info =
        try { parseImpl(name) } catch (t: Throwable) {
            val n = name.trim()
            Info(n.lowercase(), n, 0, false, false, false)
        }

    private fun parseImpl(name: String): Info {
        val s = prep(name)
        var rank = 0; var hevc = false; var hdr = false; var alt = false
        token.findAll(s).forEach { m ->
            val t = m.value.lowercase().replace(tokenSep, "")
            when {
                t == "sd" || t == "480" || t == "480p" || t == "576" || t == "576p" -> rank = maxOf(rank, 1)
                t == "hd" || t == "hq" || t.startsWith("720") -> rank = maxOf(rank, 2)
                t == "fhd" || t == "fullhd" || t.startsWith("1080") -> rank = maxOf(rank, 3)
                t == "fhdr" -> { rank = maxOf(rank, 3); hdr = true }
                t == "uhd" || t == "4k" || t == "8k" || t.startsWith("2160") -> rank = maxOf(rank, 4)
                t == "hdr" -> hdr = true
                t.contains("265") || t == "hevc" -> hevc = true
                t.contains("264") -> {}
                t.startsWith("alt") || t == "backup" || t == "reserva" -> alt = true
            }
        }
        val stripped = token.replace(s, " ")
        val base = emptyBrackets.replace(stripped, " ").replace(spaces, " ").trim().trim('-', '|', ':', '•', '·', '_', '.', ' ', '/')
        val key = foldKey(base).ifEmpty { foldKey(name) }
        return Info(key, base.ifEmpty { name.trim() }, rank, hevc, hdr, alt)
    }

    /** Nome do canal sem as marcas de qualidade ("Telecine Pipoca FHD" -> "Telecine Pipoca"). */
    fun displayName(name: String): String = parse(name).base

    /** Selos curtos do grupo, em ordem: "SD,HD,FHD,4K". */
    fun tags(names: List<String>): String =
        names.map { parse(it).rank }.filter { it > 0 }.distinct().sorted().joinToString(",") { RANK_LABEL[it] }

    /** Rótulos das variantes (sem repetir: "HD", "HD 2"). Mesma ordem da lista. */
    fun labels(names: List<String>): List<String> = try { labelsImpl(names) } catch (t: Throwable) { names }

    private fun labelsImpl(names: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return names.map { n ->
            val l = parse(n).label
            val c = (seen[l] ?: 0) + 1
            seen[l] = c
            if (c == 1) l else "$l $c"
        }
    }

    /** Ordem de "melhor": qualidade maior, sem ALT, sem H.265 (TV fraca), primeiro da lista. */
    private fun bestOrder(i: Info): Int = i.rank * 100 - (if (i.alt) 10 else 0) - (if (i.hevc) 5 else 0)

    /**
     * Qual variante tocar: a última escolhida (pelo rótulo), senão a preferida, senão a melhor.
     * [names] na ordem da lista; devolve o índice.
     */
    fun pick(names: List<String>, remembered: String?, preferred: String): Int =
        try { pickImpl(names, remembered, preferred) } catch (t: Throwable) { 0 }

    private fun pickImpl(names: List<String>, remembered: String?, preferred: String): Int {
        if (names.isEmpty()) return 0
        val labels = labels(names)
        remembered?.let { r -> labels.indexOf(r).takeIf { it >= 0 }?.let { return it } }
        val infos = names.map { parse(it) }
        val best = infos.indices.maxWith(compareBy<Int> { bestOrder(infos[it]) }.thenByDescending { -it })
        val want = RANK_LABEL.indexOf(preferred)
        if (want <= 0) return best
        val exact = infos.indices.filter { infos[it].rank == want }
        if (exact.isNotEmpty()) return exact.maxWith(compareBy<Int> { bestOrder(infos[it]) }.thenByDescending { -it })
        val below = infos.indices.filter { infos[it].rank in 1 until want }
        if (below.isNotEmpty()) return below.maxWith(compareBy<Int> { bestOrder(infos[it]) }.thenByDescending { -it })
        return infos.indices.minWith(compareBy<Int> { if (infos[it].rank == 0) 99 else infos[it].rank }.thenBy { it })
    }
}

/**
 * Filmes repetidos: "Nome", "Nome (2025)", "Nome [L]", "Nome 4K [HDR]", cópias idênticas.
 * Anos diferentes e explícitos = filmes diferentes (refilmagens).
 */
object MovieDedup {
    data class Info(val key: String, val year: Int?, val legendado: Boolean, val uhd: Boolean, val hdr: Boolean, val clean: String)

    private val yearRx = rx("[\\(\\[]\\s*((?:19|20)\\d{2})\\s*[\\)\\]]")
    private val legRx = rx("(?i)\\[\\s*l\\s*\\]|\\(\\s*l\\s*\\)|(?<![\\p{L}\\p{N}])(leg|legendado|legendada)(?![\\p{L}\\p{N}])")
    private val dubRx = rx("(?i)\\[\\s*d\\s*\\]|\\(\\s*d\\s*\\)|(?<![\\p{L}\\p{N}])(dub|dublado|dublada|dual[ ._-]?(?:audio|áudio))(?![\\p{L}\\p{N}])")
    private val qRx = rx("(?i)(?<![\\p{L}\\p{N}])(4k|uhd|hdr10\\+?|hdr|dolby[ ._-]?vision|fhd|full[ ._-]?hd|hd|1080p?|720p?|2160p?|h[ ._-]?26[45]|x26[45]|hevc|web[ ._-]?dl|webrip|bluray|brrip|remux)(?![\\p{L}\\p{N}])")
    private val uhdRx = rx("(?i)(?<![\\p{L}\\p{N}])(4k|uhd|2160p?)(?![\\p{L}\\p{N}])")
    private val hdrRx = rx("(?i)(?<![\\p{L}\\p{N}])(hdr10\\+?|hdr|dolby[ ._-]?vision)(?![\\p{L}\\p{N}])")
    private val emptyBrackets = rx("\\[\\s*\\]|\\(\\s*\\)|\\{\\s*\\}")

    /** Nunca lança: se algo der errado, o filme fica com o nome original (sem juntar). */
    fun parse(name: String, group: String = ""): Info =
        try { parseImpl(name, group) } catch (t: Throwable) {
            val n = name.trim()
            Info(n.lowercase(), null, false, false, false, n)
        }

    private fun parseImpl(name: String, group: String): Info {
        val s = decor.replace(name, " ")
        val year = yearRx.find(s)?.groupValues?.get(1)?.toIntOrNull()
        val legendado = legRx.containsMatchIn(s) || foldKey(group).contains("legendad")
        var t = yearRx.replace(s, " ")
        t = legRx.replace(t, " "); t = dubRx.replace(t, " "); t = qRx.replace(t, " ")
        val clean = emptyBrackets.replace(t, " ").replace(spaces, " ").trim().trim('-', '|', ':', '•', '·', '_', '.', ' ', '/')
        val key = foldKey(clean).ifEmpty { foldKey(name) }
        return Info(key, year, legendado, uhdRx.containsMatchIn(s), hdrRx.containsMatchIn(s), clean.ifEmpty { name.trim() })
    }

    /** Título para o card: limpo + "(Ano)" quando se sabe. */
    fun displayTitle(name: String): String = parse(name).let { i -> if (i.year != null) "${i.clean} (${i.year})" else i.clean }

    fun versionLabel(name: String, group: String): String = label(parse(name, group))

    fun label(i: Info): String = i.let { i ->
        buildString {
            append(if (i.legendado) "Legendado" else "Dublado")
            if (i.uhd) append(" 4K")
            if (i.hdr) append(" HDR")
        }
    }

    /** Rótulos das versões sem repetir ("Dublado", "Dublado · opção 2"). */
    fun versionLabels(items: List<Pair<String, String>>): List<String> =
        try { versionLabelsImpl(items) } catch (t: Throwable) { items.map { it.first } }

    private fun versionLabelsImpl(items: List<Pair<String, String>>): List<String> {
        val seen = HashMap<String, Int>()
        return items.map { (n, g) ->
            val l = versionLabel(n, g)
            val c = (seen[l] ?: 0) + 1
            seen[l] = c
            if (c == 1) l else "$l · opção $c"
        }
    }
}

object ContentDedup {
    /** Agrupa 1 escopo (lista de linhas já do mesmo grupo de busca). Devolve clusters (rep primeiro). */
    private fun clustersLive(rows: List<DedupRow>, infos: Map<Long, LiveQuality.Info>, adult: (String) -> Boolean): List<List<DedupRow>> =
        rows.groupBy { infos.getValue(it.id).key }.values.filter { it.size > 1 }.map { c ->
            val rep = c.minWith(
                compareBy<DedupRow> { if (adult(it.group)) 1 else 0 }
                    .thenBy { if (it.epgId.isNullOrBlank()) 1 else 0 }
                    .thenByDescending { infos.getValue(it.id).let { i -> i.rank * 100 - (if (i.alt) 10 else 0) - (if (i.hevc) 5 else 0) } }
                    .thenBy { it.id }
            )
            listOf(rep) + c.filter { it !== rep }
        }

    private fun clustersMovie(rows: List<DedupRow>, infos: Map<Long, MovieDedup.Info>, adult: (String) -> Boolean): List<List<DedupRow>> {
        val out = ArrayList<List<DedupRow>>()
        rows.groupBy { infos.getValue(it.id).key }.values.filter { it.size > 1 }.forEach { sameName ->
            val byYear = sameName.groupBy { infos.getValue(it.id).year }
            val years = byYear.keys.filterNotNull()
            // sem ano junta com o único ano que existir; com 2+ anos diferentes (refilmagem) cada ano fica separado
            val parts: Collection<List<DedupRow>> = if (years.size <= 1) listOf(sameName) else byYear.values
            parts.filter { it.size > 1 }.forEach { c ->
                val rep = c.minWith(
                    compareBy<DedupRow> { if (adult(it.group)) 1 else 0 }
                        .thenBy { if (infos.getValue(it.id).legendado) 1 else 0 }
                        .thenBy { if (infos.getValue(it.id).uhd) 1 else 0 }
                        .thenBy { if (infos.getValue(it.id).year == null) 1 else 0 }
                        .thenBy { it.name.length }
                        .thenBy { it.id }
                )
                out.add(listOf(rep) + c.filter { it !== rep })
            }
        }
        return out
    }

    /** Selos do grupo; null quando todas as cópias são iguais (nada a escolher). */
    private fun tagsOf(type: String, c: List<DedupRow>, movieInfos: Map<Long, MovieDedup.Info>, liveInfos: Map<Long, LiveQuality.Info>): String? =
        if (type == "live") c.map { liveInfos.getValue(it.id).rank }.filter { it > 0 }.distinct().sorted().joinToString(",") { LiveQuality.RANK_LABEL[it] }.ifEmpty { null }
        else c.map { MovieDedup.label(movieInfos.getValue(it.id)) }.distinct().takeIf { it.size > 1 }?.joinToString(" · ")

    /**
     * Calcula as marcas de toda a playlist: por categoria (dupOf/tags) e em "Todos"/busca (dupOfAll/tagsAll).
     * Só devolve ids que mudam (o resto continua null).
     */
    fun compute(rows: List<DedupRow>, adult: (String) -> Boolean = { false }): Map<Long, DedupMark> =
        try { computeImpl(rows, adult) } catch (t: Throwable) {
            runCatching { android.util.Log.w("Hugo", "agrupamento falhou; lista fica sem agrupar", t) }
            emptyMap()
        }

    private fun computeImpl(rows: List<DedupRow>, adult: (String) -> Boolean): Map<Long, DedupMark> {
        val dupOf = HashMap<Long, Long>(); val dupOfAll = HashMap<Long, Long>()
        val tags = HashMap<Long, String>(); val tagsAll = HashMap<Long, String>()
        val liveInfos = HashMap<Long, LiveQuality.Info>(); val movieInfos = HashMap<Long, MovieDedup.Info>()
        rows.forEach { r -> when (r.type) { "live" -> liveInfos[r.id] = LiveQuality.parse(r.name); "movie" -> movieInfos[r.id] = MovieDedup.parse(r.name, r.group) } }
        rows.groupBy { it.type }.forEach { (type, list) ->
            val cluster: (List<DedupRow>) -> List<List<DedupRow>> = when (type) {
                "live" -> { l -> clustersLive(l, liveInfos, adult) }
                "movie" -> { l -> clustersMovie(l, movieInfos, adult) }
                else -> return@forEach
            }
            list.groupBy { it.group }.values.forEach { g ->
                cluster(g).forEach { c -> tagsOf(type, c, movieInfos, liveInfos)?.let { tags[c[0].id] = it }; c.drop(1).forEach { dupOf[it.id] = c[0].id } }
            }
            cluster(list).forEach { c -> tagsOf(type, c, movieInfos, liveInfos)?.let { tagsAll[c[0].id] = it }; c.drop(1).forEach { dupOfAll[it.id] = c[0].id } }
        }
        val ids = HashSet<Long>().apply { addAll(dupOf.keys); addAll(dupOfAll.keys); addAll(tags.keys); addAll(tagsAll.keys) }
        return ids.associateWith { DedupMark(dupOf[it], dupOfAll[it], tags[it], tagsAll[it]) }
    }
}
