package com.hugo.player

import com.hugo.player.data.TmdbMatch
import com.hugo.player.data.TmdbMatch.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbMatchTest {
    @Test fun usesYear() {
        val c = listOf(Candidate(0, listOf("Matrix", "The Matrix"), 2021), Candidate(1, listOf("Matrix", "The Matrix"), 1999))
        assertEquals(1, TmdbMatch.pick("Matrix (1999)", c)?.index)
        assertEquals(1999, TmdbMatch.yearOf("Matrix (1999)"))
    }
    @Test fun rejectsWrongYearOrName() {
        assertNull(TmdbMatch.pick("Escola da Máfia [L] (2021)", listOf(Candidate(0, listOf("Escola da Máfia"), 2005))))
        assertNull(TmdbMatch.pick("Vingança (2025)", listOf(Candidate(0, listOf("Velozes e Furiosos"), 2025))))
    }
    @Test fun accentsAndTags() {
        assertEquals(0, TmdbMatch.pick("Irreversível [L] (2002)", listOf(Candidate(0, listOf("Irreversivel", "Irréversible"), 2002)))?.index)
        assertEquals("Escola da Máfia", TmdbMatch.cleanTitle("Escola da Máfia [L] (2021)"))
    }
    @Test fun noYearNeedsExactName() {
        assertEquals(0, TmdbMatch.pick("Dark", listOf(Candidate(0, listOf("Dark"), 2017)))?.index)
        assertNull(TmdbMatch.pick("Dark", listOf(Candidate(0, listOf("Dark Matter"), 2024))))
    }
}
