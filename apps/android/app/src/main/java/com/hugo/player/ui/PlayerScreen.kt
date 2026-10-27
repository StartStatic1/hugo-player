package com.hugo.player.ui

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hugo.player.data.ApiException
import com.hugo.player.data.ExternalSubs
import kotlinx.coroutines.launch
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.hugo.player.data.AppSettings
import com.hugo.player.data.WatchHistory
import com.hugo.player.player.HugoPlayerFactory
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.player.StreamTypes
import kotlinx.coroutines.delay

private val Accent = Color(0xFF8B5CF6)
private const val MAX_AUTO_RETRIES = 3
private const val CONTROLS_HIDE_MS = 3_000L

@OptIn(UnstableApi::class)
private val resizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajustar",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Esticar",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
)

@Composable
fun PlayerScreen(streamUrl: String, title: String, onBack: () -> Unit) {
    PlayerScreen(PlaybackRequest(url = streamUrl, title = title), onBack)
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(request: PlaybackRequest, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var current by remember { mutableStateOf(request) }
    var queue by remember { mutableStateOf(request.queue) }

    var isPlaying by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var retryCount by remember { mutableIntStateOf(0) }
    var retryTick by remember { mutableIntStateOf(0) }
    var forceHls by remember { mutableStateOf(false) }
    var resizeIndex by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(C.TIME_UNSET) }
    var isLive by remember { mutableStateOf(current.isLive ?: true) }
    var scrubPosition by remember { mutableStateOf<Float?>(null) }
    val scrubbing = scrubPosition != null
    var showTracks by remember { mutableStateOf(false) }
    var audioOpts by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var textOpts by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    // legenda da internet (OpenSubtitles pelo servidor do Hugo)
    var extOptions by remember { mutableStateOf<List<ExternalSubs.Option>>(emptyList()) }
    var extApplied by remember { mutableStateOf<Long?>(null) }
    var subStatus by remember { mutableStateOf<String?>(null) }
    var autoSubTried by remember { mutableStateOf(false) }
    var readyOnce by remember { mutableStateOf(false) }
    /** legenda ligada sozinha porque o usuário trocou para áudio estrangeiro (desliga ao voltar ao português) */
    var subByAudioSwitch by remember { mutableStateOf(false) }
    /** legenda da internet: o próprio app desenha as falas (permite ajustar a sincronia sem recarregar o vídeo) */
    var extCues by remember { mutableStateOf<List<ExternalSubs.Cue>>(emptyList()) }
    var extActive by remember { mutableStateOf(false) }
    var subOffsetMs by remember { mutableLongStateOf(0L) }
    /** "Desligada" vale só para este vídeo (antes valia para sempre e travava a legenda automática) */
    var subsOffThisVideo by remember { mutableStateOf(false) }
    /** aviso curto na tela ("Buscando legenda…", "Legenda em português ligada"…) */
    var subNotice by remember { mutableStateOf<String?>(null) }
    var subNoticeTick by remember { mutableIntStateOf(0) }
    /** última vez que a legenda do arquivo mostrou texto (para detectar legenda embutida vazia) */
    var lastCueAt by remember { mutableLongStateOf(0L) }
    var embeddedAutoAt by remember { mutableStateOf<Long?>(null) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val scope = rememberCoroutineScope()
    /** "Próximo episódio em N s" (null = sem contagem). */
    var nextCountdown by remember { mutableStateOf<Int?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var sleep by remember { mutableStateOf(SleepOption.OFF) }
    var sleepEndAt by remember { mutableStateOf<Long?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val nextFocus = remember { FocusRequester() }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    fun touch() {
        lastInteraction = System.currentTimeMillis()
        showControls = true
    }

    val userAgent = remember(current) { current.userAgent ?: playerUserAgent(context) }
    val exoPlayer = remember(userAgent) { HugoPlayerFactory.create(context, userAgent) }

    LaunchedEffect(exoPlayer, current, forceHls) {
        errorMsg = null
        errorDetail = null
        loading = true
        extOptions = emptyList(); extApplied = null; subStatus = null; autoSubTried = false; readyOnce = false; subByAudioSwitch = false
        extCues = emptyList(); extActive = false; subOffsetMs = 0L; subsOffThisVideo = false; embeddedAutoAt = null; subNotice = null
        playerViewRef?.subtitleView?.setCues(emptyList())
        exoPlayer.setMediaItem(HugoPlayerFactory.mediaItem(current.url, forceHls))
        exoPlayer.prepare()
        val resumeAt = if (current.isLive != true) WatchHistory.getProgress(context, progressKeyFor(current)) else 0L
        if (resumeAt > 15_000) exoPlayer.seekTo(resumeAt)
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    return
                }
                val parsingError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                if (parsingError && !forceHls && StreamTypes.detect(current.url) != StreamTypes.Kind.HLS) {
                    forceHls = true
                    return
                }
                if (isRetryable(error) && retryCount < MAX_AUTO_RETRIES) {
                    retryCount++
                    retryTick++
                    return
                }
                errorMsg = friendlyError(error)
                errorDetail = "${error.errorCodeName} · ${error.message ?: ""}".trim()
                loading = false
                showControls = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    errorMsg = null
                    retryCount = 0
                    readyOnce = true
                    isLive = exoPlayer.isCurrentMediaItemLive || (current.isLive ?: false)
                }
                if (state == Player.STATE_ENDED && current.isLive != true) {
                    // terminou: marca assistido e, se tem próximo, conta 10 s (dá para cancelar)
                    WatchHistory.markWatched(context, progressKeyFor(current))
                    WatchHistory.removeProgress(context, progressKeyFor(current))
                    if (sleep == SleepOption.END_OF_EPISODE) { onBack(); return }
                    if (queue.isNotEmpty()) { showControls = false; nextCountdown = NEXT_COUNTDOWN_S }
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                if (cueGroup.cues.any { !it.text.isNullOrBlank() }) lastCueAt = System.currentTimeMillis()
            }

            override fun onTracksChanged(tracks: Tracks) {
                val (a, t) = collectTrackOptions(tracks)
                audioOpts = a
                textOpts = t
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing || (exoPlayer.playWhenReady && exoPlayer.playbackState == Player.STATE_BUFFERING)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            saveProgressOf(context, current, exoPlayer.currentPosition, exoPlayer.duration, exoPlayer.playbackState == Player.STATE_ENDED)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(retryTick) {
        if (retryTick == 0) return@LaunchedEffect
        delay(1_500L * retryCount)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        var wasPlaying = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // app saiu da tela (Home, recentes, tela desligou): grava o ponto já, antes que o sistema feche o app
                Lifecycle.Event.ON_PAUSE -> saveProgressOf(context, current, exoPlayer.currentPosition, exoPlayer.duration, exoPlayer.playbackState == Player.STATE_ENDED)
                Lifecycle.Event.ON_STOP -> {
                    wasPlaying = exoPlayer.playWhenReady
                    exoPlayer.pause()
                    saveProgressOf(context, current, exoPlayer.currentPosition, exoPlayer.duration, exoPlayer.playbackState == Player.STATE_ENDED)
                }
                Lifecycle.Event.ON_START -> if (wasPlaying) {
                    if (exoPlayer.isCurrentMediaItemLive) exoPlayer.seekToDefaultPosition()
                    exoPlayer.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(exoPlayer) {
        var lastSave = System.currentTimeMillis()
        while (true) {
            positionMs = exoPlayer.currentPosition
            durationMs = exoPlayer.duration
            nowMs = System.currentTimeMillis()
            // "Continuar assistindo": grava o ponto a cada 15 s enquanto toca (antes só ao sair da tela —
            // fechar o app pelos recentes, atualizar ou o sistema matar o app perdia o ponto)
            if (exoPlayer.isPlaying && nowMs - lastSave >= PROGRESS_SAVE_EVERY_MS) {
                lastSave = nowMs
                saveProgressOf(context, current, exoPlayer.currentPosition, exoPlayer.duration, false)
            }
            delay(500)
        }
    }

    LaunchedEffect(showControls, lastInteraction, errorMsg, isPlaying, scrubbing) {
        if (showControls && errorMsg == null && isPlaying && !scrubbing) {
            delay(CONTROLS_HIDE_MS)
            showControls = false
        }
    }

    LaunchedEffect(showControls, errorMsg, nextCountdown != null) {
        delay(50)
        runCatching {
            when {
                nextCountdown != null -> nextFocus.requestFocus()
                showControls -> playFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }

    /** Vai para o próximo da fila, guardando onde parou no atual. */
    fun goNext() {
        val next = queue.firstOrNull() ?: return
        saveProgressOf(context, current, exoPlayer.currentPosition, exoPlayer.duration, exoPlayer.playbackState == Player.STATE_ENDED)
        nextCountdown = null
        queue = queue.drop(1)
        forceHls = false
        current = next
    }

    fun notify(msg: String?) { subNotice = msg; subNoticeTick++ }

    /** Desliga a legenda da internet (para de desenhar as falas). */
    fun stopExternal() {
        extActive = false
        playerViewRef?.subtitleView?.setCues(emptyList())
    }

    /**
     * Baixa o .srt e liga a legenda da internet. O app desenha as falas na tela do player
     * (sem recarregar o vídeo), o que também permite ajustar a sincronia na hora.
     * announce = mostra aviso na tela (troca de áudio / automática).
     */
    fun applyExternal(opt: ExternalSubs.Option, announce: Boolean, inDialog: Boolean = false) {
        val req = current
        scope.launch {
            if (inDialog) subStatus = "Baixando legenda…"
            val file = try {
                ExternalSubs.file(context, opt)
            } catch (e: ApiException) {
                val msg = if (e.apiError == "subtitle_quota" || e.status == 429) "Limite diário de legendas atingido. Tente mais tarde." else "Não foi possível baixar a legenda."
                if (inDialog) subStatus = msg
                if (announce) notify(msg)
                return@launch
            } catch (e: Exception) {
                if (inDialog) subStatus = "Sem conexão para baixar a legenda."
                if (announce) notify("Sem conexão para baixar a legenda.")
                return@launch
            }
            val cues = withContext(Dispatchers.IO) { runCatching { ExternalSubs.parseSrt(file.readText()) }.getOrDefault(emptyList()) }
            if (req != current) return@launch
            if (cues.isEmpty()) {
                if (inDialog) subStatus = "Essa legenda veio vazia. Escolha outra."
                if (announce) notify("Nenhuma legenda encontrada")
                return@launch
            }
            // legenda do arquivo desligada: só a da internet aparece
            applyTextTrack(exoPlayer, null, null)
            if (extApplied != opt.fileId) subOffsetMs = 0L
            extCues = cues
            extApplied = opt.fileId
            extActive = true
            embeddedAutoAt = null
            subsOffThisVideo = false
            subStatus = null
            if (announce) notify("Legenda em português ligada")
        }
    }

    /**
     * Busca legenda em português.
     *  - inDialog: busca manual pelo menu (mostra o andamento dentro do menu e deixa escolher);
     *  - announce: automática com aviso na tela (troca de áudio) — liga a melhor sozinha;
     *  - nenhum dos dois: automática ao abrir o vídeo (só avisa quando liga).
     */
    fun searchSubs(inDialog: Boolean, announce: Boolean = false) {
        val q = ExternalSubs.queryFor(current)
        if (q == null) {
            if (inDialog) subStatus = "Busca de legenda indisponível para este conteúdo."
            if (announce) notify("Nenhuma legenda encontrada")
            return
        }
        scope.launch {
            if (inDialog) subStatus = "Buscando legenda…"
            if (announce) notify("Buscando legenda…")
            val r = ExternalSubs.search(context, q)
            val msg: String? = when (r) {
                is ExternalSubs.Result.Found -> null
                ExternalSubs.Result.None -> "Nenhuma legenda em português encontrada."
                ExternalSubs.Result.Quota -> "Limite diário de legendas atingido. Tente mais tarde."
                ExternalSubs.Result.NotConfigured -> "Legenda automática ainda não foi ativada no servidor."
                is ExternalSubs.Result.Error -> "Não foi possível buscar a legenda (${r.message})."
            }
            if (r is ExternalSubs.Result.Found) {
                extOptions = r.options.take(6)
                if (inDialog) subStatus = "Escolha uma legenda abaixo."
                // automática: a primeira é a melhor (pt-BR, sem descrição de sons, release mais parecido)
                else applyExternal(r.options.first(), announce = true)
            } else {
                if (inDialog) subStatus = msg
                if (announce) notify(
                    when (r) {
                        ExternalSubs.Result.Quota -> "Limite diário de legendas atingido"
                        ExternalSubs.Result.None -> "Nenhuma legenda encontrada"
                        else -> msg
                    }
                )
            }
        }
    }

    /** Liga a legenda em português do próprio arquivo e confere depois se ela tem texto de verdade. */
    fun applyEmbeddedPt(track: TrackOption, announce: Boolean) {
        stopExternal()
        applyTextTrack(exoPlayer, track.groupIndex, track.trackIndex)
        lastCueAt = 0L
        embeddedAutoAt = System.currentTimeMillis()
        if (announce) notify("Legenda em português ligada")
    }

    // automática ao abrir: filme/episódio com versão legendada ou áudio estrangeiro → legenda em português.
    // Usa a do arquivo se for completa (não "forçada"); senão busca na internet em segundo plano.
    LaunchedEffect(readyOnce) {
        if (!readyOnce || autoSubTried || isLive || current.isLive == true) return@LaunchedEffect
        delay(2_500)
        autoSubTried = true
        if (subsOffThisVideo || extActive) return@LaunchedEffect
        val audioLang = selectedAudioLanguage(exoPlayer.currentTracks)
        if (!wantsAutoSubtitle(current.title, audioLang)) return@LaunchedEffect
        val embedded = portugueseTextTrack(textOpts)
        if (embedded != null) applyEmbeddedPt(embedded, announce = false) else searchSubs(inDialog = false)
    }

    // legenda do arquivo ligada sozinha mas nunca mostra texto (vazia): troca pela da internet
    LaunchedEffect(embeddedAutoAt) {
        val since = embeddedAutoAt ?: return@LaunchedEffect
        var watched = 0L
        while (watched < 90_000) {
            delay(1_000)
            if (embeddedAutoAt != since || extActive || subsOffThisVideo) return@LaunchedEffect
            if (exoPlayer.isPlaying) watched += 1_000
        }
        if (lastCueAt < since && embeddedAutoAt == since) {
            embeddedAutoAt = null
            searchSubs(inDialog = false, announce = true)
        }
    }

    // desenha a fala da legenda da internet na posição atual (+ ajuste de sincronia)
    LaunchedEffect(extActive, extCues, subOffsetMs, playerViewRef) {
        val view = playerViewRef?.subtitleView ?: return@LaunchedEffect
        if (!extActive || extCues.isEmpty()) { view.setCues(emptyList()); return@LaunchedEffect }
        var shown: ExternalSubs.Cue? = null
        var first = true
        var ticks = 0
        while (true) {
            val cue = ExternalSubs.cueAt(extCues, exoPlayer.currentPosition, subOffsetMs)
            ticks++
            // reaplica de vez em quando (o PlayerView pode limpar a tela ao buscar/pausar)
            if (first || cue !== shown || ticks % 10 == 0) {
                shown = cue; first = false
                view.setCues(if (cue == null) emptyList() else listOf(Cue.Builder().setText(cue.text).build()))
            }
            delay(100)
        }
    }

    LaunchedEffect(subNoticeTick) {
        val msg = subNotice ?: return@LaunchedEffect
        delay(if (msg.endsWith("…")) 15_000 else 3_500)
        if (subNotice == msg) subNotice = null
    }

    /**
     * Filme dual áudio: trocou para áudio que não é português → liga legenda em português
     * (a do arquivo, se for completa; senão a da internet), com aviso na tela. Isso vale mesmo se
     * antes tinha escolhido "Desligada". Voltou ao português → desliga a que foi ligada sozinha.
     */
    fun onAudioPicked(opt: TrackOption) {
        applyAudioTrack(exoPlayer, opt.groupIndex, opt.trackIndex)
        if (isLive || current.isLive == true) return
        if (!isForeignAudio(opt, audioOpts)) {
            if (subByAudioSwitch) { applyTextTrack(exoPlayer, null, null); stopExternal(); embeddedAutoAt = null; subByAudioSwitch = false }
            return
        }
        subsOffThisVideo = false
        subByAudioSwitch = true
        val embedded = portugueseTextTrack(textOpts)
        when {
            extActive -> notify("Legenda em português ligada")
            extApplied != null && extCues.isNotEmpty() -> { applyTextTrack(exoPlayer, null, null); extActive = true; notify("Legenda em português ligada") }
            embedded != null -> applyEmbeddedPt(embedded, announce = true)
            extOptions.isNotEmpty() -> { notify("Buscando legenda…"); applyExternal(extOptions.first(), announce = true) }
            else -> searchSubs(inDialog = false, announce = true)
        }
    }

    LaunchedEffect(exoPlayer, speed) { exoPlayer.setPlaybackSpeed(speed) }
    // timer de desligar: pausa e sai do player (a TV pode desligar a tela)
    LaunchedEffect(sleepEndAt) {
        val end = sleepEndAt ?: return@LaunchedEffect
        delay((end - System.currentTimeMillis()).coerceAtLeast(0))
        exoPlayer.pause()
        onBack()
    }

    LaunchedEffect(nextCountdown) {
        val n = nextCountdown ?: return@LaunchedEffect
        if (n <= 0) { goNext(); return@LaunchedEffect }
        delay(1_000)
        if (nextCountdown == n) nextCountdown = n - 1
    }

    BackHandler {
        when {
            nextCountdown != null -> { nextCountdown = null; touch() }
            showControls && errorMsg == null -> showControls = false
            else -> onBack()
        }
    }

    val canSeek = !isLive && durationMs != C.TIME_UNSET && durationMs > 0

    fun togglePlay() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            saveProgressOf(context, current, exoPlayer.currentPosition, exoPlayer.duration, false)
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
            exoPlayer.play()
        }
        touch()
    }

    fun seekBy(deltaMs: Long) {
        if (!canSeek) return
        exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceIn(0, durationMs))
        touch()
    }

    fun manualRetry() {
        retryCount = 0
        errorMsg = null
        errorDetail = null
        loading = true
        if (isLive) exoPlayer.seekToDefaultPosition()
        exoPlayer.prepare()
        exoPlayer.play()
        touch()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> { togglePlay(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { exoPlayer.play(); touch(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { exoPlayer.pause(); touch(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); true }
                    AndroidKeyEvent.KEYCODE_BACK -> false
                    // contagem do próximo episódio na tela: as setas/OK vão para os botões dela
                    else -> if (nextCountdown != null) false else {
                        if (!showControls && errorMsg == null) {
                            when (ev.key.nativeKeyCode) {
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (canSeek) seekBy(-10_000) else touch()
                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (canSeek) seekBy(10_000) else touch()
                                else -> touch()
                            }
                            true
                        } else {
                            lastInteraction = System.currentTimeMillis()
                            false
                        }
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { if (showControls) showControls = false else touch() }
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    isFocusable = false
                    applySubtitleStyle(this)
                }.also { v -> playerViewRef = v }
            },
            update = {
                it.player = exoPlayer
                it.resizeMode = resizeModes[resizeIndex].first
            },
            modifier = Modifier.fillMaxSize()
        )

        if (loading && errorMsg == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(52.dp))
                if (retryCount > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text("Reconectando… ($retryCount/$MAX_AUTO_RETRIES)", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        errorMsg?.let { msg ->
            ErrorPanel(
                message = msg,
                detail = errorDetail,
                onRetry = { manualRetry() },
                onBack = onBack,
                retryFocus = playFocus,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        AnimatedVisibility(
            visible = showControls && errorMsg == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .align(Alignment.TopStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(current.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        current.subtitle?.let { Text(it, color = Color(0xFFCBD5E1), fontSize = 13.sp, maxLines = 1) }
                    }
                    if (isLive) {
                        Text(
                            "AO VIVO",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(Color(0xFFDC2626), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                ) {
                    if (canSeek) {
                        SeekBar(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            scrubPosition = scrubPosition,
                            onScrub = { scrubPosition = it; touch() },
                            onScrubEnd = {
                                scrubPosition?.let { f -> exoPlayer.seekTo((f * durationMs).toLong().coerceIn(0, durationMs)) }
                                scrubPosition = null
                                touch()
                            },
                            onSeekBy = { seekBy(it) },
                        )
                        Spacer(Modifier.height(8.dp))
                    } else if (isLive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Color(0xFFDC2626), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text("Transmissão ao vivo", color = Color.White, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (canSeek) {
                            ControlButton(Icons.Default.Replay10, "Voltar 10 segundos") { seekBy(-10_000) }
                            Spacer(Modifier.width(16.dp))
                        }
                        ControlButton(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "Pausar" else "Reproduzir",
                            big = true,
                            modifier = Modifier.focusRequester(playFocus)
                        ) { togglePlay() }
                        if (canSeek) {
                            Spacer(Modifier.width(16.dp))
                            ControlButton(Icons.Default.Forward10, "Avançar 10 segundos") { seekBy(10_000) }
                        }
                        Spacer(Modifier.width(16.dp))
                        ControlButton(Icons.Default.Refresh, "Recarregar") { manualRetry() }
                        Spacer(Modifier.width(16.dp))
                        ControlButton(Icons.Default.AspectRatio, "Tela: ${resizeModes[resizeIndex].second}") {
                            resizeIndex = (resizeIndex + 1) % resizeModes.size
                            touch()
                        }
                        // sempre visível (mostra "só uma faixa" quando não há escolha)
                        Spacer(Modifier.width(16.dp))
                        ControlButton(Icons.Default.Subtitles, "Legendas e áudio") {
                            showTracks = true
                            touch()
                        }
                        if (canSeek) {
                            Spacer(Modifier.width(16.dp))
                            ControlButton(Icons.Default.Speed, "Velocidade ${speedLabel(speed)}", label = speedLabel(speed)) {
                                speed = nextSpeed(speed)
                                touch()
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        ControlButton(Icons.Default.Bedtime, "Desligar: ${sleep.label}", label = if (sleep == SleepOption.OFF) null else if (sleep == SleepOption.END_OF_EPISODE) "fim" else "${sleep.minutes}") {
                            sleep = SleepOption.cycle(sleep, allowEndOfEpisode = !isLive)
                            sleepEndAt = if (sleep.minutes > 0) System.currentTimeMillis() + sleep.minutes * 60_000L else null
                            touch()
                        }
                        if (queue.isNotEmpty()) {
                            Spacer(Modifier.width(16.dp))
                            ControlButton(Icons.Default.SkipNext, "Próximo: ${queue.first().subtitle ?: queue.first().title}") {
                                goNext()
                                touch()
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tela: ${resizeModes[resizeIndex].second}" +
                            (if (canSeek) "  ·  Velocidade ${speedLabel(speed)}" else "") +
                            when {
                                sleep == SleepOption.END_OF_EPISODE -> "  ·  Desliga no fim do episódio"
                                sleepEndAt != null -> "  ·  Desliga em ${sleepRemainingLabel(sleepEndAt!! - nowMs)}"
                                else -> ""
                            } +
                            (if (canSeek) "  ·  ◀ ▶ na barra avança/volta 10 s" else ""),
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }

        subNotice?.let { msg ->
            Text(
                msg,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .background(Color(0xD9111827), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        nextCountdown?.let { n ->
            NextEpisodeCard(
                seconds = n,
                next = queue.firstOrNull(),
                playFocus = nextFocus,
                onPlayNow = { goNext() },
                onCancel = { nextCountdown = null; touch() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp),
            )
        }

        if (showTracks) {
            val embeddedOn = textOpts.any { it.selected }
            TrackPickerDialog(
                audio = audioOpts,
                text = if (extActive) textOpts.map { it.copy(selected = false) } else textOpts,
                onPickAudio = { opt -> onAudioPicked(opt); showTracks = false; touch() },
                onPickText = { opt ->
                    stopExternal()
                    embeddedAutoAt = null
                    if (opt == null) applyTextTrack(exoPlayer, null, null)
                    else applyTextTrack(exoPlayer, opt.groupIndex, opt.trackIndex)
                    // "Desligada" só para este vídeo
                    subsOffThisVideo = opt == null
                    subByAudioSwitch = false
                    showTracks = false
                    touch()
                },
                onDismiss = { showTracks = false; subStatus = null },
                external = extOptions.map { o ->
                    val selected = extActive && o.fileId == extApplied
                    ExternalRow(o.label + (o.release.takeIf { it.isNotBlank() }?.let { " · ${it.take(28)}" } ?: ""), selected = selected) {
                        if (selected) { showTracks = false; return@ExternalRow }
                        applyExternal(o, announce = true, inDialog = true); showTracks = false; touch()
                    }
                },
                onSearch = if (!isLive && current.isLive != true) ({ searchSubs(inDialog = true) }) else null,
                searchStatus = subStatus,
                textOff = !extActive && !embeddedOn,
                sync = if (extActive) SubtitleSync(
                    label = ExternalSubs.formatOffset(subOffsetMs),
                    onShift = { d -> subOffsetMs = (subOffsetMs + d).coerceIn(-60_000L, 60_000L); touch() },
                    onReset = { subOffsetMs = 0L; touch() },
                ) else null,
            )
        }
    }
}

private const val NEXT_COUNTDOWN_S = 10
/** de quanto em quanto tempo o ponto do "Continuar assistindo" é gravado durante a reprodução */
internal const val PROGRESS_SAVE_EVERY_MS = 15_000L

/**
 * Estilo da legenda (Configurações → Legendas): texto branco com contorno preto e, por padrão,
 * SEM a caixa preta atrás. Ignora cores/fundos que venham dentro do arquivo de legenda.
 */
@OptIn(UnstableApi::class)
internal fun applySubtitleStyle(view: PlayerView) {
    val sv = view.subtitleView ?: return
    val bg = when (AppSettings.subtitleBackground) {
        "translucent" -> 0x99000000.toInt()
        "black" -> android.graphics.Color.BLACK
        else -> android.graphics.Color.TRANSPARENT
    }
    sv.setApplyEmbeddedStyles(false)
    sv.setStyle(
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            bg,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null,
        )
    )
    sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.08f)
}

internal fun progressKeyFor(r: PlaybackRequest): String = WatchHistory.contentKey(r.playlistId ?: "_", r.url)

/** Guarda o ponto (ou marca assistido) do item que está saindo da tela. */
private fun saveProgressOf(context: Context, r: PlaybackRequest, pos: Long, dur: Long, ended: Boolean) {
    if (r.isLive == true) return
    val key = progressKeyFor(r)
    if (ended) { WatchHistory.markWatched(context, key); WatchHistory.removeProgress(context, key); return }
    if (pos > 15_000) {
        WatchHistory.saveProgress(context, key, r.title, r.url, pos, if (dur > 0) dur else 0L, r.playlistId ?: "", logo = r.logo, subtitle = r.subtitle)
    }
}

@Composable
private fun NextEpisodeCard(
    seconds: Int,
    next: PlaybackRequest?,
    playFocus: FocusRequester,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.widthIn(max = 380.dp).background(Color(0xEE0F172A), RoundedCornerShape(14.dp)).padding(18.dp)
    ) {
        Text("Próximo episódio em $seconds s", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        next?.let { Text(it.subtitle ?: it.title, color = Color(0xFFCBD5E1), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CountdownButton("Assistir agora", primary = true, modifier = Modifier.focusRequester(playFocus), onClick = onPlayNow)
            CountdownButton("Cancelar", primary = false, onClick = onCancel)
        }
    }
}

@Composable
private fun CountdownButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .height(42.dp)
            .background(if (focused) Accent else if (primary) Color(0x668B5CF6) else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
            .border(if (focused) 2.dp else 0.dp, Color.White, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    scrubPosition: Float?,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onSeekBy: (Long) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fraction = scrubPosition ?: (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val shownMs = scrubPosition?.let { (it * durationMs).toLong() } ?: positionMs
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = onScrub,
            onValueChangeFinished = onScrubEnd,
            interactionSource = interaction,
            colors = SliderDefaults.colors(
                thumbColor = if (focused) Color.White else Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key.nativeKeyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { onSeekBy(-10_000); true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { onSeekBy(10_000); true }
                        else -> false
                    }
                }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownMs), color = Color.White, fontSize = 12.sp)
            Text("-" + formatTime((durationMs - shownMs).coerceAtLeast(0)) + "  /  " + formatTime(durationMs), color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    /** texto pequeno embaixo do ícone (ex.: "1,5x", "30") */
    label: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val size = if (big) 56.dp else 44.dp
    Box(
        modifier
            .size(size)
            .background(if (focused) Accent else Color(0x44FFFFFF), CircleShape)
            .border(if (focused) 2.dp else 0.dp, Color.White, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(if (big) 32.dp else 24.dp))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    detail: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    retryFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Color(0xEE0F172A), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Não foi possível reproduzir", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color(0xFFCBD5E1), fontSize = 14.sp)
        detail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Color(0xFF64748B), fontSize = 11.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("Tentar de novo")
            }
            OutlinedButton(onClick = onBack) { Text("Voltar", color = Color.White) }
        }
    }
}

internal fun isRetryable(e: PlaybackException): Boolean = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_UNSPECIFIED -> true
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> httpStatus(e).let { it == null || it >= 500 }
    else -> false
}

@OptIn(UnstableApi::class)
internal fun httpStatus(e: PlaybackException): Int? =
    (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode

internal fun friendlyError(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (val code = httpStatus(e)) {
        401, 403 -> "Acesso negado pelo servidor (HTTP $code). Verifique se sua lista está ativa."
        404 -> "Canal ou arquivo não encontrado no servidor (HTTP 404)."
        null -> "O servidor respondeu com erro."
        else -> "O servidor respondeu com erro (HTTP $code)."
    }
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Sem conexão com o servidor do canal."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT ->
        "Tempo esgotado. O servidor demorou para responder."
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "Conexão HTTP bloqueada pelo sistema."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "Formato de stream não reconhecido."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Este aparelho não suporta o formato de vídeo deste conteúdo."
    else -> "Erro ao reproduzir o conteúdo."
}

private fun formatTime(ms: Long): String {
    if (ms == C.TIME_UNSET || ms < 0) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun playerUserAgent(context: Context): String =
    context.getSharedPreferences("hugo", Context.MODE_PRIVATE)
        .getString("userAgent", null)
        ?.takeIf { it.isNotBlank() }
        ?: StreamTypes.DEFAULT_USER_AGENT
