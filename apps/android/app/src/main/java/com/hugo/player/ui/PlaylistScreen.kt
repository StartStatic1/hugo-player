package com.hugo.player.ui

import com.hugo.player.ui.components.tvTextInput
import com.hugo.player.ui.components.rememberTvTextInput
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.data.AppSettings
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.ApiException
import com.hugo.player.data.AppUpdate
import com.hugo.player.data.ApkInstall
import com.hugo.player.data.UpdateStep
import com.hugo.player.data.isUpdateRequired
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.data.DeviceIdentity
import com.hugo.player.data.PlaylistRepository
import com.hugo.player.data.RecentPlaylists
import com.hugo.player.data.RemotePlaylist
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoIconButton
import com.hugo.player.ui.components.HugoLogo
import com.hugo.player.ui.components.InitialAvatar
import com.hugo.player.ui.components.ShimmerBox
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** Estado do carregamento de uma playlist, mostrado dentro do próprio card. */
private data class CardLoad(val status: String, val fraction: Float?, val error: Boolean = false)

private sealed interface PendingAction {
    val playlist: RemotePlaylist
    data class Open(override val playlist: RemotePlaylist) : PendingAction
    data class Refresh(override val playlist: RemotePlaylist) : PendingAction
    /** Recarrega o conteúdo e já entra na playlist (atalho "Atualizar" da Home). */
    data class RefreshAndOpen(override val playlist: RemotePlaylist) : PendingAction
}

private val cardBrushes = listOf(
    Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899))),
    Brush.linearGradient(listOf(Color(0xFF06B6D4), Color(0xFF3B82F6))),
    Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEF4444))),
    Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF06B6D4))),
)

/**
 * Lista de playlists do dispositivo (estilo IBO): cards grandes com nome, resumo
 * do conteúdo, carregamento dentro do card e botões de editar/excluir sincronizados
 * com o servidor.
 */
