package com.hugo.player

import com.hugo.player.update.InAppUpdatePolicy
import com.hugo.player.update.InAppUpdatePolicy.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InAppUpdatePolicyTest {
    @Test fun flexibleByDefault() {
        assertEquals(Flow.FLEXIBLE, InAppUpdatePolicy.choose(0, null, immediateAllowed = true, flexibleAllowed = true))
        assertEquals(Flow.FLEXIBLE, InAppUpdatePolicy.choose(3, 6, immediateAllowed = true, flexibleAllowed = true))
    }

    @Test fun immediateForHighPriorityOrStale() {
        assertEquals(Flow.IMMEDIATE, InAppUpdatePolicy.choose(4, null, immediateAllowed = true, flexibleAllowed = true))
        assertEquals(Flow.IMMEDIATE, InAppUpdatePolicy.choose(5, 0, immediateAllowed = true, flexibleAllowed = true))
        assertEquals(Flow.IMMEDIATE, InAppUpdatePolicy.choose(0, 7, immediateAllowed = true, flexibleAllowed = true))
    }

    @Test fun fallsBackToWhatPlayAllows() {
        assertEquals(Flow.FLEXIBLE, InAppUpdatePolicy.choose(5, 30, immediateAllowed = false, flexibleAllowed = true))
        assertEquals(Flow.IMMEDIATE, InAppUpdatePolicy.choose(0, null, immediateAllowed = true, flexibleAllowed = false))
        assertNull(InAppUpdatePolicy.choose(5, 30, immediateAllowed = false, flexibleAllowed = false))
    }
}
