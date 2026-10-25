package com.hugo.player.ui

import com.hugo.player.data.AppSettings
import com.hugo.player.ui.components.lazyFocusRestore
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.LiveRecents
import com.hugo.player.data.WatchHistory
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.data.epg.EpgNowNext
import com.hugo.player.data.epg.LiveEpg
import com.hugo.player.player.HugoPlayerFactory
import com.hugo.player.ui.components.ChannelLogo
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WINDOW = 5000
private const val MAX_LIVE_RETRIES = 6
private const val BANNER_MS = 3_000L
private const val STALL_BUFFERING_MS = 15_000L
private const val STALL_FROZEN_MS = 12_000L



/**
 * Player só da TV ao vivo: CH+/- zapeia sempre; ▲▼ mostram a barra (se escondida) e depois zapeiam; número pelo controle, lista lateral (OK / ←),
 * painel de fontes e tela (→), reconexão automática e parada ao sair do app.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LivePlayerScreen(request: LivePlayRequest, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repo = remember { CatalogRepository.get(context) }
    val src = request.source

    var channels by remember { mutableStateOf(request.fixedList ?: listOf(request.channel)) }
    var offset by remember { mutableIntStateOf(if (request.fixedList != null) 0 else request.number - 1) }
    var index by remember { mutableIntStateOf(if (request.fixedList != null) (request.number - 1).coerceIn(0, (channels.size - 1).coerceAtLeast(0)) else 0) }
    val current = channels.getOrNull(index) ?: request.channel
    var previous by remember { mutableStateOf<Pair<Int, ChannelEntity>?>(null) }

    var format by remember { mutableStateOf(LiveFormat.AUTO) }
    // tentativas do canal atual (.m3u8 <-> .ts etc.); attempt = qual está tocando
    var attempts by remember { mutableStateOf(liveAttempts(current.url, format)) }
    var attempt by remember { mutableIntStateOf(0) }
    val playUrl = attempts.getOrNull(attempt)?.url ?: current.url
    val forceHls = attempts.getOrNull(attempt)?.forceHls ?: false
    var screenMode by remember { mutableStateOf(LiveScreenMode.from(AppSettings.liveScreenMode)) }
    var videoAspect by remember { mutableFloatStateOf(0f) }
    // formato que está TOCANDO de verdade (null até a primeira imagem)
    var playing by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var bufferingSince by remember { mutableLongStateOf(0L) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var reconnectTick by remember { mutableIntStateOf(0) }
    var foreground by remember { mutableStateOf(true) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var bitrate by remember { mutableIntStateOf(0) }

    var sleep by remember { mutableStateOf(SleepOption.OFF) }
    var sleepEndAt by remember { mutableStateOf<Long?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showBanner by remember { mutableStateOf(true) }
    var bannerTick by remember { mutableIntStateOf(0) }
    var showList by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf<EpgNowNext?>(null) }
    val listEpg = remember { mutableStateMapOf<String, EpgNowNext>() }

    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val sourcesFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val exoPlayer = remember { HugoPlayerFactory.create(context, playerUserAgent(context), decoderFallback = true) }
    // timer de desligar: pausa e sai
    LaunchedEffect(sleepEndAt) {
        val end = sleepEndAt ?: return@LaunchedEffect
        while (System.currentTimeMillis() < end) { nowMs = System.currentTimeMillis(); delay(1_000) }
        exoPlayer.pause()
        onBack()
    }

    // lista para zapear (mesma ordem/numeração da tela)
    suspend fun loadWindowAround(number: Int): Boolean {
        if (request.fixedList != null) return false
        val group = if (src.query.isNotBlank()) null else LiveFilters.group(src.filter)
        val start = (number - 1 - WINDOW / 2).coerceAtLeast(0)
        val list = repo.liveWindow(src.playlistId, group, src.query, start, WINDOW)
        val i = number - 1 - start
        if (i !in list.indices) return false
        channels = list; offset = start; index = i
        return true
    }
    LaunchedEffect(Unit) {
        if (request.fixedList == null) {
            val ok = loadWindowAround(request.number)
            if (ok && channels[index].url != request.channel.url) {
                // a lista mudou desde a tela: procura o canal pela URL
                val j = channels.indexOfFirst { it.url == request.channel.url }
                if (j >= 0) index = j else { channels = listOf(request.channel); offset = request.number - 1; index = 0 }
            }
        }
    }

    fun bannerNow() { showBanner = true; bannerTick++ }

    fun zapTo(i: Int) {
        if (i !in channels.indices || i == index) return
        previous = index to current
        index = i
        bannerNow()
    }

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        val target = index + delta
        when {
            target in channels.indices -> zapTo(target)
            request.fixedList == null -> scope.launch {
                val number = offset + target + 1
                if (number >= 1 && loadWindowAround(number)) bannerNow()
                else if (channels.size > 1) zapTo(if (target < 0) channels.size - 1 else 0)
            }
            channels.size > 1 -> zapTo(if (target < 0) channels.size - 1 else 0)
        }
    }

    fun jumpToNumber(n: Int) {
        val i = n - offset - 1
        if (i in channels.indices) { zapTo(i); return }
        scope.launch { if (loadWindowAround(n)) bannerNow() }
    }

    // troca de canal / formato -> URL a tocar
    LaunchedEffect(current.url, format) {
        attempts = liveAttempts(current.url, format)
        attempt = 0
        retryCount = 0
        videoHeight = 0
        epg = LiveEpg.cached(src.playlistId, current)
        LiveRecents.add(context, src.playlistId, current.url)
        LiveSession.lastPlayedUrl = current.url
        launch { epg = LiveEpg.nowNext(context, src.playlistId, listOf(current))[current.url] ?: epg }
    }

    LaunchedEffect(playUrl, forceHls, foreground) {
        errorMsg = null
        playing = null
        if (!foreground) { exoPlayer.stop(); return@LaunchedEffect }
        loading = true
        bufferingSince = System.currentTimeMillis()
        exoPlayer.setMediaItem(HugoPlayerFactory.mediaItem(playUrl, forceHls))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun reconnect() {
        errorMsg = null
        loading = true
        bufferingSince = System.currentTimeMillis()
        exoPlayer.setMediaItem(HugoPlayerFactory.mediaItem(playUrl, forceHls))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exoPlayer.seekToDefaultPosition(); exoPlayer.prepare(); return
                }
                // qualquer erro: tenta a outra variante (.ts <-> .m3u8) antes de desistir
                if (attempt + 1 < attempts.size) { attempt++; return }
                if ((isRetryable(error) || isLiveFormatFailure(error.errorCode, httpStatus(error))) && retryCount < MAX_LIVE_RETRIES) {
                    retryCount++
                    // recomeça pela primeira variante (TS); com uma só variante, reconecta com espera crescente
                    if (attempts.size > 1) attempt = 0 else reconnectTick++
                    return
                }
                errorMsg = friendlyError(error)
                loading = false
            }
            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING || (state == Player.STATE_IDLE && errorMsg == null && exoPlayer.playWhenReady)
                if (state == Player.STATE_BUFFERING) bufferingSince = System.currentTimeMillis()
                if (state == Player.STATE_READY) {
                    errorMsg = null; retryCount = 0
                    attempts.getOrNull(attempt)?.let { a -> LiveFormatMemory.remember(a.url); playing = playingFormat(a.url, a.forceHls) }
                }
                // ao vivo não "acaba": se acabar, reconecta
                if (state == Player.STATE_ENDED) reconnectTick++
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoHeight = videoSize.height
                videoAspect = if (videoSize.width > 0 && videoSize.height > 0) videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height else 0f
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener); exoPlayer.release() }
    }

    LaunchedEffect(reconnectTick) {
        if (reconnectTick == 0) return@LaunchedEffect
        delay((1_500L * retryCount.coerceAtLeast(1)).coerceAtMost(8_000L))
        if (foreground) reconnect()
    }

    // vigia: buffering eterno ou imagem congelada -> reconecta
    LaunchedEffect(exoPlayer) {
        var lastPos = -1L
        var frozenSince = 0L
        while (true) {
            delay(2_000)
            bitrate = (exoPlayer.videoFormat?.bitrate ?: 0).coerceAtLeast(0)
            if (!foreground || errorMsg != null || !exoPlayer.playWhenReady) { frozenSince = 0L; continue }
            val t = System.currentTimeMillis()
            when (exoPlayer.playbackState) {
                Player.STATE_BUFFERING -> if (t - bufferingSince > STALL_BUFFERING_MS) { retryCount = 0; reconnect() }
                Player.STATE_READY -> {
                    val pos = exoPlayer.currentPosition
                    if (pos == lastPos) {
                        if (frozenSince == 0L) frozenSince = t
                        else if (t - frozenSince > STALL_FROZEN_MS) { frozenSince = 0L; reconnect() }
                    } else frozenSince = 0L
                    lastPos = pos
                }
                else -> frozenSince = 0L
            }
        }
    }

    // HOME / app em segundo plano: para de verdade (sem áudio fantasma, sem gastar banda)
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { foreground = false; exoPlayer.stop() }
                Lifecycle.Event.ON_START -> foreground = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(bannerTick, showBanner) {
        if (showBanner) { delay(BANNER_MS); showBanner = false }
    }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_800)
        digits.toIntOrNull()?.let { jumpToNumber(it) }
        digits = ""
    }
    LaunchedEffect(showList, showSources, errorMsg) {
        delay(60)
        runCatching {
            when {
                showList -> { listState.scrollToItem((index - 4).coerceAtLeast(0)); delay(30); listFocus.requestFocus() }
                showSources -> sourcesFocus.requestFocus()
                errorMsg == null -> rootFocus.requestFocus()
            }
        }
    }

    BackHandler {
        when {
            showList -> showList = false
            showSources -> showSources = false
            digits.isNotEmpty() -> digits = ""
            else -> onBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = ev.key.nativeKeyCode
                // números funcionam até com a lista aberta
                if (code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9) {
                    if (digits.length < 5) digits += (code - AndroidKeyEvent.KEYCODE_0).toString()
                    return@onPreviewKeyEvent true
                }
                when (code) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> { zap(+1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> { zap(-1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> { previous?.let { (i, c) -> if (channels.getOrNull(i)?.url == c.url) zapTo(i) }; return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (exoPlayer.isPlaying) exoPlayer.pause() else reconnect(); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_INFO, AndroidKeyEvent.KEYCODE_GUIDE -> { bannerNow(); return@onPreviewKeyEvent true }
                }
                if (showList || showSources || errorMsg != null) return@onPreviewKeyEvent false
                when (code) {
                    // ▲▼: com a barra escondida, o 1º toque só mostra a barra; com ela na tela, troca o canal
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (liveArrowZaps(showBanner)) zap(-1) else bannerNow(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (liveArrowZaps(showBanner)) zap(+1) else bannerNow(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { showList = true; true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MENU -> { showSources = true; true }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                // toque no vídeo: fecha painel aberto, senão mostra/esconde a barra do canal (some em 3s).
                // A lista de canais abre pelo botão "Canais" da barra (ou OK/← no controle).
                if (showList || showSources) { showList = false; showSources = false }
                else if (showBanner) showBanner = false else bannerNow()
            }
    ) {
        // tamanho do vídeo calculado aqui (o PlayerView só estica no espaço dado): em TV 16:9 com vídeo 16:9
        // Ajustar/Esticar/Zoom do PlayerView ficavam iguais; agora cada modo tem efeito visível
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val rect = liveVideoRect(screenMode, maxWidth.value, maxHeight.value, videoAspect)
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    isFocusable = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = exoPlayer; it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL },
            modifier = Modifier.requiredSize(rect.first.dp, rect.second.dp),
        )
        }

        if (loading && errorMsg == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = HugoColors.Accent)
                if (retryCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("Reconectando… ($retryCount)", color = Color.White, fontSize = 13.sp, modifier = Modifier.background(Color(0xFF12141C), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }

        errorMsg?.let { msg ->
            LiveError(msg, onRetry = { retryCount = 0; attempts = liveAttempts(current.url, format); if (attempt == 0) reconnect() else attempt = 0 }, onNext = { zap(+1) }, modifier = Modifier.align(Alignment.Center))
        }

        if (showBanner && !showList && !showSources) {
            InfoBanner(
                offset + index + 1, current, epg, videoHeight, playing, src.title, Modifier.align(Alignment.BottomCenter),
                onChannels = { showList = true }, onSources = { showSources = true },
            )
        }

        // selos no topo junto com a barra do canal: ao vivo, taxa, fluxo e resolução
        if (showBanner && !showList && !showSources && errorMsg == null) {
            Row(Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TopBadge("● AO VIVO", Color(0xFFF472B6))
                if (bitrate > 0) TopBadge("%.1f Mbps".format(bitrate / 1_000_000f), Color(0xFF4ADE80))
                playing?.let { TopBadge("FLUXO $it", Color.White) }
                if (videoHeight > 0) TopBadge("${videoHeight}p", Color.White)
                sleepEndAt?.let { TopBadge("Desliga em ${sleepRemainingLabel(it - nowMs)}", Color(0xFFFACC15)) }
            }
        }

        if (digits.isNotEmpty()) {
            Text(
                digits, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(28.dp).background(Color(0xFF12141C), RoundedCornerShape(12.dp))
                    .border(2.dp, HugoColors.Accent, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }

        if (showList) {
            Column(
                Modifier.align(Alignment.CenterStart).fillMaxHeight().width(380.dp).background(HugoColors.Panel)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) { showList = false; true } else false
                    }
                    .padding(vertical = 10.dp)
            ) {
                Text(src.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().lazyFocusRestore(listState, listFocus) { index }, contentPadding = PaddingValues(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
                        val isCurrent = i == index
                        val fav = remember(c.url) { WatchHistory.isFavorite(context, WatchHistory.contentKey(src.playlistId, c.url)) }
                        FocusSurface(
                            onClick = { zapTo(i); showList = false },
                            color = if (isCurrent) Color(0xFF2A2350) else HugoColors.Card,
                            focusedColor = Color(0xFF3B2F70),
                            focusScale = 1.0f,
                            modifier = (if (isCurrent) Modifier.focusRequester(listFocus) else Modifier).fillMaxWidth().height(52.dp),
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${offset + i + 1}", color = HugoColors.TextMuted, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(42.dp))
                                Spacer(Modifier.width(8.dp))
                                ChannelLogo(c.logo, c.name, size = 36.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    (listEpg[c.url] ?: LiveEpg.cached(src.playlistId, c))?.now?.let { Text(it.title, color = HugoColors.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                                if (fav) Icon(Icons.Default.Star, null, tint = Color(0xFFFACC15), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            // EPG das linhas visíveis da lista lateral
            LaunchedEffect(listState, channels) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }.collect { idx ->
                    delay(400)
                    LiveEpg.nowNext(context, src.playlistId, idx.mapNotNull { channels.getOrNull(it) }).forEach { (k, v) -> listEpg[k] = v }
                }
            }
        }

        if (showSources) {
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp).background(HugoColors.Panel)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) { showSources = false; true } else false
                    }
                    // rola (toque ou setas: o item focado é trazido para a vista); nada fica cortado embaixo
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Fonte", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                LiveFormat.entries.forEachIndexed { i, f ->
                    PanelOption(f.label, selected = format == f, modifier = if (i == 0) Modifier.focusRequester(sourcesFocus) else Modifier) { format = f }
                }
                Text(
                    "Tocando: " + (playing ?: "conectando…") +
                        (if (videoHeight > 0) " · ${videoHeight}p" else ""),
                    color = HugoColors.TextMuted, fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("Tela", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                LiveScreenMode.entries.forEach { m -> PanelOption(m.label, selected = screenMode == m) { screenMode = m; AppSettings.liveScreenMode = m.key } }
                Spacer(Modifier.height(8.dp))
                Text("Desligar em", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                SleepOption.entries.filter { it != SleepOption.END_OF_EPISODE }.forEach { o ->
                    PanelOption(
                        if (o == sleep && sleepEndAt != null) "${o.label} (faltam ${sleepRemainingLabel(sleepEndAt!! - nowMs)})" else o.label,
                        selected = sleep == o,
                    ) {
                        sleep = o
                        sleepEndAt = if (o.minutes > 0) System.currentTimeMillis() + o.minutes * 60_000L else null
                        nowMs = System.currentTimeMillis()
                    }
                }
                Spacer(Modifier.height(8.dp))
                PanelOption("Recarregar canal", selected = false) { retryCount = 0; if (attempt == 0) reconnect() else attempt = 0; showSources = false }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PanelOption(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick, selected = selected, selectedColor = HugoColors.Accent, color = HugoColors.CardAlt,
        focusedColor = Color(0xFF4C3A86), focusScale = 1.0f, shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().height(42.dp),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
    }
}

@Composable
private fun InfoBanner(
    number: Int, c: ChannelEntity, epg: EpgNowNext?, videoHeight: Int, playing: String?, listTitle: String, modifier: Modifier,
    onChannels: () -> Unit, onSources: () -> Unit,
) {
    Row(
        modifier.padding(24.dp).fillMaxWidth().background(Color(0xFF12141C), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF2A3148), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$number", color = HugoColors.Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 56.dp))
        Spacer(Modifier.width(10.dp))
        ChannelLogo(c.logo, c.name, size = 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                qualityChips(c, videoHeight, playing).forEach {
                    Text(it, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp).border(1.dp, HugoColors.Accent, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            val now = epg?.now
            if (now != null) {
                Text("AGORA  ${formatHm(now.start)}–${formatHm(now.stop)}  ${now.title}", color = Color(0xFFE2E8F0), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(progress = { now.progress() }, color = HugoColors.Accent, trackColor = Color(0xFF2A3148), modifier = Modifier.fillMaxWidth(0.7f).height(3.dp))
            }
            epg?.next?.let { Text("A SEGUIR  ${formatHm(it.start)}  ${it.title}", color = HugoColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (epg == null) Text(listTitle, color = HugoColors.TextMuted, fontSize = 12.sp)
            Text("● Ao vivo", color = Color(0xFF4ADE80), fontSize = 11.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            // botões para toque (no controle o foco fica no player: setas/OK seguem como antes)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BannerButton("Canais", onChannels)
                BannerButton("Fonte/tela", onSources)
            }
            Spacer(Modifier.height(4.dp))
            Text("▲▼ trocar canal · CH± · OK lista · → tela", color = HugoColors.TextDim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun TopBadge(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Color(0xFF12141C), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF2A3148), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun BannerButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(HugoColors.CardAlt)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun LiveError(msg: String, onRetry: () -> Unit, onNext: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(msg) { delay(80); runCatching { focus.requestFocus() } }
    Column(
        modifier.widthIn(max = 520.dp).background(Color(0xFF12141C), RoundedCornerShape(14.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(msg, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent), modifier = Modifier.focusRequester(focus)) {
                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Tentar de novo")
            }
            OutlinedButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Próximo canal", color = Color.White) }
        }
    }
}