@Composable
fun PlaylistScreen(
    onOpen: (playlistId: String, playlistName: String) -> Unit,
    onSettings: () -> Unit,
    autoOpenId: String? = null,
    autoRefresh: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity = remember { DeviceIdentity.get(context) }
    val repo = remember { CatalogRepository.get(context) }

    var playlists by remember { mutableStateOf<List<RemotePlaylist>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    /** servidor recusou por versão antiga (426): as playlists existem, só não aparecem até atualizar */
    var updateBlocked by remember { mutableStateOf(false) }
    val summaries = remember { mutableStateMapOf<String, PlaylistRepository.LocalSummary>() }
    val loads = remember { mutableStateMapOf<String, CardLoad>() }
    var openingId by remember { mutableStateOf<String?>(null) }

    var renameTarget by remember { mutableStateOf<RemotePlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<RemotePlaylist?>(null) }
    var pinTarget by remember { mutableStateOf<PendingAction?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val firstCardFocus = remember { FocusRequester() }

    suspend fun reload() {
        listError = null
        try {
            DeviceIdentity.ensureRegistered(context)
            val migrated = DeviceIdentity.migrateLegacyIfNeeded(context)
            if (migrated > 0) toast = "$migrated playlist(s) copiadas do ID antigo"
            val list = PlaylistRepository.list(identity.deviceId, identity.deviceKey)
            playlists = list
            updateBlocked = false
            if (DeviceIdentity.isTrialDenied(context)) DeviceIdentity.clearTrialDenied(context)
            list.forEach { summaries[it.id] = PlaylistRepository.localSummary(context, it.id) }
        } catch (e: Exception) {
            if (isUpdateRequired(e)) {
                updateBlocked = true
                return
            }
            listError = if (DeviceIdentity.isTrialDenied(context) && (e as? ApiException)?.status == 401)
                "Este aparelho já usou o teste grátis. Para continuar, ative um plano no site hugo.streamflixvip.online com o Device ID e a Key deste aparelho (em Configurações)."
            else e.message ?: "Não foi possível carregar as playlists."
            if (playlists == null) playlists = emptyList()
        }
    }

    var autoHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(playlists?.size) {
        if (!playlists.isNullOrEmpty()) runCatching { firstCardFocus.requestFocus() }
    }
    LaunchedEffect(toast) {
        if (toast != null) { kotlinx.coroutines.delay(3000); toast = null }
    }

    fun sync(pl: RemotePlaylist, url: String, navigateAfter: Boolean) {
        if (loads[pl.id]?.let { !it.error } == true) return
        if (navigateAfter) openingId = pl.id
        loads[pl.id] = CardLoad("Conectando...", null)
        scope.launch {
            try {
                repo.syncPlaylist(
                    pl.id, url,
                    onProgress = { msg -> loads[pl.id] = (loads[pl.id] ?: CardLoad(msg, null)).copy(status = msg) },
                    onFraction = { f -> loads[pl.id] = (loads[pl.id] ?: CardLoad("", f)).copy(fraction = f) },
                )
                PlaylistRepository.markSynced(context, pl.id)
                summaries[pl.id] = PlaylistRepository.localSummary(context, pl.id)
                loads.remove(pl.id)
                if (navigateAfter && openingId == pl.id) {
                    openingId = null
                    enterPlaylist(context, pl, url, onOpen)
                } else if (!navigateAfter) {
                    toast = "\"${pl.name}\" atualizada"
                }
            } catch (e: Exception) {
                loads[pl.id] = CardLoad("Erro: ${e.message ?: "falha ao carregar"}", null, error = true)
                if (openingId == pl.id) openingId = null
            }
        }
    }

    fun proceed(action: PendingAction, url: String) {
        val pl = action.playlist
        when (action) {
            is PendingAction.Refresh -> sync(pl, url, navigateAfter = false)
            is PendingAction.RefreshAndOpen -> sync(pl, url, navigateAfter = true)
            is PendingAction.Open -> scope.launch {
                // o resumo local pode ainda não ter sido lido (ex.: abrir pela Home logo ao entrar):
                // lê do banco do aparelho antes de decidir, para não baixar de novo uma lista que já está guardada
                val s = summaries[pl.id] ?: runCatching { PlaylistRepository.localSummary(context, pl.id) }.getOrNull()?.also { summaries[pl.id] = it }
                val fresh = s != null && AppSettings.isCatalogFresh(s.total, s.syncedAt, System.currentTimeMillis(), AppSettings.catalogTtlHours)
                if (fresh) enterPlaylist(context, pl, url, onOpen) else sync(pl, url, navigateAfter = true)
            }
        }
    }

    fun request(action: PendingAction) {
        val pl = action.playlist
        val url = pl.url
        when {
            url != null -> proceed(action, url)
            pl.isProtected && PlaylistRepository.pinFor(pl.id) != null -> scope.launch {
                try {
                    val unlocked = PlaylistRepository.unlock(identity.deviceId, identity.deviceKey, pl.id, PlaylistRepository.pinFor(pl.id)!!)
                    unlocked.url?.let { proceed(action, it) }
                } catch (e: Exception) {
                    PlaylistRepository.forgetPin(pl.id); pinTarget = action
                }
            }
            pl.isProtected -> pinTarget = action
            else -> loads[pl.id] = CardLoad("Playlist sem URL", null, error = true)
        }
    }

    LaunchedEffect(playlists) {
        val target = autoOpenId?.let { id -> playlists?.firstOrNull { it.id == id } }
        if (!autoHandled && target != null) {
            autoHandled = true
            request(if (autoRefresh) PendingAction.RefreshAndOpen(target) else PendingAction.Open(target))
        }
    }

    Column(Modifier.fillMaxSize().background(HugoColors.Background).padding(horizontal = 28.dp, vertical = 20.dp)) {
        // Topo
        Row(verticalAlignment = Alignment.CenterVertically) {
            HugoLogo()
            Spacer(Modifier.width(18.dp))
            Text("Playlists", color = HugoColors.TextMuted, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 14.dp)) {
                Text("ID ${identity.deviceId}", color = HugoColors.TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("KEY ${identity.deviceKey}", color = HugoColors.Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            HugoIconButton(Icons.Default.Add, "Adicionar playlist", onClick = { showAdd = true }, label = "Adicionar")
            Spacer(Modifier.width(8.dp))
            HugoIconButton(Icons.Default.Refresh, "Atualizar lista", onClick = { scope.launch { reload() } })
            Spacer(Modifier.width(8.dp))
            HugoIconButton(Icons.Default.Settings, "Configurações", onClick = onSettings)
        }
        Spacer(Modifier.height(18.dp))

        if (updateBlocked) {
            UpdateRequiredPanel(onRetry = { scope.launch { reload() } })
            return@Column
        }

        listError?.let {
            Row(
                Modifier.fillMaxWidth().background(Color(0x22EF4444), RoundedCornerShape(10.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFF87171))
                Spacer(Modifier.width(8.dp))
                Text(it, color = Color(0xFFF87171), fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
        }

        val list = playlists
        LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 24.dp, top = 4.dp, start = 4.dp, end = 4.dp),
            modifier = Modifier.weight(1f)
        ) {
            when {
                list == null -> items(3) { ShimmerBox(Modifier.fillMaxWidth().height(176.dp), RoundedCornerShape(18.dp)) }
                list.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyPlaylists(identity.deviceId, identity.deviceKey) { showAdd = true }
                }
                else -> items(list, key = { it.id }) { pl ->
                    val index = list.indexOf(pl)
                    PlaylistCard(
                        playlist = pl,
                        brush = cardBrushes[index % cardBrushes.size],
                        summary = summaries[pl.id],
                        load = loads[pl.id],
                        openModifier = if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier,
                        onOpen = { request(PendingAction.Open(pl)) },
                        onRefresh = { request(PendingAction.Refresh(pl)) },
                        onRename = { renameTarget = pl },
                        onDelete = { deleteTarget = pl },
                    )
                }
            }
        }
        toast?.let {
            Text(
                it, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).background(HugoColors.CardAlt, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }

    // ---------- Diálogos ----------
    pinTarget?.let { action ->
        PinDialog(
            title = "Playlist protegida",
            message = "Digite o PIN de \"${action.playlist.name}\".",
            onDismiss = { pinTarget = null },
            onConfirm = { pin, setError ->
                scope.launch {
                    try {
                        val unlocked = PlaylistRepository.unlock(identity.deviceId, identity.deviceKey, action.playlist.id, pin)
                        pinTarget = null
                        unlocked.url?.let { proceed(action, it) }
                    } catch (e: Exception) { setError(e.message ?: "PIN incorreto.") }
                }
            }
        )
    }

    renameTarget?.let { pl ->
        val needsPin = pl.isProtected && PlaylistRepository.pinFor(pl.id) == null
        var name by remember(pl.id) { mutableStateOf(pl.name) }
        var epg by remember(pl.id) { mutableStateOf(pl.epgUrl.orEmpty()) }
        var pin by remember(pl.id) { mutableStateOf("") }
        var err by remember(pl.id) { mutableStateOf<String?>(null) }
        var busy by remember(pl.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) renameTarget = null },
            containerColor = HugoColors.Card,
            title = { Text("Editar playlist", color = Color.White) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HugoTextField(name, { name = it }, "Nome")
                    Spacer(Modifier.height(10.dp))
                    EpgLinkField(epg, { epg = it.trim() }, keepHint = needsPin)
                    if (needsPin) { Spacer(Modifier.height(10.dp)); HugoTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, "PIN", isPin = true) }
                    err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFF87171), fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        val n = name.trim()
                        if (n.isEmpty()) { err = "Digite um nome."; return@Button }
                        if (!PlaylistRepository.isValidEpgUrl(epg)) { err = EPG_URL_ERROR; return@Button }
                        busy = true
                        scope.launch {
                            try {
                                // protegida e bloqueada: o link atual não aparece; vazio = manter
                                val keepEpg = needsPin && epg.isBlank()
                                if (keepEpg) PlaylistRepository.rename(identity.deviceId, identity.deviceKey, pl.id, n, pin.takeIf { needsPin })
                                else PlaylistRepository.update(identity.deviceId, identity.deviceKey, pl.id, n, epg, pin.takeIf { needsPin })
                                playlists = playlists?.map {
                                    if (it.id != pl.id) it else if (keepEpg) it.copy(name = n) else it.copy(name = n, epgUrl = epg.ifBlank { null })
                                }
                                RecentPlaylists.rename(context, pl.id, n)
                                if (LiveTvCache.playlistId == pl.id) { LiveTvCache.playlistName = n; LiveTvCache.save(context) }
                                renameTarget = null
                                toast = "Playlist atualizada"
                            } catch (e: Exception) { err = e.message; busy = false }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent)
                ) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }, enabled = !busy) { Text("Cancelar", color = HugoColors.TextMuted) } }
        )
    }

    deleteTarget?.let { pl ->
        val needsPin = pl.isProtected && PlaylistRepository.pinFor(pl.id) == null
        var pin by remember(pl.id) { mutableStateOf("") }
        var err by remember(pl.id) { mutableStateOf<String?>(null) }
        var busy by remember(pl.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            containerColor = HugoColors.Card,
            title = { Text("Excluir playlist?", color = Color.White) },
            text = {
                Column {
                    Text("\"${pl.name}\" será removida deste dispositivo e do site. Essa ação não pode ser desfeita.", color = HugoColors.TextMuted)
                    if (needsPin) { Spacer(Modifier.height(10.dp)); HugoTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, "PIN", isPin = true) }
                    err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFF87171), fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                PlaylistRepository.delete(identity.deviceId, identity.deviceKey, pl.id, pin.takeIf { needsPin })
                                repo.deletePlaylist(pl.id)
                                PlaylistRepository.clearSync(context, pl.id)
                                RecentPlaylists.remove(context, pl.id)
                                if (AppSettings.lastPlaylistId == pl.id) AppSettings.lastPlaylistId = null
                                if (LiveTvCache.playlistId == pl.id) { LiveTvCache.clear(); LiveTvCache.save(context) }
                                playlists = playlists?.filterNot { it.id == pl.id }
                                summaries.remove(pl.id); loads.remove(pl.id)
                                deleteTarget = null
                                toast = "Playlist excluída"
                            } catch (e: Exception) { err = e.message; busy = false }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !busy) { Text("Cancelar", color = HugoColors.TextMuted) } }
        )
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var isXtream by remember { mutableStateOf(false) }
        var url by remember { mutableStateOf("") }
        var host by remember { mutableStateOf("") }
        var user by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var epg by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) showAdd = false },
            containerColor = HugoColors.Card,
            title = { Text("Adicionar playlist", color = Color.White) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tipo:", color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.width(12.dp))
                        FilterChip(selected = !isXtream, onClick = { isXtream = false }, label = { Text("M3U") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = isXtream, onClick = { isXtream = true }, label = { Text("Xtream") })
                    }
                    Spacer(Modifier.height(10.dp))
                    HugoTextField(name, { name = it }, "Nome")
                    Spacer(Modifier.height(10.dp))
                    if (isXtream) {
                        HugoTextField(host, { host = it.trim() }, "Host", keyboardType = KeyboardType.Uri)
                        Spacer(Modifier.height(4.dp))
                        Text("Ex: http://servidor.com:8080", color = HugoColors.TextMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        HugoTextField(user, { user = it.trim() }, "Usuário")
                        Spacer(Modifier.height(8.dp))
                        HugoTextField(pass, { pass = it }, "Senha")
                    } else {
                        HugoTextField(url, { url = it.trim() }, "URL M3U", keyboardType = KeyboardType.Uri)
                    }
                    Spacer(Modifier.height(10.dp))
                    EpgLinkField(epg, { epg = it.trim() })
                    err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFF87171), fontSize = 13.sp) }
                    Spacer(Modifier.height(8.dp))
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        val finalUrl = if (isXtream) {
                            val h = host.trimEnd('/')
                            if (h.isBlank() || user.isBlank() || pass.isBlank()) { err = "Preencha host, usuário e senha."; return@Button }
                            "$h/get.php?username=$user&password=$pass&type=m3u_plus&output=ts"
                        } else url
                        if (name.isBlank() || finalUrl.length < 8) { err = "Preencha os campos."; return@Button }
                        if (!PlaylistRepository.isValidEpgUrl(epg)) { err = EPG_URL_ERROR; return@Button }
                        busy = true
                        scope.launch {
                            try {
                                PlaylistRepository.add(identity.deviceId, identity.deviceKey, name.trim(), finalUrl, epg)
                                showAdd = false
                                toast = "Playlist adicionada"
                                reload()
                            } catch (e: Exception) { err = e.message; busy = false }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent)
                ) { Text("Adicionar") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }, enabled = !busy) { Text("Cancelar", color = HugoColors.TextMuted) } }
        )
    }
}

