package com.hugo.player.ui

import com.hugo.player.ui.components.tvTextInput
import com.hugo.player.ui.components.rememberTvTextInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.BuildConfig
import com.hugo.player.data.CrashLog
import androidx.compose.ui.text.font.FontFamily
import com.hugo.player.data.AdultLock
import com.hugo.player.data.AppSettings
import com.hugo.player.data.DiskCache
import com.hugo.player.data.HugoApi
import com.hugo.player.data.WatchHistory
import com.hugo.player.player.StreamTypes
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val SITE_URL = "https://hugo.streamflixvip.online"

@Composable
fun SettingsScreen(deviceId: String, deviceKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var catalogTtl by remember { mutableIntStateOf(AppSettings.catalogTtlHours) }
    var autoOpen by remember { mutableStateOf(AppSettings.autoOpenLastPlaylist) }
    var liveFormat by remember { mutableStateOf(AppSettings.liveFormat) }
    var ua by remember { mutableStateOf(AppSettings.userAgent) }
    var cacheSize by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    var licenseType by remember { mutableStateOf<String?>(null) }
    var licensePlan by remember { mutableStateOf<String?>(null) }
    var licenseExpires by remember { mutableStateOf<String?>(null) }
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    var lockOn by remember { mutableStateOf(AdultLock.isEnabled(context)) }
    // fluxo do PIN: "off" (confirmar p/ desligar), "verify" (antes de trocar), "new", "confirm"
    var pinStep by remember { mutableStateOf<String?>(null) }
    var newPin by remember { mutableStateOf("") }
    var enableAfterSet by remember { mutableStateOf(false) }

    fun copy(label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        message = "$label copiado"
    }

    LaunchedEffect(Unit) {
        DiskCache.init(context)
        cacheSize = DiskCache.sizeBytes()
        licenseType = null
        try {
            val text = withContext(Dispatchers.IO) { HugoApi.get("/devices/status", deviceId, deviceKey) }
            val json = JSONObject(text)
            val lic = json.optJSONObject("license")
            licenseType = lic?.optString("type") ?: "—"
            licensePlan = lic?.optString("plan")?.takeIf { it.isNotBlank() && it != "null" }
            licenseExpires = lic?.optString("expiresAt")?.takeIf { it.isNotBlank() && it != "null" }?.take(10)
        } catch (_: Exception) {
            licenseType = "indisponível"
            licensePlan = null
        }
    }

    Column(
        Modifier.fillMaxSize().background(HugoColors.Background).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Configurações", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent)) { Text("Voltar") }
        }

        Section("Dispositivo") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("v${BuildConfig.VERSION_NAME}", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                LicenseBadge(licenseType, licensePlan, licenseExpires)
            }
            Spacer(Modifier.height(8.dp))
            CompactCopy("ID", deviceId) { copy("ID", deviceId) }
            CompactCopy("Key", deviceKey, valueColor = HugoColors.Accent) { copy("Key", deviceKey) }
            CompactCopy("API", HugoApi.baseUrl, valueColor = HugoColors.TextMuted) { copy("API", HugoApi.baseUrl) }
        }

        Section("Controle dos pais (PIN adulto)") {
            fun toggleLock() {
                val on = !lockOn
                when {
                    on && !AdultLock.hasPin(context) -> { enableAfterSet = true; pinStep = "new" }
                    on -> { AdultLock.setEnabled(context, true); lockOn = true; message = "Bloqueio ligado" }
                    else -> pinStep = "off"
                }
            }
            // linha inteira focável (controle remoto: OK liga/desliga), com borda no foco
            FocusSurface(onClick = { toggleLock() }, color = HugoColors.CardAlt, focusedColor = HugoColors.Accent.copy(alpha = 0.35f), focusScale = 1f, focusBorderWidth = 2.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) { _ ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bloquear categorias adultas: " + if (lockOn) "LIGADO" else "desligado", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Adultos / XXX / +18 em TV, filmes e séries pedem PIN (1 vez por sessão)", color = HugoColors.TextMuted, fontSize = 11.sp)
                    }
                    Switch(checked = lockOn, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = HugoColors.Accent))
                }
            }
            Spacer(Modifier.height(6.dp))
            FocusSurface(
                onClick = { enableAfterSet = false; pinStep = if (AdultLock.hasPin(context)) "verify" else "new" },
                color = HugoColors.CardAlt, focusedColor = HugoColors.Accent.copy(alpha = 0.35f), focusScale = 1f, focusBorderWidth = 2.dp, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(42.dp),
            ) { _ ->
                Text(if (AdultLock.hasPin(context)) "Alterar PIN" else "Definir PIN", color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
            }
        }

        Section("Diagnóstico") {
            val log = crash
            if (log == null) {
                Text("Nenhum fechamento inesperado registrado.", color = HugoColors.TextMuted, fontSize = 12.sp)
            } else {
                Text("Último fechamento do app", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    log.lineSequence().take(30).joinToString("\n"),
                    color = HugoColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(HugoColors.CardAlt, RoundedCornerShape(8.dp)).padding(8.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy("Erro", log) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("Copiar", fontSize = 12.sp) }
                    OutlinedButton(onClick = { CrashLog.clear(context); crash = null; message = "Registro apagado" }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("Apagar", fontSize = 12.sp) }
                }
            }
        }

        when (pinStep) {
            "off" -> PinDialog("PIN atual", "Para desligar o bloqueio", onSubmit = { p ->
                if (AdultLock.check(context, p)) { AdultLock.setEnabled(context, false); lockOn = false; pinStep = null; message = "Bloqueio desligado"; null } else "PIN errado"
            }, onDismiss = { pinStep = null })
            "verify" -> PinDialog("PIN atual", "Para trocar o PIN", onSubmit = { p ->
                if (AdultLock.check(context, p)) { pinStep = "new"; null } else "PIN errado"
            }, onDismiss = { pinStep = null })
            "new" -> PinDialog("Novo PIN", "4 a 6 números", onSubmit = { p ->
                if (AdultLock.validPinFormat(p)) { newPin = p; pinStep = "confirm"; null } else "Use 4 a 6 números"
            }, onDismiss = { pinStep = null })
            "confirm" -> PinDialog("Repita o PIN", null, onSubmit = { p ->
                if (p == newPin) {
                    AdultLock.setPin(context, p)
                    if (enableAfterSet) { AdultLock.setEnabled(context, true); lockOn = true }
                    pinStep = null; newPin = ""; message = if (lockOn) "PIN salvo · bloqueio ligado" else "PIN salvo"; null
                } else { pinStep = "new"; "Não confere, digite de novo" }
            }, onDismiss = { pinStep = null; newPin = "" })
        }

        Section("Reprodução") {
            Text("Formato ao vivo", color = HugoColors.TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("HLS", liveFormat == "hls") { liveFormat = "hls"; AppSettings.liveFormat = "hls"; message = "Formato: HLS" }
                Chip("TS", liveFormat == "ts") { liveFormat = "ts"; AppSettings.liveFormat = "ts"; message = "Formato: MPEG-TS" }
            }
            Spacer(Modifier.height(10.dp))
            Text("User-Agent", color = HugoColors.TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            val uaInput = rememberTvTextInput()
            OutlinedTextField(
                value = ua, onValueChange = { ua = it }, readOnly = uaInput.readOnly,
                modifier = Modifier.fillMaxWidth().tvTextInput(uaInput), singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                supportingText = if (uaInput.isTv && !uaInput.editing) ({ Text("OK para editar", fontSize = 10.sp, color = HugoColors.TextDim) }) else null,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { AppSettings.userAgent = ua.trim(); message = "User-Agent salvo" }, colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Salvar", fontSize = 13.sp) }
                OutlinedButton(onClick = { ua = StreamTypes.DEFAULT_USER_AGENT; AppSettings.userAgent = StreamTypes.DEFAULT_USER_AGENT; message = "User-Agent padrão" }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Padrão", fontSize = 13.sp) }
            }
        }

        Section("Cache e histórico") {
            Text("Validade da lista no aparelho", color = HugoColors.TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(6, 12, 24, 48).forEach { h -> Chip("${h}h", catalogTtl == h) { catalogTtl = h; AppSettings.catalogTtlHours = h; message = "Cache: ${h}h" } }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${"%.1f".format(cacheSize / 1_048_576.0)} MB", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { DiskCache.clear(); cacheSize = 0L; message = "Cache limpo" }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("Limpar", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Abrir última playlist", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = autoOpen, onCheckedChange = { autoOpen = it; AppSettings.autoOpenLastPlaylist = it }, colors = SwitchDefaults.colors(checkedTrackColor = HugoColors.Accent))
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { WatchHistory.clearContinue(context); message = "Continuar assistindo limpo" }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) { Text("Limpar continuar assistindo", fontSize = 13.sp) }
        }

        Section("Site") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("hugo.streamflixvip.online", color = Color.White, fontSize = 13.sp)
                    Text("Ativar e gerenciar dispositivos", color = HugoColors.TextMuted, fontSize = 11.sp)
                }
                IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE_URL))) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = "Abrir site", tint = HugoColors.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }

        message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = HugoColors.Accent, fontSize = 12.sp) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LicenseBadge(type: String?, plan: String?, expires: String?) {
    val (text, color) = when (type) {
        null -> "..." to HugoColors.TextMuted
        "trial" -> ("Teste" + (expires?.let { " · $it" } ?: "")) to Color(0xFFFBBF24)
        "premium" -> when (plan) {
            "lifetime" -> "Premium Vitalício" to Color(0xFF34D399)
            else -> ("Premium Anual" + (expires?.let { " · $it" } ?: "")) to Color(0xFF34D399)
        }
        "expired" -> "Expirada" to Color(0xFFF87171)
        else -> (type ?: "—") to HugoColors.TextMuted
    }
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun CompactCopy(label: String, value: String, valueColor: Color = Color.White, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = HugoColors.TextMuted, fontSize = 11.sp, modifier = Modifier.width(36.dp))
        Text(value, color = valueColor, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
        IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copiar $label", tint = HugoColors.TextMuted, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(title, color = HugoColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Column(Modifier.fillMaxWidth().background(HugoColors.Card, RoundedCornerShape(12.dp)).padding(12.dp), content = content)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) HugoColors.Accent else HugoColors.CardAlt
    val fg = if (selected) Color.White else HugoColors.TextMuted
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(label, fontSize = 12.sp) }
}
