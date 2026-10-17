package com.hugo.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hugo.player.data.Catalog
import com.hugo.player.data.Movie
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.ui.components.CategoryRow
import com.hugo.player.ui.components.EmptyState
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.PosterCard
import kotlinx.coroutines.delay

/** Filmes: categorias + grade de pôsteres + detalhes. */
@Composable
fun MoviesContent(catalog: Catalog, query: String, onPlay: (PlaybackRequest) -> Unit) {
    val movies = catalog.movies
    var categoryId by rememberSaveable { mutableStateOf(ALL_CATEGORIES) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }

    val filtered = remember(movies.items, categoryId, query) {
        movies.items.filter { (categoryId == ALL_CATEGORIES || it.categoryId == categoryId) && matchesQuery(it.name, query) }
    }
    val details = remember(detailsId, movies.items) { movies.items.firstOrNull { it.id == detailsId } }

    fun play(m: Movie) = onPlay(PlaybackRequest(url = m.url, title = m.name, subtitle = listOfNotNull(m.year?.toString(), m.group).joinToString(" · "), isLive = false))

    if (movies.items.isEmpty()) {
        EmptyState("Nenhum filme encontrado nesta lista.")
        return
    }

    // Estado da grade fica fora do if para preservar a rolagem ao fechar os detalhes
    val gridState = rememberLazyGridState()
    LaunchedEffect(categoryId, query) { gridState.scrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        if (details == null) Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.width(200.dp).fillMaxHeight().background(HugoColors.Panel),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { CategoryRow("Todos os filmes", movies.items.size, categoryId == ALL_CATEGORIES, HugoColors.Pink) { categoryId = ALL_CATEGORIES } }
                items(movies.categories, key = { it.id }) { cat ->
                    CategoryRow(cat.name, cat.count, cat.id == categoryId, HugoColors.Pink) { categoryId = cat.id }
                }
            }
            if (filtered.isEmpty()) {
                EmptyState("Nenhum filme nesta categoria.", Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(132.dp),
                    state = gridState,
                    contentPadding = PaddingValues(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    itemsIndexed(filtered, key = { i, m -> "${m.id}#$i" }) { _, m ->
                        PosterCard(
                            title = m.name,
                            imageUrl = m.poster,
                            subtitle = m.year?.toString(),
                            rating = m.rating,
                            onClick = { detailsId = m.id }
                        )
                    }
                }
            }
        }

        details?.let { m ->
            BackHandler { detailsId = null }
            DetailsOverlay(
                title = m.name,
                image = m.poster,
                meta = listOfNotNull(m.year?.toString(), m.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }, m.group).joinToString("  ·  "),
                description = null,
                primaryLabel = "Assistir",
                accent = HugoColors.Pink,
                onPrimary = { play(m) },
                onClose = { detailsId = null },
            )
        }
    }
}

/** Painel de detalhes (filme/série) por cima da grade. */
@Composable
fun DetailsOverlay(
    title: String,
    image: String?,
    meta: String,
    description: String?,
    primaryLabel: String?,
    accent: Color,
    onPrimary: () -> Unit,
    onClose: () -> Unit,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { primaryFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xF207080F))) {
        if (!image.isNullOrBlank()) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.18f,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(HugoColors.Background, Color(0x9907080F)))))
        }
        Row(Modifier.fillMaxSize().padding(28.dp)) {
            Box(
                Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(HugoColors.CardAlt),
                contentAlignment = Alignment.Center
            ) {
                if (!image.isNullOrBlank()) {
                    AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(title, color = HugoColors.TextMuted, modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, color = HugoColors.TextMuted, fontSize = 14.sp)
                }
                description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Color(0xFFCBD5E1), fontSize = 14.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (primaryLabel != null) {
                        Button(
                            onClick = onPrimary,
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            modifier = Modifier.focusRequester(primaryFocus)
                        ) { Text(primaryLabel) }
                    }
                    OutlinedButton(
                        onClick = onClose,
                        modifier = if (primaryLabel == null) Modifier.focusRequester(primaryFocus) else Modifier
                    ) { Text("Voltar", color = Color.White) }
                }
                if (extra != null) {
                    Spacer(Modifier.height(16.dp))
                    extra()
                }
            }
        }
    }
}