private const val EPG_URL_ERROR = "O link do EPG deve começar com http:// ou https://"

/** Link opcional do guia (XMLTV .xml ou .xml.gz); vazio = automático. */
@Composable
private fun EpgLinkField(value: String, onChange: (String) -> Unit, keepHint: Boolean = false) {
    HugoTextField(value, onChange, "Link do EPG (XMLTV) — opcional", keyboardType = KeyboardType.Uri)
    Spacer(Modifier.height(4.dp))
    Text(
        if (keepHint) "Deixe vazio para manter o link atual. Aceita .xml e .xml.gz."
        else "Ex: http://servidor.com/epg.xml.gz — vazio usa o EPG do próprio servidor (Xtream).",
        color = HugoColors.TextMuted, fontSize = 11.sp,
    )
}

/** Marca a playlist como atual e abre a Home. */
private fun enterPlaylist(context: android.content.Context, pl: RemotePlaylist, url: String, onOpen: (String, String) -> Unit) {
    LiveTvCache.playlistId = pl.id
    LiveTvCache.playlistName = pl.name
    LiveTvCache.playlistUrl = url
    LiveTvCache.save(context)
    AppSettings.lastPlaylistId = pl.id
    AppSettings.lastPlaylistName = pl.name
    AppSettings.lastPlaylistUrl = url
    RecentPlaylists.touch(context, pl.id, pl.name)
    onOpen(pl.id, pl.name)
}

