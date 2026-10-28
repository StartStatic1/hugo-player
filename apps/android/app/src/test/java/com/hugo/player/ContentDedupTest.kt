package com.hugo.player

import com.hugo.player.data.ContentDedup
import com.hugo.player.data.DedupRow
import com.hugo.player.data.LiveQuality
import com.hugo.player.data.MovieDedup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDedupTest {
    private fun k(n: String) = LiveQuality.parse(n).key

    @Test
    fun canalTiraSoAsMarcasDeQualidade() {
        val same = listOf(
            "Telecine Pipoca HD", "Telecine Pipoca FULLHD", "Telecine Pipoca SD", "Telecine Pipoca 4K", "TELECINE PIPOCA FHD",
            "Telecine Pipoca UHD", "Telecine Pipoca H265", "Telecine Pipoca HEVC", "Telecine Pipoca ᴴᴰ", "Telecine Pipoca [HD]",
            "Telecine Pipoca (FHD)", "Telecine Pipoca HD²", "Telecine Pipoca HD ⭐", "Telecine Pipoca FHD [ALT]", "Telecine Pipoca Full HD",
            "Telecine Pipoca 1080p", "Telecine Pipoca FHD [H265]", "Telecine Pipoca",
        )
        same.forEach { assertEquals(it, "telecine pipoca", k(it)) }
        assertEquals("Telecine Pipoca", LiveQuality.displayName("Telecine Pipoca FHD [H265] ⭐"))
        assertEquals(k("GLOBO EPTV RIBEIRAO PRETO SD"), k("GLOBO EPTV RIBEIRÃO PRETO FHD"))
        assertEquals(k("GLOBO SP 4K FHDR"), k("GLOBO SP HD"))
    }

    @Test
    fun canaisDiferentesNaoJuntam() {
        assertNotEquals(k("Telecine Action HD"), k("Telecine Pipoca HD"))
        assertNotEquals(k("Globo SP HD"), k("Globo RJ HD"))
        assertNotEquals(k("ESPN 2 HD"), k("ESPN HD"))
        assertNotEquals(k("ESPN²"), k("ESPN"))
        assertNotEquals(k("TV Alterosa HD"), k("TV HD"))
        assertNotEquals(k("SporTV 3 FHD"), k("SporTV FHD"))
    }

    @Test
    fun qualidadeEscolha() {
        val n = listOf("X SD", "X HD", "X FHD [ALT]", "X FHD", "X 4K", "X FHD [H265]")
        assertEquals(4, LiveQuality.pick(n, null, "best"))
        assertEquals(3, LiveQuality.pick(n, null, "FHD"))
        assertEquals(1, LiveQuality.pick(n, null, "HD"))
        assertEquals(0, LiveQuality.pick(n, null, "SD"))
        assertEquals(2, LiveQuality.pick(n, "FHD ALT", "best"))
        // preferida não existe: a mais próxima abaixo
        assertEquals(1, LiveQuality.pick(listOf("Y SD", "Y HD", "Y 4K"), null, "FHD"))
        assertEquals("SD,HD,FHD,4K", LiveQuality.tags(n))
        assertEquals(listOf("HD", "HD 2"), LiveQuality.labels(listOf("Z HD", "Z HD")))
    }

    @Test
    fun filmesRepetidosDaListaReal() {
        fun mk(n: String) = MovieDedup.parse(n).key
        assertEquals(mk("A Estação do Terror"), mk("A Estação do Terror (2025)"))
        assertEquals(mk("A Estação do Terror"), mk("A Estação do Terror [L]"))
        assertEquals(mk("Asa Branca: A Voz da Arena"), mk("Asa Branca: A Voz da Arena (2025)"))
        assertEquals(mk("#SalveRosa"), mk("#SalveRosa 4K [HDR]"))
        assertEquals(mk("\$30 (1999)"), mk("\$30 (1999) (2026)"))
        assertEquals(1999, MovieDedup.parse("\$30 (1999) (2026)").year)
        assertEquals("A Estação do Terror (2025)", MovieDedup.displayTitle("A Estação do Terror (2025)"))
        assertEquals("#SalveRosa", MovieDedup.displayTitle("#SalveRosa 4K [HDR]"))
        assertEquals("Dublado 4K HDR", MovieDedup.versionLabel("#SalveRosa 4K [HDR]", "UHD| 4K"))
        assertEquals("Legendado", MovieDedup.versionLabel("Filme [L]", "Ação"))
        assertEquals(mk("Escola da Máfia (2021)"), mk("Escola da Máfia [L] (2021)"))
        assertTrue(MovieDedup.parse("Escola da Máfia [L] (2021)").legendado)
        assertTrue(MovieDedup.parse("Qualquer Um", "♦️ Legendados ✔️").legendado)
        // títulos parecidos continuam separados
        assertNotEquals(mk("Blade Runner"), mk("Blade Runner 2049"))
        assertNotEquals(mk("Cam"), mk("Cam 2"))
        assertNotEquals(mk("Tesouro Nacional"), mk("Tesouro"))
    }

    private var nextId = 1L
    private fun row(name: String, group: String, type: String = "movie", epg: String? = null) = DedupRow(nextId++, name, group, type, epg)

    @Test
    fun agrupamentoPorCategoriaETodos() {
        val a = row("A Estação do Terror", "Top 2026")
        val b = row("A Estação do Terror (2025)", "Top 2026")
        val c = row("A Estação do Terror", "Terror")
        val l = row("A Estação do Terror [L]", "Legendados")
        val x1 = row("\$30 (1999)", "Ação"); val x2 = row("\$30 (1999)", "Ação"); val x3 = row("\$30 (1999) (2026)", "Ação")
        val r1 = row("Carrie (1976)", "Terror"); val r2 = row("Carrie (2013)", "Terror"); val r3 = row("Carrie", "Terror")
        val m = ContentDedup.compute(listOf(a, b, c, l, x1, x2, x3, r1, r2, r3))
        // na categoria: 1 card por título
        assertEquals(b.id, m[a.id]?.dupOf)
        assertNull(m[b.id]?.dupOf)
        assertNull(m[c.id]?.dupOf) // sozinho na categoria Terror
        assertTrue(listOf(x2.id, x3.id).all { m[it]?.dupOf == x1.id })
        // Todos: junta entre categorias (o dublado com ano fica visível)
        val rep = b.id
        assertTrue(listOf(a.id, c.id, l.id).all { m[it]?.dupOfAll == rep })
        assertTrue(m[rep]?.tagsAll!!.contains("Legendado"))
        // anos diferentes = filmes diferentes; o sem ano fica separado (ambíguo)
        assertNull(m[r1.id]?.dupOf); assertNull(m[r2.id]?.dupOf); assertNull(m[r3.id]?.dupOf)
    }

    @Test
    fun canaisAgrupadosUsamOQueTemEpg() {
        val sd = row("GLOBO SP SD", "Capitais", "live", "globo.sp.br")
        val fhd = row("GLOBO SP FHD", "Capitais", "live", "globo.sp.br")
        val hd = row("GLOBO SP HD", "Capitais", "live", null)
        val rj = row("GLOBO RJ HD", "Capitais", "live", "globo.rj.br")
        val m = ContentDedup.compute(listOf(sd, fhd, hd, rj))
        assertNull(m[fhd.id]?.dupOf)
        assertEquals(fhd.id, m[sd.id]?.dupOf)
        assertEquals(fhd.id, m[hd.id]?.dupOf)
        assertNull(m[rj.id])
        assertEquals("SD,HD,FHD", m[fhd.id]?.tags)
    }

    /**
     * O Android compila regex com o ICU, que recusa "]" e "}" soltos fora de [...]/{n}
     * (o Java aceita; foi o que derrubou a 0.5.57). Confere todos os padrões do arquivo.
     */
    @Test
    fun padroesValidosNoAndroidIcu() {
        val src = listOf("src/main/java/com/hugo/player/data/ContentDedup.kt", "app/src/main/java/com/hugo/player/data/ContentDedup.kt")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        val pats = Regex("rx\\(\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)\\)").findAll(src).map { m ->
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] }
                .replace("\\\\", "\\")
        }.filter { it != "(?!)" }.toList()
        assertTrue("achou padrões: ${pats.size}", pats.size >= 12)
        pats.forEach { p ->
            Regex(p) // compila no Java
            var i = 0; var inClass = false; var inBrace = false
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '\\' -> { i++; if ((p.getOrNull(i) == 'p' || p.getOrNull(i) == 'P') && p.getOrNull(i + 1) == '{') i = p.indexOf('}', i) }
                    inClass -> if (c == ']') inClass = false
                    c == '[' -> { inClass = true; if (p.getOrNull(i + 1) == '^') i++; if (p.getOrNull(i + 1) == ']') i++ }
                    c == '{' -> { val close = p.indexOf('}', i); assertTrue("'{' solto em: $p", close > i && p.substring(i + 1, close).matches(Regex("\\d+(,\\d*)?"))); i = close }
                    c == ']' || c == '}' -> throw AssertionError("'$c' sem \\ (ICU/Android recusa) em: $p")
                }
                i++
            }
            assertTrue(!inBrace)
        }
    }

    @Test
    fun colchetesVaziosSaemDoNome() {
        assertEquals("Telecine Pipoca", LiveQuality.displayName("Telecine Pipoca [HD]"))
        assertEquals("Telecine Pipoca", LiveQuality.displayName("Telecine Pipoca {FHD}"))
        assertEquals("A Estação do Terror", MovieDedup.parse("A Estação do Terror [L]").clean)
    }
}
