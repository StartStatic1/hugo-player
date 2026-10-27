package com.hugo.player

import com.hugo.player.ui.PROGRESS_SAVE_EVERY_MS
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeProgressTest {
    @Test fun progressIsSavedOftenEnoughToSurviveAppKill() {
        // fechar pelos recentes / atualizar / sistema matar: perde no máximo ~15 s
        assertTrue(PROGRESS_SAVE_EVERY_MS in 5_000L..15_000L)
    }
}
