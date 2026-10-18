package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.hugo.player.data.AppSettings
import com.hugo.player.data.DeviceIdentity
import com.hugo.player.data.DiskCache
import com.hugo.player.data.HugoApi
import com.hugo.player.player.StreamTypes
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun SettingsScreen(deviceId: String, deviceKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var catalogTtl by remember { mutableIntStateOf(AppSettings.catalogTtlHours) }
    var autoOpen by remember { mutableStateOf(AppSettings.autoOpenLastPlaylist) }
    var liveFormat by remember { mutableStateOf(AppSettings.liveFormat) }
    var ua by remember { mutableStateOf(AppSettings.userAgent) }
    var cacheSize by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    var restoreId by remember { mutableStateOf("") }
    var restoreKey by remember { mutableStateOf("") }
    var restoring by remember { mutableStateOf(false) }
    var licenseType by remember { mutableStateOf<String?>(null) }
    var licenseExpires by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        DiskCache.init(context)
        cacheSize = DiskCache.sizeBytes()
        licenseType = null
        try {
            val text = withContext(Dispatchers.IO) {
                HugoApi.get("/devices/status", deviceId, deviceKey)
            }
            val json = JSONObject(text)
            val lic = json.optJSONObject("license")
            licenseType = lic?.optString("type") ?: "—"
            licenseExpires = lic?.optString("expiresAt")?.takeIf { it.isNotBlank() && it != "null" }
                ?.take(10)
        } catch (_: Exception) {
            licenseType = "indisponível"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(HugoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Configurações", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent)) { Text("Voltar") }
        }

        Section("App e licença") {
            Text("Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text("Device ID: $deviceId", color = Color.White, fontSize = 13.sp)
            Text("Device Key: $deviceKey", color = HugoColors.Accent, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            val licLabel = when (licenseType?.lowercase()) {
                "trial" -> "Trial"
                "premium" -> "Premium"
                "expired" -> "Expirado"
                null -> "Carregando…"
                else -> licenseType ?: "—"
            }
            Text(
                "Licença: $licLabel" + (licenseExpires?.let { " · até $it" } ?: ""),
                color = when (licenseType?.lowercase()) {
                    "premium" -> Color(0xFF22C55E)
                    "expired" -> Color(0xFFEF4444)
                    "trial" -> HugoColors.Cyan
                    else -> HugoColors.TextMuted
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text("API: ${HugoApi.baseUrl}", color = HugoColors.TextMuted, fontSize = 11.sp)
        }

        Section("Restaurar dispositivo") {
            Text("Se você desinstalou o app e perdeu o Device ID, cole aqui o ID e a Key antigos.", color = HugoColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = restoreId, onValueChange = { restoreId = it }, label = { Text("Device ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = restoreKey, onValueChange = { restoreKey = it }, label = { Text("Device Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    restoring = true
                    message = null
                },
                enabled = !restoring && restoreId.isNotBlank() && restoreKey.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent),
            ) { Text(if (restoring) "Restaurando..." else "Restaurar") }
            LaunchedEffect(restoring) {
                if (!restoring) return@LaunchedEffect
                val ok = DeviceIdentity.restore(context, restoreId, restoreKey)
                message = if (ok) "Dispositivo restaurado. Reinicie o app." else "Não foi possível restaurar. Verifique ID e Key."
                restoring = false
            }
        }

        Section("Cache do catálogo") {
            Text("A lista fica salva no aparelho e abre na hora. Depois do prazo, é baixada de novo.", color = HugoColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6, 12, 24, 48).forEach { h ->
                    Chip("${h}h", catalogTtl == h) {
                        catalogTtl = h
                        AppSettings.catalogTtlHours = h
                        message = "Cache: ${h}h"
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Espaço usado: ${"%.1f".format(cacheSize / 1_048_576.0)} MB", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    DiskCache.clear()
                    cacheSize = 0
                    message = "Cache limpo."
                }) { Text("Limpar cache", color = Color.White) }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Abrir a última lista ao iniciar", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = autoOpen, onCheckedChange = {
                    autoOpen = it
                    AppSettings.autoOpenLastPlaylist = it
                })
            }
        }

        Section("Reprodução") {
            Text("Formato dos canais ao vivo (listas Xtream)", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("HLS (m3u8)", liveFormat == "m3u8") { liveFormat = "m3u8"; AppSettings.liveFormat = "m3u8" }
                Chip("MPEG-TS (ts)", liveFormat == "ts") { liveFormat = "ts"; AppSettings.liveFormat = "ts" }
            }
            Spacer(Modifier.height(12.dp))
            Text("User-Agent", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = ua,
                onValueChange = { ua = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = HugoColors.Accent),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { AppSettings.userAgent = ua; message = "User-Agent salvo." }, colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent)) { Text("Salvar") }
                OutlinedButton(onClick = { ua = StreamTypes.DEFAULT_USER_AGENT; AppSettings.userAgent = ua; message = "User-Agent padrão restaurado." }) {
                    Text("Padrão", color = Color.White)
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = HugoColors.Cyan, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(20.dp))
    Column(Modifier.fillMaxWidth().background(HugoColors.Card, RoundedCornerShape(14.dp)).padding(18.dp)) {
        Text(title, color = HugoColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        selected = selected,
        color = HugoColors.CardAlt,
        focusedColor = HugoColors.Accent.copy(alpha = 0.5f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 13.sp)
        }
    }
}
