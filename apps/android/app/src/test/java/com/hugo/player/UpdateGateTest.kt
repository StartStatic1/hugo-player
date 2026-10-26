package com.hugo.player

import com.hugo.player.data.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateGateTest {
    @Test fun blocksOnlyWhenServerSaysTooOld() {
        assertEquals(true to false, AppUpdate.decide(local = 43, remote = 44, min = 44, cachedMin = 0))
        assertEquals(false to false, AppUpdate.decide(local = 44, remote = 44, min = 44, cachedMin = 0))
        assertEquals(false to false, AppUpdate.decide(local = 40, remote = 44, min = 0, cachedMin = 0))
    }

    @Test fun offlineFailsOpenUnlessAlreadyBlocked() {
        assertEquals(false to true, AppUpdate.decide(local = 44, remote = null, min = null, cachedMin = 0))
        assertEquals(true to true, AppUpdate.decide(local = 43, remote = null, min = null, cachedMin = 44))
    }
}
