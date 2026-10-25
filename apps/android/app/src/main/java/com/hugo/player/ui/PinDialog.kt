package com.hugo.player.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors

/**
 * Teclado numérico na tela (setas + OK) — sem teclado do sistema. Os números do controle também funcionam.
 * [onSubmit] devolve null se aceitou, ou a mensagem de erro.
 */
@Composable
fun PinDialog(title: String, subtitle: String? = null, onSubmit: (String) -> String?, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    fun submit() { error = onSubmit(pin); if (error != null) pin = "" }
    fun type(d: Char) { if (pin.length < 6) { pin += d; error = null } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(300.dp).background(HugoColors.Panel, RoundedCornerShape(16.dp)).padding(18.dp)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val c = ev.key.nativeKeyCode
                    when {
                        c in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> { type('0' + (c - AndroidKeyEvent.KEYCODE_0)); true }
                        c == AndroidKeyEvent.KEYCODE_DEL -> { pin = pin.dropLast(1); true }
                        else -> false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            subtitle?.let { Text(it, color = HugoColors.TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(10.dp))
            Text(if (pin.isEmpty()) "– – – –" else "●".repeat(pin.length), color = Color.White, fontSize = 22.sp, letterSpacing = 4.sp)
            error?.let { Text(it, color = Color(0xFFFCA5A5), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(10.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
            rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEachIndexed { i, k ->
                        FocusSurface(
                            onClick = {
                                when (k) {
                                    "⌫" -> pin = pin.dropLast(1)
                                    "OK" -> submit()
                                    else -> type(k[0])
                                }
                            },
                            modifier = Modifier.size(width = 72.dp, height = 46.dp).then(if (r == 0 && i == 0) Modifier.focusRequester(first) else Modifier),
                            color = if (k == "OK") HugoColors.Accent.copy(alpha = 0.6f) else HugoColors.CardAlt,
                            focusedColor = HugoColors.Accent,
                            focusBorderWidth = 3.dp,
                            shape = RoundedCornerShape(10.dp),
                        ) { _ ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(k, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
}
