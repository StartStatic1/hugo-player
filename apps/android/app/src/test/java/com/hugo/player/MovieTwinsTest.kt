package com.hugo.player

import com.hugo.player.data.MovieTwins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieTwinsTest {
    @Test fun candidatesMatchSqlReplace() {
        for (l in listOf("Escola da Máfia [L] (2021)", "#Natal [L] (2012)", "Host [L]", "(MAD)² [L] (2007)")) {
            assertTrue(MovieTwins.isLegendado(l))
            val dub = MovieTwins.dubbedName(l)
            assertFalse(MovieTwins.isLegendado(dub))
            assertTrue(l, MovieTwins.legendadoCandidates(dub).contains(l))
        }
        assertEquals("Escola da Máfia (2021)", MovieTwins.dubbedName("Escola da Máfia [L] (2021)"))
    }

    @Test fun singlesUntouched() {
        assertTrue(MovieTwins.legendadoCandidates("Filme [L] (2020)").isEmpty())
        assertFalse(MovieTwins.isLegendado("Lisboa (2020)"))
        assertFalse(MovieTwins.isLegendado("Filme [Leg] (2020)"))
    }
}
