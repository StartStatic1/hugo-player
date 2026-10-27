package com.hugo.player

import com.hugo.player.data.ListExpiry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListExpiryTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test fun levels() {
        assertEquals(ListExpiry.Level.OK, ListExpiry.describe(now + 30 * day, now)!!.level)
        assertEquals(ListExpiry.Level.SOON, ListExpiry.describe(now + 7 * day, now)!!.level)
        assertEquals(ListExpiry.Level.SOON, ListExpiry.describe(now + 1, now)!!.level)
        assertEquals(ListExpiry.Level.EXPIRED, ListExpiry.describe(now - 1, now)!!.level)
        assertEquals(ListExpiry.Level.UNLIMITED, ListExpiry.describe(-1L, now)!!.level)
        assertNull(ListExpiry.describe(0L, now))
    }

    @Test fun parseIso() {
        assertEquals(1_795_392_000_000L, ListExpiry.parseIso("2026-11-23T00:00:00.000Z"))
        assertEquals(1_795_392_000_000L, ListExpiry.parseIso("2026-11-23T00:00:00Z"))
        assertNull(ListExpiry.parseIso(null))
        assertNull(ListExpiry.parseIso("null"))
    }

    @Test fun parseExpDate() {
        assertEquals(1_795_392_000_000L, ListExpiry.parseExpDate("1795392000"))
        assertEquals(1_795_392_000_000L, ListExpiry.parseExpDate(1795392000))
        assertEquals(1_795_392_000_000L, ListExpiry.parseExpDate(1.795392E9))
        for (v in listOf(null, "", "0", "null", "Ilimitado", 0)) assertNull(ListExpiry.parseExpDate(v))
    }
}
