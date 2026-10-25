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
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
    val progressKey = remember(current.url, current.playlistId) {
        WatchHistory.contentKey(current.playlistId ?: "_", current.url)
    }

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
        exoPlayer.setMediaItem(HugoPlayerFactory.mediaItem(current.url, forceHls))
        exoPlayer.prepare()
        val resumeAt = if (current.isLive != true) WatchHistory.getProgress(context, progressKey) else 0L
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
                    isLive = exoPlayer.isCurrentMediaItemLive || (current.isLive ?: false)
                }
                if (state == Player.STATE_ENDED && queue.isNotEmpty()) {
                    val next = queue.first()
                    queue = queue.drop(1)
                    forceHls = false
                    current = next
                }
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
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (current.isLive != true && pos > 15_000) {
                WatchHistory.saveProgress(
                    context, progressKey, current.title, current.url, pos,
                    if (dur > 0) dur else 0L, current.playlistId ?: "", logo = current.logo
                )
            }
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
                Lifecycle.Event.ON_STOP -> {
                    wasPlaying = exoPlayer.playWhenReady
                    exoPlayer.pause()
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
        while (true) {
            positionMs = exoPlayer.currentPosition
            durationMs = exoPlayer.duration
            delay(500)
        }
    }

    LaunchedEffect(showControls, lastInteraction, errorMsg, isPlaying, scrubbing) {
        if (showControls && errorMsg == null && isPlaying && !scrubbing) {
            delay(CONTROLS_HIDE_MS)
            showControls = false
        }
    }

    LaunchedEffect(showControls, errorMsg) {
        delay(50)
        runCatching { if (showControls) playFocus.requestFocus() else rootFocus.requestFocus() }
    }

    BackHandler {
        if (showControls && errorMsg == null) showControls = false else onBack()
    }

    val canSeek = !isLive && durationMs != C.TIME_UNSET && durationMs > 0

    fun togglePlay() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else {
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
                    else -> {
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
                }
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
                        if (audioOpts.size > 1 || textOpts.isNotEmpty()) {
                            Spacer(Modifier.width(16.dp))
                            ControlButton(Icons.Default.Subtitles, "Legendas e áudio") {
                                showTracks = true
                                touch()
                            }
                        }
                        if (queue.isNotEmpty()) {
                            Spacer(Modifier.width(16.dp))
                            ControlButton(Icons.Default.SkipNext, "Próximo: ${queue.first().title}") {
                                val next = queue.first()
                                queue = queue.drop(1)
                                forceHls = false
                                current = next
                                touch()
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tela: ${resizeModes[resizeIndex].second}" + if (canSeek) "  ·  ◀ ▶ na barra avança/volta 10 s" else "",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }

        if (showTracks) {
            TrackPickerDialog(
                audio = audioOpts,
                text = textOpts,
                onPickAudio = { opt -> applyAudioTrack(exoPlayer, opt.groupIndex, opt.trackIndex); showTracks = false; touch() },
                onPickText = { opt ->
                    if (opt == null) applyTextTrack(exoPlayer, null, null)
                    else applyTextTrack(exoPlayer, opt.groupIndex, opt.trackIndex)
                    showTracks = false
                    touch()
                },
                onDismiss = { showTracks = false },
            )
        }
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
        Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(if (big) 32.dp else 24.dp))
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
