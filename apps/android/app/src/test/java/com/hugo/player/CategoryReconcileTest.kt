package com.hugo.player

import com.hugo.player.data.CategoryReconcile
import com.hugo.player.data.CategoryReconcile.Cat
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryReconcileTest {
    private fun counts(vararg p: Pair<String, Int>) = linkedMapOf(*p)

    @Test
    fun categoriaSemItensSomeEItensGanhamCategoria() {
        // caso syntronicdn: servidor mandou "Categoria 113"/"Categoria 313", itens todos em "Outros"
        val server = listOf(Cat("live", "Categoria 113", 5), Cat("live", "Categoria 192", 3), Cat("movie", "Categoria 313", 37))
        val got = CategoryReconcile.reconcile(server, counts("live\tOutros" to 8, "movie\tOutros" to 37))
        assertEquals(listOf(Cat("live", "Outros", 8), Cat("movie", "Outros", 37)), got)
    }

    @Test
    fun mantemOrdemDoServidorEContagemReal() {
        val server = listOf(Cat("live", "Esportes", 99), Cat("live", "Abertos", 1), Cat("movie", "Ação", 2))
        val got = CategoryReconcile.reconcile(server, counts("live\tAbertos" to 4, "movie\tAção" to 2, "live\tEsportes" to 3, "live\tNovos" to 1))
        assertEquals(
            listOf(Cat("live", "Esportes", 3), Cat("live", "Abertos", 4), Cat("movie", "Ação", 2), Cat("live", "Novos", 1)),
            got,
        )
    }

    @Test
    fun semCategoriasDoServidorUsaOsGruposDosItens() {
        val got = CategoryReconcile.reconcile(emptyList(), counts("series\tDrama" to 2))
        assertEquals(listOf(Cat("series", "Drama", 2)), got)
    }

    @Test
    fun nomeRepetidoNaoDuplica() {
        val server = listOf(Cat("live", "Globo", 2), Cat("live", "Globo", 3))
        assertEquals(listOf(Cat("live", "Globo", 5)), CategoryReconcile.reconcile(server, counts("live\tGlobo" to 5)))
    }
}
