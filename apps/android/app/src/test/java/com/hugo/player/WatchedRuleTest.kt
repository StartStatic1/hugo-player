package com.hugo.player

import com.hugo.player.data.WatchHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchedRuleTest {
    @Test fun threshold() { assertEquals(0.92f, WatchHistory.WATCHED_FRACTION) }
}
