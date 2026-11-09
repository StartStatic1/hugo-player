package com.hugo.player

import com.hugo.player.ui.StoreBuild
import org.junit.Assert.assertEquals
import org.junit.Test

/** Textos do build Google Play: "playlist"/"lista" viram "conexão" (pt/en/es), mantendo maiúsculas. */
class StoreWordingTest {
    @Test fun portuguese() {
        assertEquals("Conexões", StoreBuild.wording("Playlists", "pt"))
        assertEquals("Nenhuma conexão ainda", StoreBuild.wording("Nenhuma playlist ainda", "pt"))
        assertEquals("Adicione sua conexão M3U", StoreBuild.wording("Adicione sua lista M3U", "pt"))
        assertEquals("Validade da conexão no aparelho", StoreBuild.wording("Validade da lista no aparelho", "pt"))
        assertEquals("Listagem", StoreBuild.wording("Listagem", "pt"))
    }

    @Test fun englishAndSpanish() {
        assertEquals("Add connection", StoreBuild.wording("Add playlist", "en"))
        assertEquals("No connections yet", StoreBuild.wording("No playlists yet", "en"))
        assertEquals("Aún no hay conexiones", StoreBuild.wording("Aún no hay playlists", "es"))
        assertEquals("¡Listo! 3 elementos", StoreBuild.wording("¡Listo! 3 elementos", "es"))
    }
}

class StoreExtraTest {
    @Test fun extraEnglish() {
        assertEquals("Switch, add or edit connections", StoreBuild.extra("Trocar, adicionar ou editar listas", "en"))
        assertEquals("18 channels · 12 movies · 4 series · updated 5 minutes ago", StoreBuild.extra("18 canais · 12 filmes · 4 séries · atualizada 5 minutes ago", "en"))
        assertEquals("Minha TV · Expires on 08/05/2027", StoreBuild.extra("Minha TV · Vence em 08/05/2027", "en"))
        assertEquals(null, StoreBuild.extra("Trocar, adicionar ou editar listas", "pt"))
    }
}
