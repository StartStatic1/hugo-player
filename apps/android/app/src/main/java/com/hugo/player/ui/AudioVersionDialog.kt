package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/**
 * Escolha entre várias versões (qualidade do canal; Dublado / Legendado / 4K do filme).
 * Lista vertical para o controle (▲▼ e OK; Voltar fecha). [selected] ganha o foco inicial.
 */
@Composable
fun VersionPickerDialog(
    title: String,
    question: String,
    options: List<String>,
    selected: Int? = null,
    extraLabel: String? = null,
    onExtra: () -> Unit = {},
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val focusIdx = (selected ?: 0).coerceIn(0, (options.size - 1).coerceAtLeast(0))
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).background(HugoColors.Panel, RoundedCornerShape(16.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(question, color = HugoColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Column(
                Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEachIndexed { i, label ->
                    PickerRow(
                        if (i == selected) "$label  ✓" else label,
                        if (i == focusIdx) Modifier.focusRequester(first) else Modifier,
                    ) { onPick(i) }
                }
                if (extraLabel != null) PickerRow(extraLabel, Modifier, muted = true, onClick = onExtra)
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
}

@Composable
private fun PickerRow(label: String, modifier: Modifier, muted: Boolean = false, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick, modifier = modifier.width(300.dp).height(46.dp), focusedColor = HugoColors.Accent,
        color = if (muted) HugoColors.CardAlt else HugoColors.Card, focusBorderWidth = 3.dp, shape = RoundedCornerShape(12.dp),
    ) { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
