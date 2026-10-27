package com.hugo.player

import com.hugo.player.data.UpdateStep
import com.hugo.player.data.updatePercent
import com.hugo.player.data.updateStepText
import com.hugo.player.ui.busy
import com.hugo.player.ui.updateButtonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateProgressTest {
    @Test fun percentOnlyWhenSizeKnown() {
        assertNull(updatePercent(500, -1))
        assertNull(updatePercent(500, 0))
        assertEquals(50, updatePercent(7_469_762, 14_939_523))
        assertEquals(100, updatePercent(20, 10))
    }

    @Test fun textsForEachStep() {
        val mb = 1_048_576L
        assertTrue(updateStepText(UpdateStep.Downloading(5 * mb, 10 * mb)).startsWith("Baixando atualização… 50%"))
        assertTrue(updateStepText(UpdateStep.Downloading(5 * mb, 10 * mb)).contains("de 10,0 MB"))
        assertEquals("Baixando atualização… 5,0 MB", updateStepText(UpdateStep.Downloading(5 * mb, -1)))
        assertEquals("Baixando atualização…", updateStepText(UpdateStep.Downloading(0, -1)))
        assertEquals("Abrindo instalador…", updateStepText(UpdateStep.Opening))
        assertEquals("Sem internet", updateStepText(UpdateStep.Failed("Sem internet")))
    }

    @Test fun buttonAndBusy() {
        assertEquals("Baixar e instalar", updateButtonText(UpdateStep.Idle, "Baixar e instalar"))
        assertEquals("Baixando… 42%", updateButtonText(UpdateStep.Downloading(42, 100), "x"))
        assertEquals("Tentar de novo", updateButtonText(UpdateStep.Failed("e"), "x"))
        assertTrue(UpdateStep.Downloading(0, -1).busy)
        assertTrue(UpdateStep.Opening.busy)
        assertTrue(UpdateStep.Checking.busy)
        assertFalse(UpdateStep.Failed("e").busy)
        assertFalse(UpdateStep.Opened.busy)
        assertFalse(UpdateStep.Idle.busy)
    }
}
