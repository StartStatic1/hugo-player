package com.hugo.player.ui

import com.hugo.player.ui.components.tvTextInput
import com.hugo.player.ui.components.rememberTvTextInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.BuildConfig
import com.hugo.player.data.CrashLog
import androidx.compose.ui.text.font.FontFamily
import com.hugo.player.data.AdultLock
import com.hugo.player.data.AppSettings
import com.hugo.player.data.DiskCache
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.ListExpiry
import com.hugo.player.data.PlaylistRepository
import com.hugo.player.data.HugoApi
import com.hugo.player.data.WatchHistory
import com.hugo.player.player.StreamTypes
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val SITE_URL = "https://hugo.streamflixvip.online"

/** Categorias do menu lateral (ordem = ordem na tela). */
private enum class SettingsCat(val label: String, val hint: String, val icon: ImageVector) {
    INFO("Informação geral", "Aparelho, licença e lista em uso", Icons.Rounded.Info),
    PLAYER("Player de vídeo", "Formato ao vivo e User-Agent", Icons.Rounded.PlayCircle),
    SUBS("Legendas", "Aparência da legenda", Icons.Rounded.Subtitles),
    PARENTAL("Controle dos pais", "PIN para conteúdo adulto", Icons.Rounded.Lock),
    CACHE("Cache e histórico", "Lista salva no aparelho e histórico", Icons.Rounded.Storage),
    DIAG("Diagnóstico", "Último fechamento inesperado", Icons.Rounded.BugReport),
    ABOUT("Site e suporte", "Gerenciar dispositivos pelo site", Icons.Rounded.Public),
}

