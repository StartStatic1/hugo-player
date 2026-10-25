package com.hugo.player

import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.data.epg.LiveEpg
import androidx.media3.common.PlaybackException
import com.hugo.player.data.epg.EpgProgram
import com.hugo.player.ui.LiveAttempt
import com.hugo.player.ui.epgDisplayList
import com.hugo.player.ui.playingFormat
import com.hugo.player.ui.previewLayout
import com.hugo.player.ui.headerChipsThatFit
import com.hugo.player.ui.liveDpadRight
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
        assertTrue(chips.containsAll(listOf("FHD", "H.265")))
        // formato só aparece quando se sabe o que está tocando (a URL da lista não manda)
        assertTrue("TS" !in chips && "HLS" !in chips)
        assertTrue("TS" in qualityChips(c, 1080, playing = playingFormat("http://h/live/u/p/1.ts", false)))
        assertEquals("HLS", playingFormat("http://h/live/u/p/1.ts", forceHls = true))
        assertEquals("4K", qualityChips(c, videoHeight = 2160).first())
    }

    /** Regressão 0.5.20: painel XUI devolve HTML (200) no .m3u8 e só entrega vídeo no .ts. */
    @Test
    fun m3u8FallsBackToTsAndRemembersServer() {
        LiveFormatMemory.clear()
        val u = "http://panel.example.test/live/u/p/1479281.m3u8"
        // automático: TS primeiro, HLS de reserva
        assertEquals(
            listOf(LiveAttempt("http://panel.example.test/live/u/p/1479281.ts", false), LiveAttempt(u, true)),
            liveAttempts(u),
        )
        assertEquals("http://h/live/u/p/9.ts", liveAttempts("http://h/live/u/p/9.ts").first().url)
        // TS falhou e HLS funcionou neste servidor: HLS passa a vir primeiro
        LiveFormatMemory.remember("http://h/live/u/p/9.m3u8")
        assertEquals("http://h/live/u/p/10.m3u8", liveAttempts("http://h/live/u/p/10.ts").first().url)
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

    @Test
    fun epgListDropsDuplicateStartsAndEndedShows() {
        val now = 1_000L
        val guide = listOf(
            EpgProgram("B", 1_500, 2_000), EpgProgram("A", 900, 1_500),
            EpgProgram("A dup", 900, 1_500), EpgProgram("old", 100, 500),
        )
        val list = epgDisplayList(guide, emptyList(), now)
        assertEquals(listOf("A", "B"), list.map { it.title })
        assertEquals(list.size, list.map { it.start }.toSet().size)
    }

    @Test
    fun epgListFallsBackToNowNextAndHandlesEmpty() {
        val nn = listOf(EpgProgram("now", 900, 1_500), EpgProgram("same", 900, 1_500))
        assertEquals(1, epgDisplayList(emptyList(), nn, 1_000).size)
        assertTrue(epgDisplayList(emptyList(), emptyList(), 1_000).isEmpty())
    }

    @Test
    fun previewFillsColumnWidthAndKeepsEpgRows() {
        // celular deitado (~340-400dp de coluna) e TV (~480dp)
        for ((w, h) in listOf(260f to 340f, 320f to 343f, 320f to 400f, 320f to 482f, 380f to 600f)) {
            val l = previewLayout(w, h)
            val inner = w - 2 * l.pad
            val epg = h - l.boxHeight - l.cardHeight - 3 * l.pad
            assertTrue("w=$w h=$h epg=$epg", epg >= (if (l.short) 3 * 44f else 3 * 56f) - 0.5f)
            // vídeo sempre 16:9 e nunca esticado; corte <= 22% da altura
            assertEquals(16f / 9f, l.videoWidth / l.videoHeight, 0.01f)
            assertTrue(l.boxHeight >= l.videoHeight * 0.78f - 0.5f)
            assertTrue(l.boxWidth <= inner + 0.5f)
        }
        // nos tamanhos comuns a prévia ocupa a largura toda (sem faixas vazias dos lados)
        assertEquals(304f, previewLayout(320f, 343f).boxWidth, 0.5f)
        assertEquals(296f, previewLayout(320f, 482f).boxWidth, 0.5f)
    }

    @Test
    fun headerChipsDropWhatDoesNotFit() {
        val lens = listOf(10, 8, 10, 20)
        assertEquals(4, headerChipsThatFit(700f, lens, compact = false))
        assertEquals(1, headerChipsThatFit(140f, lens, compact = true))
        assertEquals(0, headerChipsThatFit(40f, lens, compact = true))
    }

    @Test
    fun previewLayoutStaysFiniteOnTvAndOddSizes() {
        for ((w, h) in listOf(380f to 482f, 380f to 0f, 0f to 0f, Float.NaN to 400f, 380f to Float.POSITIVE_INFINITY, 2f to 2f)) {
            val l = previewLayout(w, h)
            for (v in listOf(l.boxWidth, l.boxHeight, l.videoWidth, l.videoHeight)) assertTrue("w=$w h=$h v=$v", v.isFinite() && v > 0f)
        }
        assertTrue(liveDpadRight(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, isDown = true))
        assertTrue(!liveDpadRight(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, isDown = false))
        assertTrue(!liveDpadRight(android.view.KeyEvent.KEYCODE_DPAD_LEFT, isDown = true))
    }

    @Test
    fun crashLogFormatKeepsCauseAndIsBounded() {
        val text = com.hugo.player.data.CrashLog.format(IllegalStateException("FocusRequester is not initialized"), "main", "0.5.27", now = 0L)
        assertTrue(text.contains("FocusRequester is not initialized"))
        assertTrue(text.contains("0.5.27"))
        val deep = RuntimeException("x".repeat(50_000))
        assertTrue(com.hugo.player.data.CrashLog.format(deep, "main", "v").length <= 12_000)
    }
}
