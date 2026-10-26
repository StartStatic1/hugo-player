package com.hugo.player

import com.hugo.player.ui.cleanEpisodeTitle
import com.hugo.player.ui.formatEpisodeDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeTextTest {
    @Test fun stripsSeriesAndCode() {
        assertEquals("", cleanEpisodeTitle("#blackAF (2020) S01E01", "#blackAF"))
        assertEquals("Cat's in the Bag", cleanEpisodeTitle("Breaking Bad S01E02 - Cat's in the Bag", "Breaking Bad"))
        assertEquals("Segredos", cleanEpisodeTitle("Dark - S01E01 - Segredos", "Dark"))
        assertEquals("O Começo", cleanEpisodeTitle("S01E03 - O Começo", "Qualquer"))
        assertEquals("Piloto", cleanEpisodeTitle("Piloto", "Série X"))
        assertEquals("", cleanEpisodeTitle("Série X", "Série X"))
        assertEquals("", cleanEpisodeTitle("Goblin [L] (2016)", "Goblin [L] (2016)"))
        assertEquals("", cleanEpisodeTitle("Goblin [L] (2016) S01E05", "Goblin [L] (2016)"))
    }

    @Test fun duration() {
        assertEquals("43 min", formatEpisodeDuration(2550))
        assertEquals("1 h 07 min", formatEpisodeDuration(4000))
        assertNull(formatEpisodeDuration(0))
        assertNull(formatEpisodeDuration(null))
    }
}
