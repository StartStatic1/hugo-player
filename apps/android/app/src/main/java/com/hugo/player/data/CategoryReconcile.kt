package com.hugo.player.data

/**
 * Categorias que o servidor mandou x categorias que os itens têm de verdade.
 *
 * O app abre uma categoria filtrando os itens pelo nome ("group"). Se o servidor mandar uma categoria
 * cujo nome nenhum item usa (ex.: o painel não respondeu a lista de categorias e o servidor chamou a
 * categoria de "Categoria 113" mas deixou os itens em "Outros"), ela aparecia com contagem e abria vazia.
 * Aqui só ficam as categorias que têm itens, com a contagem real, na ordem do servidor; os grupos que
 * só existem nos itens entram depois, na ordem em que apareceram.
 */
object CategoryReconcile {
    data class Cat(val type: String, val name: String, val count: Int)

    fun key(type: String, name: String) = "$type\t$name"

    /** [itemCounts]: "tipo\tgrupo" -> quantidade de itens (na ordem em que apareceram). */
    fun reconcile(server: List<Cat>, itemCounts: Map<String, Int>): List<Cat> {
        val out = ArrayList<Cat>(itemCounts.size)
        val used = HashSet<String>()
        for (c in server) {
            val k = key(c.type, c.name)
            val n = itemCounts[k] ?: continue
            if (n <= 0 || !used.add(k)) continue
            out.add(c.copy(count = n))
        }
        for ((k, n) in itemCounts) {
            if (n <= 0 || k in used) continue
            val p = k.split("\t", limit = 2)
            out.add(Cat(p[0], p.getOrElse(1) { "Outros" }, n))
            used.add(k)
        }
        return out
    }
}
