package com.hugo.player

import com.hugo.player.data.PlaylistRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressão: playlist sem EPG (epgUrl null) derrubava a lista com NPE no ConcurrentHashMap. */
class PlaylistEpgLinkTest {
    @Test
    fun playlistWithoutEpgDoesNotCrash() {
        PlaylistRepository.rememberEpgLink("p1", null)
        PlaylistRepository.rememberEpgLink("p2", "")
        assertNull(PlaylistRepository.epgUrlFor("p1"))
        assertNull(PlaylistRepository.epgUrlFor("p2"))
    }

    @Test
    fun setAndClearEpgLink() {
        PlaylistRepository.rememberEpgLink("p3", " http://h/epg.xml.gz ")
        assertEquals("http://h/epg.xml.gz", PlaylistRepository.epgUrlFor("p3"))
        PlaylistRepository.rememberEpgLink("p3", null)
        assertNull(PlaylistRepository.epgUrlFor("p3"))
    }

    @Test
    fun epgUrlValidation() {
        assertTrue(PlaylistRepository.isValidEpgUrl(""))
        assertTrue(PlaylistRepository.isValidEpgUrl("https://h.test/epg.xml"))
        assertFalse(PlaylistRepository.isValidEpgUrl("ftp://h.test/epg.xml"))
        assertFalse(PlaylistRepository.isValidEpgUrl("epg.xml"))
    }
}
