package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors

/** Escolha Dublado / Legendado (controle: ◀ ▶ e OK; Voltar fecha). */
@Composable
fun AudioVersionDialog(title: String, onDubbed: () -> Unit, onSubtitled: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).background(HugoColors.Panel, RoundedCornerShape(16.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text("Como quer assistir?", color = HugoColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VersionButton("Dublado", Modifier.focusRequester(first), onDubbed)
                VersionButton("Legendado", Modifier, onSubtitled)
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
}

@Composable
private fun VersionButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.width(150.dp).height(48.dp), focusedColor = HugoColors.Accent, focusBorderWidth = 3.dp, shape = RoundedCornerShape(12.dp)) { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