@Composable
private fun PlaylistCard(
    playlist: RemotePlaylist,
    brush: Brush,
    summary: PlaylistRepository.LocalSummary?,
    load: CardLoad?,
    openModifier: Modifier,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val busy = load != null && !load.error
    Column(
        Modifier
            .fillMaxWidth()
            .height(176.dp)
            .onFocusChanged { hasFocus = it.hasFocus }
            .background(if (hasFocus) HugoColors.CardAlt else HugoColors.Card, shape)
            .border(2.dp, if (hasFocus) HugoColors.Accent else Color(0xFF1F2638), shape)
            .pointerInput(playlist.id, busy) { detectTapGestures { if (!busy) onOpen() } }
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(playlist.name, 56.dp, brush = brush, icon = if (playlist.isProtected) Icons.Default.Lock else null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(summaryLine(summary, playlist), color = HugoColors.TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        if (load != null) {
            // Carregamento dentro do card
            if (!load.error) {
                if (load.fraction != null) {
                    LinearProgressIndicator(
                        progress = { load.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = HugoColors.Accent, trackColor = Color(0xFF0F1220),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = HugoColors.Accent, trackColor = Color(0xFF0F1220))
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!load.error) Icon(Icons.Default.Sync, null, tint = HugoColors.Accent, modifier = Modifier.size(14.dp))
                else Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFF87171), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    load.status, color = if (load.error) Color(0xFFF87171) else HugoColors.TextMuted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (load.error) HugoIconButton(Icons.Default.Refresh, "Tentar de novo", onClick = onOpen, label = "Tentar")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HugoIconButton(
                    Icons.Default.PlayArrow, "Abrir", onClick = onOpen, label = "Abrir",
                    background = HugoColors.Accent.copy(alpha = 0.35f), modifier = openModifier
                )
                Spacer(Modifier.weight(1f))
                HugoIconButton(Icons.Default.Sync, "Recarregar conteúdo", onClick = onRefresh)
                HugoIconButton(Icons.Default.Edit, "Editar nome e EPG", onClick = onRename)
                HugoIconButton(Icons.Default.Delete, "Excluir", onClick = onDelete, tint = Color(0xFFF87171))
            }
        }
    }
}

private fun summaryLine(s: PlaylistRepository.LocalSummary?, pl: RemotePlaylist): String {
    val lock = if (pl.isProtected) "Protegida por PIN · " else ""
    if (s == null || s.total == 0) return lock + "Ainda não carregada neste aparelho"
    val nf = NumberFormat.getIntegerInstance(Locale("pt", "BR"))
    val parts = buildList {
        if (s.live > 0) add("${nf.format(s.live)} canais")
        if (s.movies > 0) add("${nf.format(s.movies)} filmes")
        if (s.series > 0) add("${nf.format(s.series)} séries")
    }
    val ago = s.syncedAt?.let { " · atualizada " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) } ?: ""
    return lock + parts.joinToString(" · ") + ago
}

@Composable
private fun EmptyPlaylists(deviceId: String, deviceKey: String, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(HugoColors.Card, RoundedCornerShape(18.dp)).padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Nenhuma playlist ainda", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Entre no site do Hugo Player com ID $deviceId e Key $deviceKey para adicionar, ou adicione aqui.",
            color = HugoColors.TextMuted, fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        HugoIconButton(Icons.Default.Add, "Adicionar playlist", onClick = onAdd, label = "Adicionar playlist", background = HugoColors.Accent)
    }
}

