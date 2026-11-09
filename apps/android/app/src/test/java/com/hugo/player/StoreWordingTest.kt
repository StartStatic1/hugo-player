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
