package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.hugo.player.data.Catalog
import com.hugo.player.data.LiveChannel
import com.hugo.player.player.HugoPlayerFactory
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.ui.components.CategoryRow
import com.hugo.player.ui.components.ChannelLogo
import com.hugo.player.ui.components.EmptyState
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import kotlinx.coroutines.delay

/**
 * TV ao Vivo: categorias | lista de canais com logo | prévia do canal.
 * Toque/OK num canal mostra a prévia; tocar/OK de novo abre em tela cheia.
 */
@Composable
fun LiveContent(catalog: Catalog, query: String, onPlay: (PlaybackRequest) -> Unit) {
    val live = catalog.live
    var categoryId by rememberSaveable { mutableStateOf(ALL_CATEGORIES) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedId by remember { mutableStateOf<String?>(null) }

    val channels = remember(live.items, categoryId, query) {
        live.items.filter { (categoryId == ALL_CATEGORIES || it.categoryId == categoryId) && matchesQuery(it.name, query) }
    }
    val preview = remember(previewId, live.items) { live.items.firstOrNull { it.id == previewId } }

    // Foco parado num canal por 900 ms -> troca a prévia (navegação por D-pad)
    LaunchedEffect(focusedId) {
        val id = focusedId ?: return@LaunchedEffect
        delay(900)
        previewId = id
    }

    fun play(ch: LiveChannel) {
        onPlay(PlaybackRequest(url = ch.url, title = ch.name, subtitle = ch.group, isLive = true, userAgent = ch.userAgent))
    }

    if (live.items.isEmpty()) {
        EmptyState("Nenhum canal ao vivo encontrado nesta lista.")
        return
    }

    Row(Modifier.fillMaxSize()) {
        // Categorias
        LazyColumn(
            Modifier.width(200.dp).fillMaxHeight().background(HugoColors.Panel),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                CategoryRow("Todos os canais", live.items.size, categoryId == ALL_CATEGORIES, HugoColors.Accent) { categoryId = ALL_CATEGORIES }
            }
            items(live.categories, key = { it.id }) { cat ->
                CategoryRow(cat.name, cat.count, cat.id == categoryId, HugoColors.Accent) { categoryId = cat.id }
            }
        }

        // Canais
        val listState = rememberLazyListState()
        LaunchedEffect(categoryId) { listState.scrollToItem(0) }
        if (channels.isEmpty()) {
            EmptyState("Nenhum canal nesta categoria.", Modifier.weight(1.2f))
        } else {
            LazyColumn(
                Modifier.weight(1.2f).fillMaxHeight(),
                state = listState,
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                itemsIndexed(channels, key = { _, ch -> ch.id }) { index, ch ->
                    ChannelRow(
                        number = index + 1,
                        channel = ch,
                        selected = ch.id == previewId,
                        onFocused = { focusedId = ch.id },
                        onClick = { if (previewId == ch.id) play(ch) else previewId = ch.id }
                    )
                }
            }
        }

        // Prévia
        Column(
            Modifier.weight(1f).fillMaxHeight().background(HugoColors.Panel).padding(14.dp)
        ) {
            if (preview == null) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Selecione um canal para a prévia", color = HugoColors.TextMuted, fontSize = 13.sp)
                }
            } else {
                LivePreview(preview, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(preview.logo, preview.name, 48.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(preview.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(preview.group, color = HugoColors.TextMuted, fontSize = 12.sp, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { play(preview) },
                    colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Assistir em tela cheia") }
                Spacer(Modifier.height(8.dp))
                Text("Dica: OK no canal selecionado abre em tela cheia.", color = HugoColors.TextDim, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ChannelRow(
    number: Int,
    channel: LiveChannel,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        onFocused = onFocused,
        selected = selected,
        selectedColor = HugoColors.Accent.copy(alpha = 0.35f),
        focusScale = 1.02f,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().height(60.dp)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$number", color = HugoColors.TextDim, fontSize = 12.sp, modifier = Modifier.width(34.dp))
            ChannelLogo(channel.logo, channel.name, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(channel.group, color = HugoColors.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (channel.catchup) {
                Text("REPLAY", color = HugoColors.Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Mini player da prévia (som ligado, buffer curto). */
@Composable
private fun LivePreview(channel: LiveChannel, modifier: Modifier) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    val ua = remember(channel.userAgent) { channel.userAgent ?: playerUserAgent(context) }
    val player = remember(ua) { HugoPlayerFactory.create(context, ua, lowLatencyPreview = true) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = "Prévia indisponível"; buffering = false }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = null
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    LaunchedEffect(player, channel.url) {
        error = null
        buffering = true
        player.setMediaItem(HugoPlayerFactory.mediaItem(channel.url))
        player.prepare()
        player.playWhenReady = true
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false; isFocusable = false; this.player = player } },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
        if (buffering && error == null) CircularProgressIndicator(color = HugoColors.Accent, modifier = Modifier.size(32.dp))
        error?.let { Text(it, color = HugoColors.TextMuted, fontSize = 12.sp) }
    }
}
