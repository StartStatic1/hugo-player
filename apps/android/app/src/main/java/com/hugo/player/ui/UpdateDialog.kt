package com.hugo.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.BuildConfig
import com.hugo.player.data.AppUpdate
import com.hugo.player.data.UpdateInfo
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoLogo
import kotlinx.coroutines.launch

/**
 * Atualização: confere a versão no arranque (e quando o servidor responde 426).
 *  - abaixo da versão mínima → tela que NÃO dá para fechar até atualizar;
 *  - só existe versão nova → popup com "Depois".
 * Sem internet o app não trava (só continua bloqueado se o último aviso já dizia que estava velho).
 */
@Composable
fun UpdateGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    val tick by AppUpdate.recheck.collectAsState()
    LaunchedEffect(tick) { info = AppUpdate.checkWithGate(context) }

    val current = info
    if (current != null && current.mandatory) {
        ForcedUpdateScreen(current, onRetryCheck = { AppUpdate.requestRecheck() })
        return
    }
    content()
    if (current != null && !dismissed) OptionalUpdateDialog(current) { dismissed = true }
}

@Composable
private fun ForcedUpdateScreen(info: UpdateInfo, onRetryCheck: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val buttonFocus = remember { FocusRequester() }
    BackHandler { /* bloqueado: não sai sem atualizar */ }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { buttonFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(HugoColors.Background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).padding(32.dp)
                .background(HugoColors.Card, RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF1F2638), RoundedCornerShape(20.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HugoLogo(markSize = 40.dp, textSize = 26.sp)
            Spacer(Modifier.height(20.dp))
            Icon(Icons.Default.SystemUpdate, null, tint = HugoColors.Accent, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("Atualização obrigatória", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Esta versão do Hugo Player (v${BuildConfig.VERSION_NAME}) não funciona mais. " +
                    "Instale a versão ${info.versionName.ifBlank { "nova" }} para continuar assistindo.",
                color = HugoColors.TextMuted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            )
            if (info.offline) {
                Spacer(Modifier.height(10.dp))
                Text("Sem internet agora. Conecte o aparelho à internet para baixar a atualização.", color = Color(0xFFFBBF24), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            FocusSurface(
                onClick = {
                    if (downloading) return@FocusSurface
                    downloading = true; error = null; progress = 0
                    scope.launch {
                        val err = AppUpdate.downloadAndInstall(context, info.apkUrl) { progress = it }
                        downloading = false
                        progress = null
                        if (err != null) { error = err; if (info.offline) onRetryCheck() }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(buttonFocus),
                color = HugoColors.Accent,
                focusedColor = HugoColors.Accent,
                focusBorderWidth = 3.dp,
                focusBorderColor = Color.White,
                focusScale = 1.03f,
                shape = RoundedCornerShape(14.dp),
            ) { _ ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            downloading && progress != null && progress!! > 0 -> "Baixando… ${progress}%"
                            downloading -> "Baixando…"
                            error != null -> "Tentar de novo"
                            else -> "Baixar e instalar"
                        },
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (downloading && (progress ?: 0) > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { (progress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth(), color = HugoColors.Accent)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color(0xFFF87171), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(14.dp))
            Text("Depois de instalar, abra o Hugo Player de novo.", color = HugoColors.TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OptionalUpdateDialog(current: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text(if (current.versionName.isNotBlank()) "Atualização ${current.versionName}" else "Atualização disponível") },
        text = {
            Text(buildString {
                append(current.notes.ifBlank { "Nova versão disponível." })
                if (error != null) { append("\n\n"); append(error) }
            })
        },
        confirmButton = {
            TextButton(enabled = !downloading, onClick = {
                downloading = true
                error = null
                scope.launch {
                    val err = AppUpdate.downloadAndInstall(context, current.apkUrl)
                    downloading = false
                    if (err != null) error = err else onDismiss()
                }
            }) { Text(if (downloading) "Baixando..." else "Baixar e instalar") }
        },
        dismissButton = { TextButton(enabled = !downloading, onClick = onDismiss) { Text("Depois") } },
    )
}
