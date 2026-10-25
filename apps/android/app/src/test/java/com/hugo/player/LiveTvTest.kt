package com.hugo.player

import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.data.epg.LiveEpg
import androidx.media3.common.PlaybackException
import com.hugo.player.ui.LiveAttempt
import com.hugo.player.ui.LiveFormat
import com.hugo.player.ui.LiveFormatMemory
import com.hugo.player.ui.isLiveFormatFailure
import com.hugo.player.ui.liveAltVariant
import com.hugo.player.ui.liveAttempts
import com.hugo.player.ui.qualityChips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvTest {
    @Test
    fun xmltvTimeWithOffset() {
        // 2026-10-25 12:00:00 -0300 == 15:00 UTC
        assertEquals(1792940400000L, LiveEpg.xmltvTime("20261025120000 -0300"))
        assertEquals(1792940400000L, LiveEpg.xmltvTime("20261025150000 +0000"))
        assertEquals(1792940400000L, LiveEpg.xmltvTime("20261025150000"))
        assertNull(LiveEpg.xmltvTime(""))
        assertNull(LiveEpg.xmltvTime("abc"))
    }

    @Test
    fun nameKeyMatchesBackendNormalization() {
        assertEquals("globo", LiveEpg.epgNameKey("Globo HD"))
        assertEquals("espn", LiveEpg.epgNameKey("ESPN [FHD]"))
        assertEquals("record", LiveEpg.epgNameKey("Récord (BR)"))
        assertEquals("sportv2", LiveEpg.epgNameKey("SporTV 2 4K"))
        assertEquals(LiveEpg.epgNameKey("HBO Family H265"), LiveEpg.epgNameKey("hbo family"))
    }

    @Test
    fun xtreamXmltvFromPlaylistUrl() {
        assertEquals(
            "http://panel.example.test:8080/xmltv.php?username=u1&password=p1",
            LiveEpg.xtreamXmltv("http://panel.example.test:8080/get.php?username=u1&password=p1&type=m3u_plus&output=ts"),
        )
        assertNull(LiveEpg.xtreamXmltv("http://example.test/list.m3u"))
    }

    @Test
    fun tsAndHlsVariants() {
        assertEquals("http://h/live/u/p/1.m3u8", liveAltVariant("http://h/live/u/p/1.ts"))
        assertEquals("http://h/u/p/1.ts?x=1", liveAltVariant("http://h/u/p/1.m3u8?x=1"))
        assertNull(liveAltVariant("http://h/u/p/1"))
    }

    @Test
    fun qualityFromNameAndFormat() {
        val c = ChannelEntity(name = "Globo FHD H265", url = "http://h/u/p/1.ts", logo = null, group = "BR", type = "live", playlistId = "p")
        val chips = qualityChips(c)
        assertTrue(chips.containsAll(listOf("FHD", "H.265", "TS")))
        assertEquals("4K", qualityChips(c, videoHeight = 2160).first())
    }

    /** Regressão 0.5.20: painel XUI devolve HTML (200) no .m3u8 e só entrega vídeo no .ts. */
    @Test
    fun m3u8FallsBackToTsAndRemembersServer() {
        LiveFormatMemory.clear()
        val u = "http://panel.example.test/live/u/p/1479281.m3u8"
        assertEquals(
            listOf(LiveAttempt(u, true), LiveAttempt("http://panel.example.test/live/u/p/1479281.ts", false)),
            liveAttempts(u),
        )
        // HTML no lugar do manifesto = falha de formato -> tenta a próxima
        assertTrue(isLiveFormatFailure(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, null))
        assertTrue(isLiveFormatFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null))
        assertTrue(isLiveFormatFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertTrue(!isLiveFormatFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        // o .ts funcionou: o próximo canal do mesmo servidor já começa no .ts
        LiveFormatMemory.remember("http://panel.example.test/live/u/p/1479281.ts")
        assertEquals("http://panel.example.test/live/u/p/2.ts", liveAttempts("http://panel.example.test/live/u/p/2.m3u8").first().url)
        // forçado no painel de fonte
        assertEquals(listOf(LiveAttempt("http://h/live/u/p/3.m3u8", true)), liveAttempts("http://h/live/u/p/3.ts", LiveFormat.HLS))
        // sem extensão: detecta pelo conteúdo, depois HLS
        assertEquals(listOf(LiveAttempt("http://h/u/p/4", false), LiveAttempt("http://h/u/p/4", true)), liveAttempts("http://h/u/p/4"))
        LiveFormatMemory.clear()
    }
}
