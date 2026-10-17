package com.hugo.player.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.db.CategoryEntity
import com.hugo.player.ui.components.ChannelLogo
import com.hugo.player.ui.components.CategoryRow
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoIconButton
import com.hugo.player.ui.components.PosterCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

enum class BrowseType(val dbType: String, val title: String, val unit: String, val accent: Color, val icon: ImageVector) {
    LIVE("live", "TV ao Vivo", "canais", Color(0xFF8B5CF6), Icons.Default.LiveTv),
    MOVIES("movie", "Filmes", "filmes", Color(0xFFEC4899), Icons.Default.Movie),
    SERIES("series", "Séries", "séries", Color(0xFF06B6D4), Icons.Default.VideoLibrary),
}

/** Item exibido na grade (canal, filme ou série agrupada). */
data class BrowseItem(
    val key: String,
    val title: String,
    val image: String?,
    val subtitle: String?,
    val url: String?,
    val seriesName: String? = null,
)

/**
 * Estado das abas (fica no back stack: voltar do player mantém lista e rolagem).
 * Paginação de 100 em 100; filtro por categoria e busca no banco local (Room).
 */
class BrowseViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CatalogRepository.get(app)
    private var playlistId = ""
    private var type = BrowseType.LIVE
    private var bound = false

    var categories by mutableStateOf<List<CategoryEntity>>(emptyList()); private set
    var allCount by mutableIntStateOf(0); private set
    var selectedGroup by mutableStateOf<String?>(null); private set
    var query by mutableStateOf(""); private set
    var items by mutableStateOf<List<BrowseItem>>(emptyList()); private set
    var total by mutableIntStateOf(0); private set
    var initialLoading by mutableStateOf(true); private set
    var loadingMore by mutableStateOf(false); private set
    /** Incrementado a cada troca de filtro: a tela volta a grade para o topo. */
    var resetToken by mutableIntStateOf(0); private set

    private var page = 0
    private var hasMore = true
    private var generation = 0
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    fun bind(playlistId: String, type: BrowseType) {
        if (bound && this.playlistId == playlistId && this.type == type) return
        bound = true
        this.playlistId = playlistId
        this.type = type
        viewModelScope.launch {
            categories = repo.getCategories(playlistId, type.dbType)
            allCount = if (type == BrowseType.SERIES) repo.seriesCount(playlistId, null, null) else repo.count(playlistId, type.dbType)
        }
        reload()
    }

    fun selectGroup(group: String?) {
        if (group == selectedGroup) return
        selectedGroup = group
        reload()
    }

    fun updateQuery(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            reload()
        }
    }

    private fun reload() {
        loadJob?.cancel()
        val gen = ++generation
        page = 0
        hasMore = true
        initialLoading = true
        loadingMore = false
        resetToken++
        loadJob = viewModelScope.launch {
            val first = fetch(0)
            if (gen != generation) return@launch
            items = first
            hasMore = first.size >= CatalogRepository.PAGE_SIZE
            initialLoading = false
            total = if (type == BrowseType.SERIES) repo.seriesCount(playlistId, selectedGroup, query)
            else repo.countFiltered(playlistId, type.dbType, selectedGroup, query)
        }
    }

    fun loadMore() {
        if (!hasMore || loadingMore || initialLoading) return
        val gen = generation
        loadingMore = true
        viewModelScope.launch {
            val next = fetch(page + 1)
            if (gen != generation) return@launch
            page++
            items = items + next
            hasMore = next.size >= CatalogRepository.PAGE_SIZE
            loadingMore = false
        }
    }

    private suspend fun fetch(p: Int): List<BrowseItem> = when (type) {
        BrowseType.SERIES -> repo.seriesPage(playlistId, selectedGroup, query, p).map {
            BrowseItem(
                key = "s:" + it.seriesName,
                title = it.seriesName,
                image = it.logo,
                subtitle = if (it.seasons > 1) "${it.seasons} temporadas" else "${it.episodes} episódio(s)",
                url = null,
                seriesName = it.seriesName,
            )
        }
        else -> repo.page(playlistId, type.dbType, selectedGroup, query, p).map {
            BrowseItem(key = "c:${it.id}", title = it.name, image = it.logo, subtitle = null, url = it.url)
        }
    }
}

