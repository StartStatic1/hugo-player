package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.update.InAppUpdateState

/**
 * Faixa embaixo da tela quando a Google Play terminou de baixar a versão nova (fluxo FLEXIBLE).
 * "Reiniciar" instala (completeUpdate); "Depois" esconde até a próxima vez que o app abrir.
 * Na TV o foco vai para "Reiniciar" quando a faixa aparece (controle remoto alcança sem procurar).
 */
@Composable
fun InAppUpdateBanner(hidden: Boolean) {
    val ready by InAppUpdateState.ready.collectAsState()
    var dismissed by remember { mutableStateOf(false) }
    if (!ready || dismissed || hidden) return
    val restartFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { restartFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.widthIn(max = 640.dp)
                .background(HugoColors.Card, RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF1F2638), RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SystemUpdate, null, tint = HugoColors.Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("Atualização pronta.", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(14.dp))
            FocusSurface(
                onClick = { dismissed = true },
                modifier = Modifier.height(44.dp),
                shape = RoundedCornerShape(10.dp),
                focusBorderWidth = 2.dp,
            ) { _ ->
                Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text("Depois", color = HugoColors.TextMuted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            FocusSurface(
                onClick = { InAppUpdateState.complete() },
                modifier = Modifier.height(44.dp).focusRequester(restartFocus),
                color = HugoColors.Accent,
                focusedColor = HugoColors.Accent,
                focusBorderWidth = 3.dp,
                focusBorderColor = Color.White,
                focusScale = 1.05f,
                shape = RoundedCornerShape(10.dp),
            ) { _ ->
                Box(Modifier.fillMaxHeight().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                    Text("Reiniciar", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
