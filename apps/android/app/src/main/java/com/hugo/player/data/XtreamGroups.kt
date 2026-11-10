package com.hugo.player.data

/**
 * Categorias de verdade de um painel Xtream para a lista baixada no aparelho.
 *
 * Vários painéis mandam no get.php (M3U) só uns poucos group-title genéricos ("FILMES", "SÉRIES",
 * "CANAIS COMPLETO(SD,HD,FHD,4K)"), enquanto a player_api tem dezenas de categorias
 * ("Filmes | Ação", "Canais ⫸ Globo Sudeste"...). Quando o nosso servidor está bloqueado e a lista
 * vem pelo M3U, o aparelho pede à player_api só os nomes + category_id e reagrupa cada item
 * pelo nome (as URLs do M3U podem usar outro host e ids diferentes dos stream_id da API).
 */
class XtreamGroups {
    private val catNames = HashMap<String, HashMap<String, String>>()      // tipo -> category_id -> nome
    private val catOrder = HashMap<String, HashMap<String, Int>>()         // tipo -> nome -> posição da API
    private val byName = HashMap<String, HashMap<String, String>>()        // tipo -> nome do item -> nome da categoria
    private var folded: HashMap<String, HashMap<String, String>>? = null

    fun addCategory(type: String, id: String, name: String) {
        val n = name.trim().ifEmpty { return }
        catNames.getOrPut(type) { HashMap() }[id.trim()] = n
        val o = catOrder.getOrPut(type) { HashMap() }
        if (n !in o) o[n] = o.size
    }

    /** Chamar depois de todas as categorias do tipo (o nome da categoria sai do category_id). */
    fun addItem(type: String, name: String, categoryId: String) {
        val cat = catNames[type]?.get(categoryId.trim()) ?: return
        val key = name.trim().ifEmpty { return }
        byName.getOrPut(type) { HashMap() }.putIfAbsent(key, cat)
        folded = null
    }

    val isEmpty: Boolean get() = byName.values.all { it.isEmpty() }
    fun size(type: String): Int = byName[type]?.size ?: 0

    /** Categoria da API para o item (nome exato, depois sem acento/símbolos); null = deixa o group-title do M3U. */
    fun groupFor(type: String, vararg names: String?): String? {
        val m = byName[type] ?: return null
        for (n in names) { val t = n?.trim(); if (!t.isNullOrEmpty()) m[t]?.let { return it } }
        val f = folded ?: buildFolded().also { folded = it }
        val fm = f[type] ?: return null
        for (n in names) { val t = n?.trim(); if (!t.isNullOrEmpty()) fm[foldKey(t)]?.let { return it } }
        return null
    }

    private fun buildFolded(): HashMap<String, HashMap<String, String>> {
        val out = HashMap<String, HashMap<String, String>>()
        for ((type, m) in byName) {
            val fm = HashMap<String, String>(m.size * 2)
            for ((k, v) in m) fm.putIfAbsent(foldKey(k), v)
            out[type] = fm
        }
        return out
    }

    /** Posição da categoria na ordem da API (as que não vieram da API ficam depois). */
    fun position(type: String, category: String): Int? = catOrder[type]?.get(category)

    companion object {
        /** tipo do app -> (ação das categorias, ação dos itens) */
        val ACTIONS = listOf(
            Triple("live", "get_live_categories", "get_live_streams"),
            Triple("movie", "get_vod_categories", "get_vod_streams"),
            Triple("series", "get_series_categories", "get_series"),
        )

        /** Monta o link da player_api a partir do link get.php/player_api.php (null = não é Xtream). */
        fun apiUrl(playlistUrl: String, action: String): String? {
            val q = playlistUrl.substringAfter('?', "")
            if (q.isEmpty()) return null
            val path = playlistUrl.substringBefore('?')
            if (!Regex("/(get|player_api)\\.php$", RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
            val params = q.split('&').filter { it.isNotEmpty() }
            val user = params.firstOrNull { it.startsWith("username=") } ?: return null
            val pass = params.firstOrNull { it.startsWith("password=") } ?: return null
            if (user.length <= 9 || pass.length <= 9) return null
            val base = path.substringBeforeLast('/')
            return "$base/player_api.php?$user&$pass&action=$action"
        }
    }
}

/**
 * Nome do item numa linha #EXTINF: o texto depois da 1ª vírgula fora de aspas (depois dos atributos).
 * Antes pegava a ÚLTIMA vírgula: "Boa Sorte, Divirta-se, Não Morra (2024)" virava "Não Morra (2024)"
 * e "Nome, (2024)" virava só "(2024)" (no card: "(2024) (2024)").
 */
fun extinfName(extinf: String): String {
    var inQuotes = false
    var cut = -1
    for (i in extinf.indices) {
        when (extinf[i]) {
            '"' -> inQuotes = !inQuotes
            ',' -> if (!inQuotes) { cut = i; break }
        }
    }
    if (cut < 0) cut = extinf.lastIndexOf(',')   // aspas desbalanceadas: jeito antigo
    if (cut < 0 || cut >= extinf.length - 1) return "Canal"
    return extinf.substring(cut + 1).trim().ifBlank { "Canal" }
}
