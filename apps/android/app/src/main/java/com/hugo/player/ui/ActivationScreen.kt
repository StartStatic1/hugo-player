package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.data.DeviceIdentity
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoIconButton
import com.hugo.player.ui.components.HugoLogo
import kotlinx.coroutines.launch

/**
 * Primeira tela: mostra o Device ID + Device Key gerados e salvos neste aparelho
 * (DeviceIdentity) e registra o dispositivo no servidor.
 */
@Composable
fun ActivationScreen(onActivated: (String, String) -> Unit) {
    val context = LocalContext.current
    val identity = remember { DeviceIdentity.get(context) }
    val scope = rememberCoroutineScope()
    var registered by remember { mutableStateOf<Boolean?>(null) }
    val continueFocus = remember { FocusRequester() }

    suspend fun register() {
        registered = null
        registered = DeviceIdentity.ensureRegistered(context)
    }
    LaunchedEffect(Unit) {
        register()
        runCatching { continueFocus.requestFocus() }
    }

    Row(
        Modifier.fillMaxSize().background(HugoColors.Background).verticalScroll(rememberScrollState()).padding(40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HugoLogo(markSize = 48.dp, textSize = 32.sp)
            Spacer(Modifier.height(24.dp))
            Text("Ative seu dispositivo", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Entre no site do Hugo Player com o Device ID e a Device Key ao lado e adicione suas playlists. Depois, toque em Continuar.",
                color = HugoColors.TextMuted, fontSize = 14.sp, lineHeight = 20.sp
            )
            Spacer(Modifier.height(20.dp))
            listOf("Abra o site do Hugo Player", "Entre com Device ID + Key", "Adicione sua lista M3U").forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).background(HugoColors.Accent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(s, color = Color.White, fontSize = 14.sp)
                }
            }
        }

        Column(
            Modifier.weight(1f)
                .background(HugoColors.Card, RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF1F2638), RoundedCornerShape(20.dp))
                .padding(28.dp)
        ) {
            Text("DEVICE ID", color = HugoColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Text(identity.deviceId, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(18.dp))
            Text("DEVICE KEY", color = HugoColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Text(identity.deviceKey, color = HugoColors.Accent, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (registered) {
                    null -> {
                        CircularProgressIndicator(Modifier.size(16.dp), color = HugoColors.Accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Registrando dispositivo...", color = HugoColors.TextMuted, fontSize = 13.sp)
                    }
                    true -> if (DeviceIdentity.isTrialDenied(context)) {
                        Icon(Icons.Default.CloudOff, null, tint = Color(0xFFFBBF24), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Este aparelho já usou o teste grátis. Para usar o Hugo Player, ative um plano no site com o Device ID e a Key acima.",
                            color = Color(0xFFFBBF24), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f),
                        )
                    } else {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Dispositivo registrado", color = Color(0xFF34D399), fontSize = 13.sp)
                    }
                    false -> {
                        Icon(Icons.Default.CloudOff, null, tint = Color(0xFFFBBF24), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sem conexão com o servidor", color = Color(0xFFFBBF24), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        HugoIconButton(Icons.Default.Refresh, "Tentar de novo", onClick = { scope.launch { register() } }, label = "Tentar")
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = {
                    DeviceIdentity.setActivationDone(context)
                    onActivated(identity.deviceId, identity.deviceKey)
                },
                colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp).focusRequester(continueFocus)
            ) {
                Text("Continuar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
            }
            Spacer(Modifier.height(10.dp))
            val (openSupport, supportDialog) = rememberSupportWhatsapp()
            com.hugo.player.ui.components.FocusSurface(
                onClick = openSupport,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                color = Color(0x1A25D366),
                focusedColor = Color(0x4025D366),
                focusBorderColor = WhatsappGreen,
                focusScale = 1.02f,
                shape = RoundedCornerShape(12.dp),
            ) { _ ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Precisa de ajuda? Suporte no WhatsApp", color = WhatsappGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            supportDialog()
        }
    }
}