@Composable
fun BrowseScreen(
    playlistId: String,
    type: BrowseType,
    onPlay: (url: String, title: String) -> Unit,
    onOpenSeries: (seriesName: String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: BrowseViewModel = viewModel()
    LaunchedEffect(playlistId, type) { vm.bind(playlistId, type) }

    val gridState = rememberLazyGridState()
    val catState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val gridFocus = remember { FocusRequester() }
    var handledReset by rememberSaveable { mutableIntStateOf(-1) }
    val nf = remember { NumberFormat.getIntegerInstance(Locale("pt", "BR")) }

    // Volta ao topo quando muda categoria/busca (mas não ao voltar do player)
    LaunchedEffect(vm.resetToken) {
        if (handledReset != -1 && handledReset != vm.resetToken) gridState.scrollToItem(0)
        handledReset = vm.resetToken
    }
    // Carrega mais ao chegar perto do fim
    LaunchedEffect(gridState) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to gridState.layoutInfo.totalItemsCount
        }.collect { (last, count) -> if (count > 0 && last >= count - 12) vm.loadMore() }
    }

    Column(Modifier.fillMaxSize().background(HugoColors.Background)) {
        // Topo: voltar, título, busca
        Row(
            Modifier.fillMaxWidth().background(HugoColors.Panel).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HugoIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(type.accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Icon(type.icon, null, tint = type.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(type.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (vm.initialLoading) "Carregando..." else "${nf.format(vm.total)} ${type.unit}",
                    color = HugoColors.TextMuted, fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(20.dp))
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::updateQuery,
                placeholder = { Text("Buscar em ${type.title}", color = HugoColors.TextDim, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = HugoColors.TextMuted) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) IconButton(onClick = { vm.updateQuery("") }) { Icon(Icons.Default.Clear, "Limpar busca", tint = HugoColors.TextMuted) }
                },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); runCatching { gridFocus.requestFocus() } }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = type.accent, unfocusedBorderColor = Color(0xFF2A3148),
                    focusedContainerColor = Color(0xFF0C0E18), unfocusedContainerColor = Color(0xFF0C0E18), cursorColor = type.accent,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            )
        }

        Row(Modifier.fillMaxSize()) {
            // Sidebar de categorias (estreita)
            LazyColumn(
                state = catState,
                modifier = Modifier.width(190.dp).fillMaxHeight().background(HugoColors.Panel),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "__all") {
                    CategoryRow("Todos", vm.allCount.takeIf { it > 0 }, vm.selectedGroup == null, type.accent) { vm.selectGroup(null) }
                }
                items(vm.categories, key = { it.name }) { cat ->
                    CategoryRow(
                        cat.name,
                        cat.count.takeIf { type != BrowseType.SERIES },
                        vm.selectedGroup == cat.name,
                        type.accent
                    ) { vm.selectGroup(cat.name) }
                }
            }

            // Grade de conteúdo
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    vm.initialLoading && vm.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = type.accent)
                    }
                    vm.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (vm.query.isNotBlank()) "Nada encontrado para \"${vm.query}\"."
                            else if (vm.allCount == 0) "Nenhum conteúdo de ${type.title} nesta playlist.\nTente \"Atualizar conteúdo\" na Home."
                            else "Nenhum item nesta categoria.",
                            color = HugoColors.TextMuted, textAlign = TextAlign.Center
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (type == BrowseType.LIVE) 128.dp else 122.dp),
                        state = gridState,
                        contentPadding = PaddingValues(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(vm.items, key = { _, it -> it.key }) { index, item ->
                            val mod = if (index == 0) Modifier.focusRequester(gridFocus) else Modifier
                            val click = {
                                if (item.seriesName != null) onOpenSeries(item.seriesName)
                                else item.url?.let { onPlay(it, item.title) }
                                Unit
                            }
                            if (type == BrowseType.LIVE) LiveCard(item, mod, click)
                            else PosterCard(title = item.title, imageUrl = item.image, subtitle = item.subtitle, rating = null, onClick = click, modifier = mod)
                        }
                        if (vm.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp), color = type.accent, strokeWidth = 3.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Card de canal ao vivo no mesmo formato vertical dos pôsteres: logo em cima, nome embaixo. */
@Composable
private fun LiveCard(item: BrowseItem, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier, focusScale = 1.07f) { focused ->
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF0F1220)).padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                ChannelLogo(item.image, item.title, size = 96.dp, modifier = Modifier.fillMaxSize())
            }
            Text(
                item.title,
                color = if (focused) Color.White else Color(0xFFE2E8F0),
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
    }
}