@Composable
fun HugoTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isPin: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    val tv = rememberTvTextInput()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        readOnly = tv.readOnly,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPin) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPin) KeyboardType.NumberPassword else keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = HugoColors.Accent, unfocusedBorderColor = Color(0xFF334155),
            focusedLabelColor = HugoColors.Accent, unfocusedLabelColor = HugoColors.TextMuted, cursorColor = HugoColors.Accent,
        ),
        modifier = modifier.fillMaxWidth().tvTextInput(tv)
    )
}

@Composable
fun PinDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: (pin: String, setError: (String) -> Unit) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HugoColors.Card,
        title = { Text(title, color = Color.White) },
        text = {
            Column {
                Text(message, color = HugoColors.TextMuted)
                Spacer(Modifier.height(10.dp))
                HugoTextField(pin, { pin = it.filter(Char::isDigit).take(8); err = null }, "PIN", isPin = true, modifier = Modifier.focusRequester(focus))
                err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFF87171), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (pin.length < 4) err = "O PIN tem de 4 a 8 dígitos." else onConfirm(pin) { err = it } },
                colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent)
            ) { Text("Desbloquear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = HugoColors.TextMuted) } }
    )
}

/**
 * App abaixo da versão mínima (o servidor respondeu 426): NÃO é "sem playlists".
 * As playlists continuam salvas; só aparecem depois de atualizar. Mesmo instalador da tela de atualização.
 */
