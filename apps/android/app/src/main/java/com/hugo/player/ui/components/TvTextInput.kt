package com.hugo.player.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.hugo.player.data.DeviceIdentity
import kotlinx.coroutines.delay

/**
 * Campo de texto no controle remoto (TV / Fire TV): o foco do D-pad só "passa" pelo campo, sem abrir o
 * teclado. OK edita (abre o teclado); CIMA/BAIXO sempre saem do campo; perder o foco fecha o teclado.
 * No celular não muda nada.
 *
 * Uso: `val tv = rememberTvTextInput()` e no campo `readOnly = tv.readOnly, modifier = Modifier.tvTextInput(tv)`.
 */
@Stable
class TvTextInputState(val isTv: Boolean) {
    var editing by mutableStateOf(false)
    internal var refocusing = false
    val readOnly: Boolean get() = isTv && !editing
}

fun isTvDevice(context: Context): Boolean = runCatching {
    DeviceIdentity.isTv(context) ||
        context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}.getOrDefault(false)

@Composable
fun rememberTvTextInput(): TvTextInputState {
    val context = LocalContext.current
    return remember { TvTextInputState(isTvDevice(context)) }
}

/** O que fazer com uma tecla do D-pad num campo de texto na TV. */
enum class TvKeyAction { NONE, MOVE_UP, MOVE_DOWN, START_EDIT, CONSUME }

fun tvTextKeyAction(keyCode: Int, isDown: Boolean, isUp: Boolean, editing: Boolean): TvKeyAction = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (isDown) TvKeyAction.MOVE_UP else TvKeyAction.CONSUME
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (isDown) TvKeyAction.MOVE_DOWN else TvKeyAction.CONSUME
    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ->
        when {
            editing -> TvKeyAction.NONE
            isUp -> TvKeyAction.START_EDIT
            else -> TvKeyAction.CONSUME
        }
    else -> TvKeyAction.NONE
}

fun Modifier.tvTextInput(state: TvTextInputState): Modifier = if (!state.isTv) this else composed {
    val fm = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(state.editing) {
        if (!state.editing) return@LaunchedEffect
        // reentra no foco já editável para o campo abrir a sessão de texto, então mostra o teclado
        state.refocusing = true
        delay(30)
        runCatching { fm.clearFocus(force = true) }
        runCatching { requester.requestFocus() }
        state.refocusing = false
        delay(30)
        kb?.show()
    }
    this
        .focusRequester(requester)
        .onFocusChanged { f ->
            if (!f.isFocused && state.editing && !state.refocusing) { state.editing = false; kb?.hide() }
        }
        .onPreviewKeyEvent { ev ->
            val action = tvTextKeyAction(ev.key.nativeKeyCode, ev.type == KeyEventType.KeyDown, ev.type == KeyEventType.KeyUp, state.editing)
            when (action) {
                TvKeyAction.NONE -> false
                TvKeyAction.CONSUME -> true
                TvKeyAction.START_EDIT -> { state.editing = true; true }
                TvKeyAction.MOVE_UP, TvKeyAction.MOVE_DOWN -> {
                    if (state.editing) { state.editing = false; kb?.hide() }
                    runCatching { fm.moveFocus(if (action == TvKeyAction.MOVE_UP) FocusDirection.Up else FocusDirection.Down) }
                    true
                }
            }
        }
}
