package com.hugo.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import android.widget.Toast
import com.hugo.player.data.CatalogRepository
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.hugo.player.data.TmdbClient
import com.hugo.player.data.WatchHistory
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoIconButton

private val yearRegex = Regex("\\((\\d{4})\\)|\\b(19|20)\\d{2}\\b")

private fun extractYear(title: String): String? {
    val m = yearRegex.find(title) ?: return null
    return m.groupValues.firstOrNull { it.length == 4 } ?: m.value.filter { it.isDigit() }.takeIf { it.length == 4 }
}

private fun cleanTitle(title: String): String =
    title.replace(yearRegex, "").replace(Regex("\\s+"), " ").trim().trimEnd('-', '|', ':')

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
    val scope = rememberCoroutineScope()
    val key = remember(movie) { WatchHistory.contentKey(movie.playlistId, movie.url) }
    var isFav by remember { mutableStateOf(WatchHistory.isFavorite(context, key)) }
    var info by remember { mutableStateOf<TmdbClient.Info?>(null) }
    var similar by remember { mutableStateOf<List<TmdbClient.Similar>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val year = remember(movie.name) { extractYear(movie.name) }
    val title = remember(movie.name) { cleanTitle(movie.name) }

    LaunchedEffect(movie.name) {
        loading = true
        info = TmdbClient.searchMovie(movie.name)
        similar = TmdbClient.similarMovies(movie.name)
        loading = false
    }

    val poster = info?.poster ?: movie.logo

    Box(Modifier.fillMaxSize().background(HugoColors.Background)) {
        poster?.let { p ->
            SubcomposeAsyncImage(model = p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.18f)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC07080F), HugoColors.Background))))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            HugoIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack, label = "Voltar")
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.width(160.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(HugoColors.Card),
                    contentAlignment = Alignment.Center,
                ) {
                    if (poster != null) {
                        SubcomposeAsyncImage(model = poster, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(title, color = HugoColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        (info?.year ?: year)?.let { Text(it, color = HugoColors.TextMuted, fontSize = 13.sp) }
                        info?.rating?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, null, tint = Color(0xFFFBBF24), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(it, color = Color(0xFFFBBF24), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        (info?.genres ?: movie.group.takeIf { it.isNotBlank() && it != "Outros" })?.let {
                            Text(it, color = HugoColors.Accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = HugoColors.Accent, strokeWidth = 2.dp)
                    } else {
                        Text(
                            info?.overview ?: "Sinopse não encontrada.",
                            color = HugoColors.TextMuted,
                            fontSize = 13.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    val playSrc = remember { MutableInteractionSource() }
                    val favSrc = remember { MutableInteractionSource() }
                    val playFocused by playSrc.collectIsFocusedAsState()
                    val favFocused by favSrc.collectIsFocusedAsState()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onPlay(PlaybackRequest(url = movie.url, title = movie.name, subtitle = "Filme", isLive = false, playlistId = movie.playlistId, logo = movie.logo))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent),
                            interactionSource = playSrc,
                            border = if (playFocused) BorderStroke(2.dp, Color.White) else null,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Assistir", fontSize = 13.sp)
                        }
                        Button(
                            onClick = {
                                isFav = WatchHistory.toggleFavorite(context, key)
                                if (isFav) WatchHistory.saveFavoriteMeta(context, key, movie.name, movie.url, movie.logo, movie.playlistId)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isFav) Color(0xFFEC4899) else HugoColors.CardAlt),
                            interactionSource = favSrc,
                            border = if (favFocused) BorderStroke(2.dp, Color.White) else null,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isFav) "Favorito" else "Favoritar", fontSize = 13.sp)
                        }
                    }
                }
            }

            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Semelhantes", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                    itemsIndexed(similar, key = { i, s -> "$i:${s.id}" }) { _, s ->
                        // cartão focável: no controle remoto a seleção aparece e a tela desce até a fileira
                        FocusSurface(
                            onClick = {
                                scope.launch {
                                    val found = runCatching {
                                        CatalogRepository.get(context).page(movie.playlistId, "movie", null, s.title, 0, 1).firstOrNull()
                                    }.getOrNull()
                                    if (found != null) onOpenSimilar(found)
                                    else Toast.makeText(context, "\"${s.title}\" não está na sua lista", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.width(110.dp),
                            color = Color.Transparent,
                            focusedColor = HugoColors.Accent.copy(alpha = 0.35f),
                            focusScale = 1.06f,
                        ) { focused ->
                            Column(Modifier.fillMaxWidth().padding(3.dp)) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(HugoColors.Card),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    s.poster?.let {
                                        SubcomposeAsyncImage(model = it, contentDescription = s.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(s.title, color = if (focused) Color.White else HugoColors.TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
