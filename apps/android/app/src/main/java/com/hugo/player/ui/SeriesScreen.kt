package com.hugo.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.data.Catalog
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.Episode
import com.hugo.player.data.Series
import com.hugo.player.data.SeriesDetails
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.ui.components.CategoryRow
import com.hugo.player.ui.components.EmptyState
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.PosterCard

/** Séries: categorias + grade de capas + temporadas/episódios. */
@Composable
fun SeriesContent(catalog: Catalog, query: String, onPlay: (PlaybackRequest) -> Unit) {
    val series = catalog.series
    var categoryId by rememberSaveable { mutableStateOf(ALL_CATEGORIES) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }

    val filtered = remember(series.items, categoryId, query) {
        series.items.filter { (categoryId == ALL_CATEGORIES || it.categoryId == categoryId) && matchesQuery(it.name, query) }
    }
    val selected = remember(detailsId, series.items) { series.items.firstOrNull { it.id == detailsId } }

    val gridState = rememberLazyGridState()
    LaunchedEffect(categoryId, query) { gridState.scrollToItem(0) }

    if (series.items.isEmpty()) {
        EmptyState("Nenhuma série encontrada nesta lista.")
        return
    }

    Box(Modifier.fillMaxSize()) {
        if (selected == null) Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.width(200.dp).fillMaxHeight().background(HugoColors.Panel),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { CategoryRow("Todas as séries", series.items.size, categoryId == ALL_CATEGORIES, HugoColors.Cyan) { categoryId = ALL_CATEGORIES } }
                items(series.categories, key = { it.id }) { cat ->
                    CategoryRow(cat.name, cat.count, cat.id == categoryId, HugoColors.Cyan) { categoryId = cat.id }
                }
            }
            if (filtered.isEmpty()) {
                EmptyState("Nenhuma série nesta categoria.", Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(132.dp),
                    state = gridState,
                    contentPadding = PaddingValues(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    itemsIndexed(filtered, key = { i, s -> "${s.id}#$i" }) { _, s ->
                        PosterCard(
                            title = s.name,
                            imageUrl = s.cover,
                            subtitle = when {
                                s.episodeCount > 0 -> "${s.episodeCount} episódios"
                                s.year != null -> s.year.toString()
                                else -> null
                            },
                            rating = s.rating,
                            onClick = { detailsId = s.id }
                        )
                    }
                }
            }
        }

        selected?.let { s ->
            BackHandler { detailsId = null }
            SeriesDetailsView(s, onPlay = onPlay, onClose = { detailsId = null })
        }
    }
}

@Composable
private fun SeriesDetailsView(series: Series, onPlay: (PlaybackRequest) -> Unit, onClose: () -> Unit) {
    var details by remember(series.id) { mutableStateOf<SeriesDetails?>(null) }
    var error by remember(series.id) { mutableStateOf<String?>(null) }
    var seasonIndex by rememberSaveable(series.id) { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(series.id, attempt) {
        error = null
        try {
            details = CatalogRepository.seriesDetails(series)
        } catch (e: Exception) {
            error = e.message ?: "Erro ao carregar episódios"
        }
    }

    val d = details
    val seasons = d?.seasons.orEmpty()
    val season = seasons.getOrNull(seasonIndex.coerceAtMost((seasons.size - 1).coerceAtLeast(0)))

    fun playEpisode(ep: Episode, list: List<Episode>) {
        val idx = list.indexOf(ep)
        val toRequest = { e: Episode ->
            PlaybackRequest(
                url = e.url,
                title = series.name,
                subtitle = "T${e.season} · E${e.episode} — ${e.name}",
                isLive = false,
            )
        }
        onPlay(toRequest(ep).copy(queue = list.drop(idx + 1).map(toRequest)))
    }

    val firstEpisode = season?.episodes?.firstOrNull()
    DetailsOverlay(
        title = series.name,
        image = d?.cover ?: series.cover,
        meta = listOfNotNull(
            (d?.year ?: series.year)?.toString(),
            (d?.rating ?: series.rating)?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
            if (seasons.isNotEmpty()) "${seasons.size} temporada(s)" else null,
            series.group,
        ).joinToString("  ·  "),
        description = d?.plot ?: series.plot,
        primaryLabel = if (firstEpisode != null) "Assistir T${firstEpisode.season} E${firstEpisode.episode}" else null,
        accent = HugoColors.Cyan,
        onPrimary = { if (firstEpisode != null && season != null) playEpisode(firstEpisode, season.episodes) },
        onClose = onClose,
    ) {
        when {
            error != null -> Column {
                Text(error ?: "", color = Color(0xFFF87171), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { attempt++ }, colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Cyan)) { Text("Tentar novamente") }
            }
            d == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = HugoColors.Cyan, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Carregando episódios…", color = HugoColors.TextMuted, fontSize = 13.sp)
            }
            seasons.isEmpty() -> Text("Nenhum episódio disponível.", color = HugoColors.TextMuted)
            else -> {
                // Temporadas
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(seasons) { i, s ->
                        FocusSurface(
                            onClick = { seasonIndex = i },
                            selected = i == seasonIndex,
                            selectedColor = HugoColors.Cyan,
                            color = HugoColors.CardAlt,
                            focusedColor = HugoColors.Cyan.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                Text("Temporada ${s.season}", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Episódios
                val eps = season?.episodes.orEmpty()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    itemsIndexed(eps, key = { i, e -> "${e.id}#$i" }) { _, ep ->
                        FocusSurface(
                            onClick = { playEpisode(ep, eps) },
                            focusScale = 1.01f,
                            modifier = Modifier.fillMaxWidth().height(58.dp)
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "E${ep.episode}",
                                    color = HugoColors.Cyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.width(48.dp)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(ep.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    ep.plot?.let { Text(it, color = HugoColors.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                                ep.durationSecs?.takeIf { it > 0 }?.let {
                                    Text("${it / 60} min", color = HugoColors.TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compatibilidade com a rota antiga "series/..." do AppNavigation (sem catálogo).
 * A tela completa de séries é SeriesContent, usada pela BrowseScreen.
 */
@Composable
fun SeriesScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(HugoColors.Background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Séries", color = Color.White, fontSize = 28.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "As séries serão carregadas da playlist selecionada.\nCapas e informações vêm da lista do provedor.",
            color = HugoColors.TextMuted,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = HugoColors.Accent)) {
            Text("Voltar")
        }
    }
}
