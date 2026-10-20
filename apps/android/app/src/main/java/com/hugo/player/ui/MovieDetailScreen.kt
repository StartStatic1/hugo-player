package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.WatchHistory
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val yearRegex = Regex("\\((\\d{4})\\)|\\b(19|20)\\d{2}\\b")

private fun extractYear(title: String): String? {
    val m = yearRegex.find(title) ?: return null
    return m.groupValues.firstOrNull { it.length == 4 } ?: m.value.filter { it.isDigit() }.takeIf { it.length == 4 }
}

private fun cleanTitle(title: String): String =
    title.replace(yearRegex, "").replace(Regex("\\s+"), " ").trim().trimEnd('-', '|', ':')

/** Sessão do filme aberto (evita URL longa na rota). */
object MovieSession {
    @Volatile var current: ChannelEntity? = null
}

@Composable
fun MovieDetailScreen(
    movie: ChannelEntity,
    onPlay: (PlaybackRequest) -> Unit,
    onOpenSimilar: (ChannelEntity) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { CatalogRepository.get(context) }
    val key = remember(movie) { WatchHistory.contentKey(movie.playlistId, movie.url) }
    var isFav by remember { mutableStateOf(WatchHistory.isFavorite(context, key)) }
    var similar by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    val year = remember(movie.name) { extractYear(movie.name) }
    val title = remember(movie.name) { cleanTitle(movie.name) }

    LaunchedEffect(movie.playlistId, movie.group) {
        similar = withContext(Dispatchers.IO) {
            repo.page(movie.playlistId, "movie", movie.group, null, 0, 20)
                .filter { it.url != movie.url }
                .take(12)
        }
    }

    Box(Modifier.fillMaxSize().background(HugoColors.Background)) {
        movie.logo?.let { logo ->
            SubcomposeAsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.18f,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC07080F), HugoColors.Background))))

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
        ) {
            HugoIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack, label = "Voltar")
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.width(180.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(HugoColors.Card),
                    contentAlignment = Alignment.Center,
                ) {
                    if (movie.logo != null) {
                        SubcomposeAsyncImage(
                            model = movie.logo,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(title, color = HugoColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        year?.let { Text(it, color = HugoColors.TextMuted, fontSize = 14.sp) }
                        if (movie.group.isNotBlank() && movie.group != "Outros") {
                            Text(movie.group, color = HugoColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Sinopse não disponível nesta lista.",
                        color = HugoColors.TextMuted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                onPlay(PlaybackRequest(url = movie.url, title = movie.name, subtitle = "Filme", isLive = false, playlistId = movie.playlistId, logo = movie.logo))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent),
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Assistir")
                        }
                        Button(
                            onClick = {
                                isFav = WatchHistory.toggleFavorite(context, key)
                                if (isFav) WatchHistory.saveFavoriteMeta(context, key, movie.name, movie.url, movie.logo, movie.playlistId)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isFav) Color(0xFFEC4899) else HugoColors.CardAlt),
                        ) {
                            Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isFav) "Favorito" else "Favoritar")
                        }
                    }
                }
            }

            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text("Semelhantes", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(similar, key = { it.id }) { m ->
                        FocusSurface(
                            onClick = { onOpenSimilar(m) },
                            modifier = Modifier.width(120.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(HugoColors.Card),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (m.logo != null) {
                                        SubcomposeAsyncImage(
                                            model = m.logo,
                                            contentDescription = m.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                Text(
                                    cleanTitle(m.name),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
