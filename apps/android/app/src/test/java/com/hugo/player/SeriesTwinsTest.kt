package com.hugo.player

import com.hugo.player.data.SeriesTwins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesTwinsTest {
    @Test fun pairsClearTwins() {
        val p = SeriesTwins.pairs(listOf("Goblin (2016)", "Goblin [L] (2016)", "iCarly (2007)", "iCarly [L] (2008)", "Dark", "Dark [L]", "Psychoville [L] (2009)"))
        assertEquals("Goblin (2016)", p["Goblin [L] (2016)"])
        assertEquals("iCarly (2007)", p["iCarly [L] (2008)"])
        assertEquals("Dark", p["Dark [L]"])
        assertFalse(p.containsKey("Psychoville [L] (2009)"))
    }
    @Test fun normalizesAccentsAndTags() {
        val p = SeriesTwins.pairs(listOf("Sala de Aula dos Heróis (2023)", "Sala de Aula dos Herois [LEG] (2023)", "La Casa de Papel [DUB] (2017)", "La Casa de Papel (Legendado) (2017)"))
        assertEquals("Sala de Aula dos Heróis (2023)", p["Sala de Aula dos Herois [LEG] (2023)"])
        assertEquals("La Casa de Papel [DUB] (2017)", p["La Casa de Papel (Legendado) (2017)"])
    }
    @Test fun conservative() {
        // ano muito diferente, um só com ano, ou dois candidatos empatados: não junta
        assertTrue(SeriesTwins.pairs(listOf("Duna (1984)", "Duna [L] (2021)")).isEmpty())
        assertTrue(SeriesTwins.pairs(listOf("Duna", "Duna [L] (2021)")).isEmpty())
        assertTrue(SeriesTwins.pairs(listOf("X (2020)", "X! (2020)", "X [L] (2020)")).isEmpty())
        assertFalse(SeriesTwins.key("Legião (2017)").legendado)
        assertFalse(SeriesTwins.key("Lisboa (2020)").legendado)
    }
}