@Composable
private fun UpdateRequiredPanel(onRetry: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<UpdateStep>(UpdateStep.Idle) }
    val installError by ApkInstall.lastError.collectAsState()
    LaunchedEffect(installError) { installError?.let { step = UpdateStep.Failed(it) } }
    val updateFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { updateFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(top = 12.dp)
                .background(HugoColors.Card, RoundedCornerShape(18.dp))
                .border(1.dp, Color(0xFF1F2638), RoundedCornerShape(18.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.SystemUpdate, null, tint = HugoColors.Accent, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("Atualize para ver suas playlists", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "Suas playlists estão salvas e nada foi apagado. Esta versão do app ficou antiga: " +
                    "é só atualizar o Hugo Player para voltar a vê-las.",
                color = HugoColors.TextMuted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            FocusSurface(
                onClick = {
                    if (step.busy) return@FocusSurface
                    scope.launch { runAppUpdate(context, null, resolveUrl = true) { step = it } }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(updateFocus),
                color = HugoColors.Accent,
                focusedColor = HugoColors.Accent,
                focusBorderWidth = 3.dp,
                focusBorderColor = Color.White,
                focusScale = 1.03f,
                shape = RoundedCornerShape(14.dp),
            ) { _ ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(updateButtonText(step, "Atualizar agora"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (step != UpdateStep.Idle) {
                Spacer(Modifier.height(12.dp))
                UpdateProgress(step)
            }
            Spacer(Modifier.height(12.dp))
            FocusSurface(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) { _ ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Já atualizei — carregar playlists", color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}
