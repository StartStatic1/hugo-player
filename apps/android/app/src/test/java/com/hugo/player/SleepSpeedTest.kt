package com.hugo.player

import com.hugo.player.ui.SleepOption
import com.hugo.player.ui.nextSpeed
import com.hugo.player.ui.sleepRemainingLabel
import com.hugo.player.ui.speedLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepSpeedTest {
    @Test fun speeds() {
        assertEquals(1.25f, nextSpeed(1f))
        assertEquals(0.5f, nextSpeed(2f))
        assertEquals("1,5x", speedLabel(1.5f))
        assertEquals("2x", speedLabel(2f))
    }

    @Test fun sleepCycle() {
        assertEquals(SleepOption.END_OF_EPISODE, SleepOption.cycle(SleepOption.M90, true))
        assertEquals(SleepOption.OFF, SleepOption.cycle(SleepOption.M90, false))
        assertEquals("30 min", sleepRemainingLabel(29 * 60_000L + 1))
        assertEquals("45 s", sleepRemainingLabel(45_000))
    }
}