/**
 * Configurações em 2 painéis: menu à esquerda (cima/baixo troca a categoria) e o conteúdo à direita.
 * D-pad: direita ou OK entra no painel; esquerda/voltar retorna ao item ativo do menu.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(deviceId: String, deviceKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var catalogTtl by remember { mutableIntStateOf(AppSettings.catalogTtlHours) }
    var subBg by remember { mutableStateOf(AppSettings.subtitleBackground) }
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
    var showKey by remember { mutableStateOf(false) }

    var selected by rememberSaveable { mutableStateOf(SettingsCat.INFO) }
    val railReq = remember { SettingsCat.entries.associateWith { FocusRequester() } }
    var panelFocused by remember { mutableStateOf(false) }

    val playlistId = LiveTvCache.playlistId
    val playlistName = LiveTvCache.playlistName
    val expiryVersion by ListExpiry.version.collectAsState()
    val expiry = remember(playlistId, expiryVersion) { playlistId?.let { ListExpiry.get(context, it) } }
    val expiryState = remember(playlistId, expiryVersion) { playlistId?.let { ListExpiry.state(context, it) } }
    LaunchedEffect(playlistId) { playlistId?.let { ListExpiry.refreshIfStale(context, it, LiveTvCache.playlistUrl) } }

    fun copy(label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        message = "$label copiado"
    }

    LaunchedEffect(Unit) {
        runCatching { railReq.getValue(selected).requestFocus() }
        cacheSize = withContext(Dispatchers.IO) { CatalogRepository.storageBytes(context) }
        licenseType = null
        try {
            val text = withContext(Dispatchers.IO) { HugoApi.get("/devices/status", deviceId, deviceKey) }
            val json = JSONObject(text)
            val lic = json.optJSONObject("license")
            licenseType = if (lic?.optBoolean("trialDenied") == true) "trial_denied" else lic?.optString("type") ?: "—"
            licensePlan = lic?.optString("plan")?.takeIf { it.isNotBlank() && it != "null" }
            licenseExpires = lic?.optString("expiresAt")?.takeIf { it.isNotBlank() && it != "null" }?.take(10)
        } catch (_: Exception) {
            licenseType = "indisponível"
            licensePlan = null
        }
    }
    LaunchedEffect(message) { if (message != null) { kotlinx.coroutines.delay(3500); message = null } }

    // voltar dentro do painel: volta para o menu (o segundo voltar sai)
    BackHandler(enabled = panelFocused && pinStep == null) { runCatching { railReq.getValue(selected).requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(HugoColors.Background)) {
        val compact = maxWidth < 600.dp
        val twoCols = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            // ── menu lateral ──
            Column(
                Modifier.width(if (compact) 72.dp else 250.dp).fillMaxHeight()
                    .background(Color(0xFF0D0F1A))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = 18.dp)
                    // entrar no menu (vindo do painel) cai sempre no item ativo
                    .focusProperties { enter = { railReq.getValue(selected) } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!compact) {
                    Text("Configurações", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                    Text("Hugo Player v${BuildConfig.VERSION_NAME}", color = HugoColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
                }
                SettingsCat.entries.forEach { cat ->
                    RailItem(
                        label = cat.label, icon = cat.icon, compact = compact, active = cat == selected,
                        modifier = Modifier.focusRequester(railReq.getValue(cat)),
                        onFocused = { selected = cat },
                        onClick = { selected = cat; focusManager.moveFocus(FocusDirection.Right) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                RailItem(label = "Voltar", icon = Icons.AutoMirrored.Rounded.ArrowBack, compact = compact, active = false, modifier = Modifier, onFocused = {}, onClick = onBack)
            }

            // ── painel da categoria ──
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .onFocusChanged { panelFocused = it.hasFocus }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 22.dp)
            ) {
                Text(selected.label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(selected.hint, color = HugoColors.TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))

                when (selected) {
                    SettingsCat.INFO -> {
                        val (licText, licColor) = licenseInfo(licenseType, licensePlan, licenseExpires)
                        val maskedKey = if (deviceKey.length > 4) "•".repeat(6) + deviceKey.takeLast(4) else deviceKey
                        val cards = listOf<@Composable (Modifier) -> Unit>(
                            { m -> InfoCard("Device ID", deviceId, "OK para copiar", m, onClick = { copy("ID", deviceId) }) },
                            { m -> InfoCard("Key", if (showKey) deviceKey else maskedKey, if (showKey) "OK oculta · segurar copia" else "OK mostra · segurar copia", m, valueColor = HugoColors.Accent, onClick = { showKey = !showKey }, onLongClick = { copy("Key", deviceKey) }) },
                            { m -> InfoCard("Licença", licText, null, m, valueColor = licColor) },
                            { m -> InfoCard("Playlist em uso", playlistName ?: "Nenhuma aberta", null, m) },
                            { m ->
                                InfoCard(
                                    "Vencimento da lista",
                                    when {
                                        playlistId == null -> "—"
                                        expiry != null -> expiry.label.removePrefix("Lista ")
                                        expiryState == ListExpiry.State.NOT_XTREAM -> "Lista M3U não informa vencimento"
                                        ListExpiry.isChecking(playlistId) || expiryState == ListExpiry.State.UNCHECKED -> "Verificando…"
                                        else -> "—"
                                    },
                                    null, m,
                                    valueColor = expiry?.let { if (it.level == ListExpiry.Level.OK) Color.White else expiryColor(it.level) } ?: HugoColors.TextMuted,
                                )
                            },
                            { m -> InfoCard("Versão do app", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", null, m) },
                            { m -> InfoCard("Plataforma", platformLabel(context), null, m) },
                        )
                        cards.chunked(if (twoCols) 2 else 1).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                                row.forEach { c -> c(Modifier.weight(1f)) }
                                if (row.size == 1 && twoCols) Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    SettingsCat.PLAYER -> Panel {
                        Label("Formato ao vivo")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("HLS", liveFormat == "hls") { liveFormat = "hls"; AppSettings.liveFormat = "hls"; message = "Formato: HLS" }
                            Chip("TS", liveFormat == "ts") { liveFormat = "ts"; AppSettings.liveFormat = "ts"; message = "Formato: MPEG-TS" }
                        }
                        Spacer(Modifier.height(16.dp))
                        Label("User-Agent")
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

                    SettingsCat.SUBS -> Panel {
                        Label("Fundo da legenda")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("none" to "Nenhum (contorno)", "translucent" to "Translúcido", "black" to "Preto").forEach { (k, label) ->
                                Chip(label, subBg == k) { subBg = k; AppSettings.subtitleBackground = k; message = "Fundo da legenda: $label" }
                            }
                        }
                    }

                    SettingsCat.PARENTAL -> Panel {
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
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Bloquear categorias adultas: " + if (lockOn) "LIGADO" else "desligado", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Adultos / XXX / +18 em TV, filmes e séries pedem PIN (1 vez por sessão)", color = HugoColors.TextMuted, fontSize = 12.sp)
                                }
                                Switch(checked = lockOn, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = HugoColors.Accent))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FocusSurface(
                            onClick = { enableAfterSet = false; pinStep = if (AdultLock.hasPin(context)) "verify" else "new" },
                            color = HugoColors.CardAlt, focusedColor = HugoColors.Accent.copy(alpha = 0.35f), focusScale = 1f, focusBorderWidth = 2.dp, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                        ) { _ ->
                            Text(if (AdultLock.hasPin(context)) "Alterar PIN" else "Definir PIN", color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
                        }
                    }

                    SettingsCat.CACHE -> Panel {
                        Label("Validade da lista no aparelho")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppSettings.CATALOG_TTL_OPTIONS.forEach { h -> Chip(if (h == AppSettings.DEFAULT_CATALOG_TTL_HOURS) "${h}h (padrão)" else "${h}h", catalogTtl == h) { catalogTtl = h; AppSettings.catalogTtlHours = h; message = "Cache: ${h}h" } }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lista salva no aparelho", color = Color.White, fontSize = 14.sp)
                                Text("${"%.1f".format(cacheSize / 1_048_576.0)} MB", color = HugoColors.TextMuted, fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    runCatching { CatalogRepository.get(context).clearLocalCatalog() }
                                    PlaylistRepository.clearAllSync(context)
                                    DiskCache.init(context); DiskCache.clear()
                                    cacheSize = withContext(Dispatchers.IO) { CatalogRepository.storageBytes(context) }
                                    message = "Lista do aparelho apagada. Ela será baixada de novo ao abrir a playlist."
                                }
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("Limpar", fontSize = 12.sp) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Abrir última playlist ao iniciar", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(checked = autoOpen, onCheckedChange = { autoOpen = it; AppSettings.autoOpenLastPlaylist = it }, colors = SwitchDefaults.colors(checkedTrackColor = HugoColors.Accent))
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { WatchHistory.clearContinue(context); message = "Continuar assistindo limpo" }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) { Text("Limpar continuar assistindo", fontSize = 13.sp) }
                    }

                    SettingsCat.DIAG -> Panel {
                        val log = crash
                        if (log == null) {
                            Text("Nenhum fechamento inesperado registrado.", color = HugoColors.TextMuted, fontSize = 13.sp)
                        } else {
                            Text("Último fechamento do app", color = Color.White, fontSize = 14.sp)
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

                    SettingsCat.ABOUT -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                            InfoCard("Site", "hugo.streamflixvip.online", "Ativar e gerenciar dispositivos · OK abre", Modifier.weight(1f), onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE_URL))) }.onFailure { message = "Abra $SITE_URL no celular" }
                            }, trailing = Icons.Outlined.OpenInNew)
                            if (twoCols) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = HugoColors.Accent, fontSize = 13.sp) }
                Spacer(Modifier.height(24.dp))
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
}

private fun platformLabel(context: Context): String {
    val pm = context.packageManager
    val device = when {
        Build.MANUFACTURER.equals("Amazon", true) -> "Fire TV"
        pm.hasSystemFeature("android.software.leanback") -> "Android TV"
        else -> "Celular / tablet"
    }
    return "$device · Android ${Build.VERSION.RELEASE}"
}

private fun brDate(isoDay: String?): String? =
    isoDay?.let { Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(it) }?.destructured?.let { (y, m, d) -> "$d/$m/$y" }

private fun licenseInfo(type: String?, plan: String?, expires: String?): Pair<String, Color> {
    val until = brDate(expires)?.let { " · até $it" } ?: ""
    return when (type) {
        null -> "Carregando…" to HugoColors.TextMuted
        "trial" -> "Teste$until" to Color(0xFFFBBF24)
        "premium" -> when (plan) {
            "lifetime" -> "Premium Vitalício" to Color(0xFF34D399)
            "semiannual" -> "Premium 6 meses$until" to Color(0xFF34D399)
            else -> "Premium Anual$until" to Color(0xFF34D399)
        }
        "expired" -> "Expirada" to Color(0xFFF87171)
        "trial_denied" -> "Teste já usado neste aparelho" to Color(0xFFF87171)
        "indisponível" -> "Sem conexão agora" to HugoColors.TextMuted
        else -> type to HugoColors.TextMuted
    }
}

@Composable
private fun RailItem(label: String, icon: ImageVector, compact: Boolean, active: Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (active) HugoColors.Accent.copy(alpha = 0.16f) else Color.Transparent,
        focusedColor = HugoColors.Accent.copy(alpha = 0.32f),
        focusBorderWidth = 2.dp,
        focusBorderColor = HugoColors.Accent,
        focusScale = 1.02f,
    ) { focused ->
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // barra do item ativo
            Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 10.dp).clip(RoundedCornerShape(2.dp)).background(if (active) HugoColors.Accent else Color.Transparent))
            Spacer(Modifier.width(if (compact) 10.dp else 12.dp))
            Icon(icon, contentDescription = label, tint = if (active || focused) Color.White else HugoColors.TextMuted, modifier = Modifier.size(22.dp))
            if (!compact) {
                Spacer(Modifier.width(12.dp))
                Text(label, color = if (active || focused) Color.White else HugoColors.TextMuted, fontSize = 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Cartão de informação: rótulo pequeno em cima, valor grande; OK/segurar opcionais. */
