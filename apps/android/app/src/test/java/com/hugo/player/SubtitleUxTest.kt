package com.hugo.player

import com.hugo.player.data.ExternalSubs
import com.hugo.player.ui.TrackOption
import com.hugo.player.ui.inferLanguage
import com.hugo.player.ui.isForeignAudio
import com.hugo.player.ui.portugueseTextTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleUxTest {
    @Test fun languageFromTrackNameWhenFileHasNoTag() {
        assertEquals("en", inferLanguage(null, "Inglês"))
        assertEquals("en", inferLanguage("und", "English 5.1"))
        assertEquals("en", inferLanguage("", "ENG"))
        assertEquals("pt", inferLanguage(null, "Português (Brasil)"))
        assertEquals("pt", inferLanguage(null, "Dublado"))
        assertEquals("es", inferLanguage(null, "Español Latino"))
        assertEquals("eng", inferLanguage("eng", "Qualquer"))
        assertNull(inferLanguage(null, "Faixa 2"))
        assertNull(inferLanguage(null, null))
    }

    @Test fun foreignAudioEvenWithoutTag() {
        val pt = TrackOption(0, 0, "Português", "pt")
        val en = TrackOption(1, 0, "Inglês", "en")
        val unknown = TrackOption(1, 0, "Áudio 2", null)
        assertTrue(isForeignAudio(en, listOf(pt, en)))
        assertFalse(isForeignAudio(pt, listOf(pt, en)))
        // faixa sem nome num filme que tem outra em português → é a estrangeira
        assertTrue(isForeignAudio(unknown, listOf(pt, unknown)))
        assertFalse(isForeignAudio(unknown, listOf(unknown)))
    }

    @Test fun forcedPortugueseSubtitleIsIgnored() {
        val forced = TrackOption(2, 0, "Português (forçada)", "pt", forced = true)
        val full = TrackOption(3, 0, "Português", "pt")
        assertNull(portugueseTextTrack(listOf(forced)))
        assertEquals(3, portugueseTextTrack(listOf(forced, full))?.groupIndex)
    }

    @Test fun srtParsingAndSyncOffset() {
        val srt = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Olá</i>\r\n\r\n2\n00:00:03,000 --> 00:00:04,000\n{\\an8}Seis, sete...\nlinha 2\n\n3\n00:00:05,000 --> 00:00:05,000\nvazia\n"
        val cues = ExternalSubs.parseSrt(srt)
        assertEquals(2, cues.size)
        assertEquals("Olá", cues[0].text)
        assertEquals("Seis, sete...\nlinha 2", cues[1].text)
        assertEquals("Olá", ExternalSubs.cueAt(cues, 1_200, 0)?.text)
        assertNull(ExternalSubs.cueAt(cues, 2_700, 0))
        // legenda atrasada 1 s (+1s): a fala de 1,0 s aparece em 2,0 s
        assertNull(ExternalSubs.cueAt(cues, 1_200, 1_000))
        assertEquals("Olá", ExternalSubs.cueAt(cues, 2_200, 1_000)?.text)
        // adiantar 0,5 s
        assertEquals("Seis, sete...\nlinha 2", ExternalSubs.cueAt(cues, 2_600, -500)?.text)
        assertTrue(ExternalSubs.parseSrt("lixo sem tempos").isEmpty())
    }

    @Test fun offsetLabel() {
        assertEquals("0s", ExternalSubs.formatOffset(0))
        assertEquals("+1s", ExternalSubs.formatOffset(1_000))
        assertEquals("+0,25s", ExternalSubs.formatOffset(250))
        assertEquals("−1,5s", ExternalSubs.formatOffset(-1_500))
    }

    @Test fun releaseHintNeverSendsListCredentials() {
        assertEquals("Filme (2024) Filme.2024.1080p.BluRay.mkv", ExternalSubs.releaseHint("Filme (2024)", "http://h/movies/Filme.2024.1080p.BluRay.mkv?token=abc"))
        assertEquals("Filme", ExternalSubs.releaseHint("Filme", "http://h/movie/usuario/senha/12345.mkv"))
        assertEquals("Filme", ExternalSubs.releaseHint("Filme", "http://h/get.php?username=a&password=b"))
    }
}
