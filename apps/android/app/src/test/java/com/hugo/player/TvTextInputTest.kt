package com.hugo.player

import android.view.KeyEvent
import com.hugo.player.ui.components.TvKeyAction
import com.hugo.player.ui.components.tvTextKeyAction
import org.junit.Assert.assertEquals
import org.junit.Test

class TvTextInputTest {
    @Test
    fun dpadPassesThroughTextFieldOnTv() {
        // CIMA/BAIXO sempre saem do campo (editando ou não), sem abrir teclado
        assertEquals(TvKeyAction.MOVE_DOWN, tvTextKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, isDown = true, isUp = false, editing = false))
        assertEquals(TvKeyAction.MOVE_UP, tvTextKeyAction(KeyEvent.KEYCODE_DPAD_UP, isDown = true, isUp = false, editing = true))
        assertEquals(TvKeyAction.CONSUME, tvTextKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, isDown = false, isUp = true, editing = false))
        // OK (ao soltar) começa a editar; durante a edição o OK vai para o campo
        assertEquals(TvKeyAction.CONSUME, tvTextKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, isDown = true, isUp = false, editing = false))
        assertEquals(TvKeyAction.START_EDIT, tvTextKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, isDown = false, isUp = true, editing = false))
        assertEquals(TvKeyAction.NONE, tvTextKeyAction(KeyEvent.KEYCODE_ENTER, isDown = false, isUp = true, editing = true))
        // ESQUERDA/DIREITA: comportamento normal
        assertEquals(TvKeyAction.NONE, tvTextKeyAction(KeyEvent.KEYCODE_DPAD_LEFT, isDown = true, isUp = false, editing = false))
    }

    @Test
    fun categoryLazyIndexForFocusRestore() {
        val f = com.hugo.player.ui.components.liveCategoryLazyIndex("__fav", "__fav", "__rec", "__all", listOf("A", "B"))
        assertEquals(0, f)
        assertEquals(2, com.hugo.player.ui.components.liveCategoryLazyIndex("__all", "__fav", "__rec", "__all", listOf("A", "B")))
        assertEquals(4, com.hugo.player.ui.components.liveCategoryLazyIndex("B", "__fav", "__rec", "__all", listOf("A", "B")))
        assertEquals(-1, com.hugo.player.ui.components.liveCategoryLazyIndex("Z", "__fav", "__rec", "__all", listOf("A", "B")))
    }
}