@Composable
private fun InfoCard(
    label: String, value: String, caption: String?, modifier: Modifier,
    valueColor: Color = Color.White,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: ImageVector? = null,
) {
    var longPressed by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = { onClick?.invoke() },
        onLongClick = onLongClick,
        modifier = modifier.heightIn(min = 92.dp)
            .then(
                if (onLongClick == null) Modifier else Modifier.onPreviewKeyEvent { ev ->
                    // controle: segurar OK = ação longa (copiar)
                    val code = ev.key.nativeKeyCode
                    val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                    when {
                        ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ok && ev.nativeKeyEvent.repeatCount > 0 -> { if (!longPressed) { longPressed = true; onLongClick() }; true }
                        ev.type == androidx.compose.ui.input.key.KeyEventType.KeyUp && ok && longPressed -> { longPressed = false; true }
                        else -> false
                    }
                }
            ),
        shape = RoundedCornerShape(14.dp),
        color = HugoColors.Card,
        focusedColor = HugoColors.CardAlt,
        focusBorderWidth = 2.dp,
        focusBorderColor = HugoColors.Accent,
        focusScale = 1.02f,
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), color = HugoColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                Spacer(Modifier.height(4.dp))
                Text(value, color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (caption != null && (focused || onClick == null)) {
                    Spacer(Modifier.height(3.dp))
                    Text(caption, color = HugoColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                }
            }
            if (trailing != null) Icon(trailing, null, tint = HugoColors.Accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(HugoColors.Card, RoundedCornerShape(14.dp)).padding(16.dp), content = content)
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = HugoColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) HugoColors.Accent else HugoColors.CardAlt
    val fg = if (selected) Color.White else HugoColors.TextMuted
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(label, fontSize = 12.sp) }
}
