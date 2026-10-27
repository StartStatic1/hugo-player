package com.hugo.player

import com.hugo.player.data.SeriesEpisodes
import com.hugo.player.data.WatchHistory
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.player.PlaybackRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeContinueTest {
    private fun item(key: String, title: String, sub: String?, at: Long, pid: String = "p1") =
        WatchHistory.ProgressItem(key, title, "u:$key", 60_000, 600_000, pid, at, subtitle = sub)

    private fun ep(s: Int, e: Int) = ChannelEntity(name = "S${s}E$e", url = "ep:$s:$e", logo = null, group = "", type = "series", playlistId = "p1", seriesName = "X", season = s, episode = e)

    @Test fun episodeDetection() {
        assertTrue(WatchHistory.isEpisode(item("a", "X", "T2 · E05 · Piloto", 1)))
        assertTrue(WatchHistory.isEpisode(item("a", "X", "Temporada 3", 1)))
        assertFalse(WatchHistory.isEpisode(item("a", "Filme", null, 1)))
        assertFalse(WatchHistory.isEpisode(item("a", "Filme", "Dublado", 1)))
    }

    @Test fun oneCardPerSeriesKeepsNewest() {
        val list = listOf(
            item("e5", "X", "T2 · E05", 30),
            item("m", "Filme", null, 20),
            item("e3", "X", "T2 · E03", 10),
            item("y", "X", "T1 · E01", 5, pid = "p2"),
        )
        assertEquals(listOf("e5", "m", "y"), WatchHistory.groupContinue(list).map { it.key })
    }

    @Test fun queueAfterByUrlOrCode() {
        val eps = listOf(ep(2, 1), ep(1, 2), ep(1, 1), ep(2, 2)).sortedBy { it.episode }
        val build = { e: ChannelEntity -> PlaybackRequest(url = e.url, title = "X") }
        assertEquals(listOf("ep:2:1", "ep:2:2"), SeriesEpisodes.queueAfter(eps, "ep:1:2", null, build).map { it.url })
        assertEquals(listOf("ep:2:2"), SeriesEpisodes.queueAfter(eps, "outra-url", "T2 · E01 · Nome", build).map { it.url })
        assertTrue(SeriesEpisodes.queueAfter(eps, "outra-url", null, build).isEmpty())
        assertEquals(2 to 5, SeriesEpisodes.parseCode("T2 · E05 · Piloto"))
    }
}
