package com.hugo.player

import com.hugo.player.data.MovieDedup
import com.hugo.player.data.XtreamGroups
import com.hugo.player.data.extinfName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamGroupsTest {
    @Test fun extinfNameUsesCommaAfterAttributes() {
        assertEquals("Boa Sorte, Divirta-se, Não Morra (2024)",
            extinfName("#EXTINF:-1 tvg-id=\"\" tvg-name=\"Boa Sorte, Divirta-se, Não Morra (2024)\" tvg-logo=\"http://x/a,b.jpg\" group-title=\"FILMES\",Boa Sorte, Divirta-se, Não Morra (2024)"))
        assertEquals("Canal X", extinfName("#EXTINF:-1,Canal X"))
        assertEquals("Filme, (2024) [L]", extinfName("#EXTINF:-1 group-title=\"A, B\",Filme, (2024) [L]"))
        // aspas dentro do tvg-name (pares): continua certo
        assertEquals("Bono: \"U2\" (2025)", extinfName("#EXTINF:-1 tvg-name=\"Bono: \"U2\" (2025)\" group-title=\"FILMES\",Bono: \"U2\" (2025)"))
        assertEquals("Canal", extinfName("#EXTINF:-1 tvg-name=\"x\","))
    }

    @Test fun movieTitleWithoutPanelMarks() {
        assertEquals("A Morte do Demônio: Em Chamas (2026)", MovieDedup.displayTitle("+ A Morte do Demônio: Em Chamas (2026)"))
        assertEquals("Comer, Rezar, Ladrar (2024)", MovieDedup.displayTitle("- Comer, Rezar, Ladrar (2024)"))
        assertEquals("Disney+", MovieDedup.displayTitle("Disney+"))
    }

    @Test fun regroupsByName() {
        val g = XtreamGroups()
        g.addCategory("movie", "5", "Filmes | Ação")
        g.addCategory("movie", "9", "Filmes | Terror")
        g.addItem("movie", "+ Filme A (2026)", "9")
        g.addItem("movie", "Filme B", "5")
        g.addItem("movie", "Sem categoria", "77")
        g.addCategory("series", "1", "Séries | NETFLIX")
        g.addItem("series", "+ Ozark", "1")
        assertEquals("Filmes | Terror", g.groupFor("movie", "+ Filme A (2026)"))
        assertEquals("Filmes | Ação", g.groupFor("movie", "x", "filme b"))
        assertNull(g.groupFor("movie", "Sem categoria"))
        assertNull(g.groupFor("live", "Filme B"))
        assertEquals("Séries | NETFLIX", g.groupFor("series", "+ Ozark"))
        assertEquals(0, g.position("movie", "Filmes | Ação"))
        assertEquals(1, g.position("movie", "Filmes | Terror"))
    }

    @Test fun apiUrlFromGetPhp() {
        assertEquals("http://h.example:80/player_api.php?username=u&password=p&action=get_vod_streams",
            XtreamGroups.apiUrl("http://h.example:80/get.php?username=u&password=p&type=m3u_plus&output=ts", "get_vod_streams"))
        assertNull(XtreamGroups.apiUrl("http://h.example/lista.m3u?username=u&password=p", "x"))
        assertNull(XtreamGroups.apiUrl("http://h.example/get.php?type=m3u", "x"))
    }
}
