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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.data.AppSettings
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.DiskCache
import com.hugo.player.player.StreamTypes
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors

@Composable
fun SettingsScreen(onBack: () -> Unit) = SettingsScreen(null, null, onBack)

@Composable
fun SettingsScreen(deviceId: String?, deviceKey: String?, onBack: () -> Unit) {
    // Garante configurações + cache em disco mesmo se a MainActivity não inicializar
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    remember { CatalogRepository.init(appContext); true }
    var ttl by remember { mutableIntStateOf(AppSettings.catalogTtlHours) }
    var liveFormat by remember { mutableStateOf(AppSettings.liveFormat) }
    var autoOpen by remember { mutableStateOf(AppSettings.autoOpenLastPlaylist) }
    var ua by remember { mutableStateOf(AppSettings.userAgent) }
    var cacheSize by remember { mutableLongStateOf(DiskCache.sizeBytes()) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(HugoColors.Background).verticalScroll(rememberScrollState()).padding(28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Configurações", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent)) { Text("Voltar") }
        }

        if (deviceId != null) {
            Section("Dispositivo") {
                Text("Device ID: $deviceId", color = Color.White, fontSize = 14.sp)
                if (deviceKey != null) Text("Device Key: $deviceKey", color = HugoColors.Accent, fontSize = 14.sp)
            }
        }

        Section("Restaurar dispositivo") {
            var restoreId by remember { mutableStateOf("") }
            var restoreKey by remember { mutableStateOf("") }
            var restoring by remember { mutableStateOf(false) }
            Text("Se você desinstalou o app e perdeu o Device ID, cole aqui o ID e a Key antigos.", color = HugoColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = restoreId, onValueChange = { restoreId = it }, label = { Text("Device ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = restoreKey, onValueChange = { restoreKey = it }, label = { Text("Device Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                restoring = true
                kotlinx.coroutines.GlobalScope.launch {
                    val ok = com.hugo.player.data.DeviceIdentity.restore(appContext, restoreId, restoreKey)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        restoring = false
                        message = if (ok) "Dispositivo restaurado. Reinicie o app." else "ID ou Key inválidos."
                    }
                }
            }, enabled = !restoring) { Text(if (restoring) "Restaurando..." else "Restaurar") }
        }

        Section("Cache da lista") {
            Text("A lista fica salva no aparelho e abre na hora. Depois do prazo, é baixada de novo.", color = HugoColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 6, 12, 24, 72).forEach { h ->
                    Chip(if (h < 24) "$h h" else "${h / 24} dia(s)", ttl == h) { ttl = h; AppSettings.catalogTtlHours = h }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Espaço usado: ${"%.1f".format(cacheSize / 1_048_576.0)} MB", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    CatalogRepository.clearAll()
                    cacheSize = DiskCache.sizeBytes()
                    message = "Cache limpo. A lista será baixada novamente."
                }) { Text("Limpar cache", color = Color.White) }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Abrir a última lista ao iniciar", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = autoOpen, onCheckedChange = { autoOpen = it; AppSettings.autoOpenLastPlaylist = it })
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
