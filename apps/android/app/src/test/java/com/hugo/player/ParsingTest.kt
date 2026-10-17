package com.hugo.player

import com.hugo.player.data.DeviceIdentity
import com.hugo.player.data.SeriesParser
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.ui.episodeTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    @Test fun seriesPatterns() {
        assertEquals(SeriesParser.Info("Breaking Code", 1, 2), SeriesParser.parse("Breaking Code S01E02"))
        assertEquals(SeriesParser.Info("La Casa", 3, 10), SeriesParser.parse("La Casa - S03 E10 Final"))
        assertEquals(SeriesParser.Info("Dark", 2, 5), SeriesParser.parse("Dark 2x05"))
        assertEquals(SeriesParser.Info("Novela", 1, 12), SeriesParser.parse("Novela Temporada 1 Episódio 12"))
        assertEquals(SeriesParser.Info("Show", 4, null), SeriesParser.parse("Show Temporada 4"))
        assertNull(SeriesParser.parse("Filme Qualquer (2023)"))
    }

    @Test fun episodeTitleStripsSeriesAndCode() {
        val ep = ChannelEntity(name = "Breaking Code S01E02 O Retorno", url = "u", logo = null, group = "g", type = "series", playlistId = "p", seriesName = "Breaking Code", season = 1, episode = 2)
        assertEquals("O Retorno", episodeTitle(ep))
    }

    @Test fun deviceIdIsMacLikeAndRandom() {
        val mac = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
        val a = DeviceIdentity.generateId()
        val b = DeviceIdentity.generateId()
        assertTrue(a, mac.matches(a))
        assertNotEquals(a, b)
        val first = a.substring(0, 2).toInt(16)
        assertEquals("bit localmente administrado", 2, first and 0x02)
        assertEquals("unicast", 0, first and 0x01)
        assertNotEquals(DeviceIdentity.LEGACY_ID, a)
    }

    @Test fun deviceKeyHasNoAmbiguousChars() {
        repeat(50) {
            val k = DeviceIdentity.generateKey()
            assertEquals(8, k.length)
            assertTrue(k, k.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
        }
    }
}
