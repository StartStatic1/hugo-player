package com.hugo.player.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun PlayerScreen(streamUrl: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontSize = 18.sp)
        }

        // Simple controls
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            IconButton(onClick = {
                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                isPlaying = !isPlaying
            }) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}