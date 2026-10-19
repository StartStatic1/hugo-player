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
import com.hugo.player.data.DiskCache
import com.hugo.player.data.HugoApi
import com.hugo.player.ui.theme.HugoColors
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
            when (licenseType) {
                null -> Text("Licença: carregando...", color = HugoColors.TextMuted, fontSize = 13.sp)
                "trial" -> Text(
                    "Licença: teste" + (licenseExpires?.let { " até $it" } ?: ""),
                    color = Color(0xFFFBBF24),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                "premium" -> Text(
                    "Licença: premium" + (licenseExpires?.let { " até $it" } ?: ""),
                    color = Color(0xFF34D399),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                "expired" -> Text("Licença: expirada", color = Color(0xFFF87171), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else -> Text("Licença: $licenseType", color = HugoColors.TextMuted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("API: ${HugoApi.baseUrl}", color = HugoColors.TextMuted, fontSize = 11.sp)
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
                    cacheSize = 0L
                    message = "Cache limpo"
                }) { Text("Limpar") }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Abrir última playlist ao iniciar", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = autoOpen,
                    onCheckedChange = {
                        autoOpen = it
                        AppSettings.autoOpenLastPlaylist = it
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = HugoColors.Accent),
                )
            }
        }

        Section("Reprodução") {
            Text("Formato dos canais ao vivo (listas Xtream)", color = HugoColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("HLS (m3u8)", liveFormat == "hls") {
                    liveFormat = "hls"
                    AppSettings.liveFormat = "hls"
                    message = "Formato: HLS"
                }
                Chip("MPEG-TS (ts)", liveFormat == "ts") {
                    liveFormat = "ts"
                    AppSettings.liveFormat = "ts"
                    message = "Formato: MPEG-TS"
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("User-Agent", color = HugoColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = ua,
                onValueChange = { ua = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        AppSettings.userAgent = ua.trim()
                        message = "User-Agent salvo"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent),
                ) { Text("Salvar") }
                OutlinedButton(onClick = {
                    ua = AppSettings.DEFAULT_UA
                    AppSettings.userAgent = AppSettings.DEFAULT_UA
                    message = "User-Agent padrão"
                }) { Text("Padrão") }
            }
        }

        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = HugoColors.Accent, fontSize = 13.sp)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(22.dp))
    Text(title, color = HugoColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(HugoColors.Card, RoundedCornerShape(14.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) HugoColors.Accent else HugoColors.Card2
    val fg = if (selected) Color.White else HugoColors.TextMuted
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) { Text(label, fontSize = 13.sp) }
}
