package com.hugo.player

import com.hugo.player.data.AdultLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultLockTest {
    @Test fun detects() {
        for (n in listOf("ADULTOS", "Adultos +18", "XXX Filmes", "Canais +18", "18+ Hot", "Adult")) assertTrue(n, AdultLock.isAdultCategory(n))
        for (n in listOf("Filmes 2018", "Desenhos", "Adultério? não", "Série A", "Esportes 180")) assertFalse(n, AdultLock.isAdultCategory(n))
    }
    @Test fun pinFormat() {
        assertTrue(AdultLock.validPinFormat("1234")); assertFalse(AdultLock.validPinFormat("12a4")); assertFalse(AdultLock.validPinFormat("123"))
    }
}
