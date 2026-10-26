package com.hugo.player

import com.hugo.player.data.ExternalSubs
import com.hugo.player.player.PlaybackRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalSubsTest {
    @Test fun movieUsesCleanTitleAndYear() {
        val q = ExternalSubs.queryFor(PlaybackRequest(url = "u", title = "A Origem [L] (2010)", subtitle = "Filme", isLive = false))!!
        assertEquals("A Origem", q.title)
        assertEquals(2010, q.year)
        assertNull(q.season)
    }

    @Test fun episodeUsesSeasonAndEpisode() {
        val q = ExternalSubs.queryFor(PlaybackRequest(url = "u", title = "Dark (2017)", subtitle = "T2 · E05 · Episódio 5", isLive = false))!!
        assertEquals("Dark", q.title)
        assertEquals(2, q.season)
        assertEquals(5, q.episode)
        assertNull(q.year)
    }

    @Test fun liveHasNoSubtitleSearch() {
        assertNull(ExternalSubs.queryFor(PlaybackRequest(url = "u", title = "Globo", isLive = true)))
    }
}
