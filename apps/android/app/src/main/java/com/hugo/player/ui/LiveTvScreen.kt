package com.hugo.player.ui

import com.hugo.player.ui.components.liveCategoryLazyIndex
import com.hugo.player.ui.components.lazyFocusRestore
import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.IconButton
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.LiveRecents
import com.hugo.player.data.WatchHistory
import com.hugo.player.data.db.CategoryEntity
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.data.CrashLog
import com.hugo.player.data.epg.EpgNowNext
import com.hugo.player.data.epg.EpgProgram
import com.hugo.player.data.epg.LiveEpg
import com.hugo.player.player.HugoPlayerFactory
import com.hugo.player.player.StreamTypes
import com.hugo.player.ui.components.ChannelLogo
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoIconButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** Estado da aba TV ao vivo em 3 colunas (TV / tela larga). */
class LiveTvViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CatalogRepository.get(app)
    var playlistId = ""; private set
    private var bound = false

    var categories by mutableStateOf<List<CategoryEntity>>(emptyList()); private set
    var allCount by mutableIntStateOf(0); private set
    var favCount by mutableIntStateOf(0); private set
    var recCount by mutableIntStateOf(0); private set
    var filter by mutableStateOf(LiveFilters.ALL); private set
    var query by mutableStateOf(""); private set
    var items by mutableStateOf<List<ChannelEntity>>(emptyList()); private set
    var total by mutableIntStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var focused by mutableStateOf<ChannelEntity?>(null); private set
    var focusedIndex by mutableIntStateOf(0); private set
    var guide by mutableStateOf<List<EpgProgram>>(emptyList()); private set
    val epg: SnapshotStateMap<String, EpgNowNext> = mutableStateMapOf()
    val favorites = mutableStateMapOf<String, Boolean>()
    /** muda a cada troca de lista (a tela volta o scroll para o topo) */
    var listToken by mutableIntStateOf(0); private set

    private var page = 0
    private var hasMore = false
    private var loadingMore = false
    private var generation = 0
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var filterJob: Job? = null
    private var guideJob: Job? = null
    private var epgJob: Job? = null
    /** lista inteira de favoritos/recentes (zapping usa a mesma) */
    private var fixedList: List<ChannelEntity>? = null

    fun bind(playlistId: String) {
        if (bound && this.playlistId == playlistId) { refreshCounts(); return }
        bound = true
        this.playlistId = playlistId
        viewModelScope.launch {
            categories = repo.getCategories(playlistId, "live")
            allCount = repo.count(playlistId, "live")
        }
        refreshCounts()
        reload()
    }

    private fun favoriteUrls(): List<String> {
        val ctx = getApplication<Application>()
        return WatchHistory.listFavorites(ctx).filter { it.playlistId == playlistId }.map { it.url }
    }

    /** Contagens de Favoritos/Recentes (mudam ao voltar do player). */
    fun refreshCounts() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            favCount = repo.liveByUrls(playlistId, favoriteUrls()).size
            recCount = repo.liveByUrls(playlistId, LiveRecents.list(ctx, playlistId)).size
            if (filter == LiveFilters.REC || filter == LiveFilters.FAV) reloadKeepFocus()
        }
    }

    /** Seleção por foco na coluna de categorias (com atraso, para não recarregar a cada passo). */
    fun focusFilter(f: String) {
        filterJob?.cancel()
        filterJob = viewModelScope.launch { delay(450); selectFilter(f) }
    }

    fun selectFilter(f: String) {
        filterJob?.cancel()
        if (f == filter && query.isBlank()) return
        filter = f
        if (query.isNotBlank()) query = ""
        reload()
    }

    fun updateQuery(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(350); reload() }
    }

    private fun reloadKeepFocus() {
        val url = focused?.url
        viewModelScope.launch {
            val list = loadFixed() ?: return@launch
            items = list
            total = list.size
            val i = list.indexOfFirst { it.url == url }
            if (i >= 0) focusedIndex = i
        }
    }

    private suspend fun loadFixed(): List<ChannelEntity>? {
        val ctx = getApplication<Application>()
        val urls = when (filter) {
            LiveFilters.FAV -> favoriteUrls()
            LiveFilters.REC -> LiveRecents.list(ctx, playlistId)
            else -> return null
        }
        val q = query.trim()
        return repo.liveByUrls(playlistId, urls).let { l -> if (q.isEmpty()) l else l.filter { it.name.contains(q, ignoreCase = true) } }
    }

    private fun reload() {
        loadJob?.cancel()
        val gen = ++generation
        loading = true
        page = 0
        hasMore = false
        loadingMore = false
        listToken++
        loadJob = viewModelScope.launch {
            val fixed = loadFixed()
            val first: List<ChannelEntity>
            if (fixed != null) {
                fixedList = fixed
                first = fixed
                total = fixed.size
            } else {
                fixedList = null
                // busca procura em todos os canais; sem busca, na categoria escolhida
                val group = if (query.isNotBlank()) null else LiveFilters.group(filter)
                first = repo.page(playlistId, "live", group, query, 0)
                hasMore = first.size >= CatalogRepository.PAGE_SIZE
                total = repo.countFiltered(playlistId, "live", group, query)
            }
            if (gen != generation) return@launch
            items = first
            loading = false
            loadFavFlags(first)
            val restore = LiveSession.lastPlayedUrl?.let { u -> first.indexOfFirst { it.url == u } } ?: -1
            setFocus(first.getOrNull(restore.coerceAtLeast(0)), restore.coerceAtLeast(0))
        }
    }

    fun loadMore() {
        if (!hasMore || loadingMore || loading || fixedList != null) return
        val gen = generation
        loadingMore = true
        viewModelScope.launch {
            val group = if (query.isNotBlank()) null else LiveFilters.group(filter)
            val next = repo.page(playlistId, "live", group, query, page + 1)
            if (gen != generation) return@launch
            page++
            items = items + next
            hasMore = next.size >= CatalogRepository.PAGE_SIZE
            loadingMore = false
            loadFavFlags(next)
        }
    }

    private fun loadFavFlags(list: List<ChannelEntity>) {
        val ctx = getApplication<Application>()
        val keys = WatchHistory.favoriteKeys(ctx)
        list.forEach { favorites[it.url] = keys.contains(WatchHistory.contentKey(playlistId, it.url)) }
    }

    fun toggleFavorite(c: ChannelEntity) {
        val ctx = getApplication<Application>()
        val key = WatchHistory.contentKey(playlistId, c.url)
        val now = WatchHistory.toggleFavorite(ctx, key)
        if (now) WatchHistory.saveFavoriteMeta(ctx, key, c.name, c.url, c.logo, playlistId)
        favorites[c.url] = now
        favCount = (favCount + if (now) 1 else -1).coerceAtLeast(0)
        if (filter == LiveFilters.FAV) reloadKeepFocus()
    }

    fun setFocus(c: ChannelEntity?, index: Int) {
        if (c?.url == focused?.url && index == focusedIndex) return
        focused = c
        focusedIndex = index
        guide = emptyList()
        guideJob?.cancel()
        if (c == null) return
        val ctx = getApplication<Application>()
        guideJob = viewModelScope.launch {
            delay(350)
            LiveEpg.nowNext(ctx, playlistId, listOf(c))[c.url]?.let { epg[c.url] = it }
            guide = LiveEpg.guide(ctx, playlistId, c, limit = 16)
        }
    }

    /** EPG (agora) das linhas visíveis. */
    fun requestEpg(visible: List<ChannelEntity>) {
        epgJob?.cancel()
        if (visible.isEmpty()) return
        val ctx = getApplication<Application>()
        epgJob = viewModelScope.launch {
            delay(400)
            val res = LiveEpg.nowNext(ctx, playlistId, visible)
            res.forEach { (k, v) -> epg[k] = v }
        }
    }

    fun cachedEpg(c: ChannelEntity): EpgNowNext? = epg[c.url] ?: LiveEpg.cached(playlistId, c)

    fun playRequestTitle(): String = when (filter) {
        LiveFilters.FAV -> "Favoritos"
        LiveFilters.REC -> "Recentes"
        LiveFilters.ALL -> "Todos"
        else -> filter
    }.let { if (query.isNotBlank()) "Busca: $query" else it }

    fun playRequest(c: ChannelEntity, index: Int): LivePlayRequest {
        val title = playRequestTitle()
        return LivePlayRequest(
            source = LiveListSource(playlistId, if (query.isNotBlank()) LiveFilters.ALL else filter, query, title),
            channel = c,
            number = index + 1,
            fixedList = fixedList,
        )
    }
}

@kotlin.OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveTvScreen(playlistId: String, onPlay: (LivePlayRequest) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: LiveTvViewModel = viewModel()
    LaunchedEffect(playlistId) { vm.bind(playlistId); CrashLog.step("TV ao vivo: abriu") }
    val nf = remember { NumberFormat.getIntegerInstance(Locale("pt", "BR")) }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val focusTarget = remember { FocusRequester() }
    val previewFocus = remember { FocusRequester() }
    val catFocus = remember { FocusRequester() }
    val catState = rememberLazyListState()
    val previewAllowed = remember { livePreviewAllowed(context) }
    val preview = remember { mutableStateOf<ExoPlayer?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }

    // volta do player / troca de lista: rola até o canal focado e devolve o foco
    LaunchedEffect(vm.listToken, vm.loading) {
        if (vm.loading) return@LaunchedEffect
        // o player pode ter zapeado: o foco volta no último canal assistido
        LiveSession.lastPlayedUrl?.let { u ->
            val j = vm.items.indexOfFirst { it.url == u }
            if (j >= 0) vm.setFocus(vm.items[j], j)
        }
        val i = vm.focusedIndex.coerceIn(0, (vm.items.size - 1).coerceAtLeast(0))
        if (vm.items.isNotEmpty()) {
            listState.scrollToItem((i - 3).coerceAtLeast(0))
            if (!initialFocusDone || LiveSession.lastPlayedUrl != null) {
                delay(60)
                safeRequestFocus(focusTarget)
                initialFocusDone = true
                LiveSession.lastPlayedUrl = null
            }
        }
    }
    // EPG das linhas visíveis + carregar mais ao chegar perto do fim
    LaunchedEffect(listState, vm.items) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { idx ->
                val visible = idx.mapNotNull { vm.items.getOrNull(it) }
                vm.requestEpg(visible)
                if ((idx.lastOrNull() ?: 0) >= vm.items.size - 15) vm.loadMore()
            }
    }

    fun play(c: ChannelEntity, index: Int) {
        // solta a prévia antes do player em tela cheia (dois decoders travam TV fraca)
        preview.value?.let { it.stop(); it.clearMediaItems() }
        LiveSession.lastPlayedUrl = c.url
        onPlay(vm.playRequest(c, index))
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(HugoColors.Background)) {
    // Tamanhos por largura: TV/tablet = 3 colunas cheias; celular deitado = 3 colunas compactas;
    // tela estreita (< 600dp) = categorias + canais (sem prévia).
    val w = maxWidth
    val shortScreen = maxHeight < 480.dp
    val showPreview = w >= 600.dp
    val catWidth = when { w >= 1000.dp -> 210.dp; w >= 840.dp -> 190.dp; w >= 600.dp -> 160.dp; else -> 130.dp }
    val previewWidth = when { w >= 1000.dp -> 380.dp; w >= 840.dp -> 320.dp; else -> 260.dp }
    Column(Modifier.fillMaxSize()) {
        // ---------- topo ----------
        Row(
            Modifier.fillMaxWidth().background(HugoColors.Panel).padding(horizontal = if (shortScreen) 10.dp else 16.dp, vertical = if (shortScreen) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HugoIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(HugoColors.Accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.LiveTv, null, tint = HugoColors.Accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("TV ao Vivo", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(if (vm.loading) "Carregando..." else "${nf.format(vm.total)} canais", color = HugoColors.TextMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.width(12.dp))
            // faixa de informações (some o que não couber)
            HeaderStats(
                playlistName = LiveTvCache.playlistName?.takeIf { LiveTvCache.playlistId == playlistId },
                total = vm.allCount,
                categoryLabel = vm.playRequestTitle(),
                categoryCount = vm.total,
                loading = vm.loading,
                nf = nf,
                compact = shortScreen,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Clock()
        }

        Row(Modifier.fillMaxSize()) {
            // ---------- 1) categorias ----------
            LazyColumn(
                state = catState,
                // volta para a categoria selecionada (sem focusRestorer: crash no Fire TV)
                modifier = Modifier.width(catWidth).fillMaxHeight().background(HugoColors.Panel)
                    .lazyFocusRestore(catState, catFocus) { liveCategoryLazyIndex(vm.filter, LiveFilters.FAV, LiveFilters.REC, LiveFilters.ALL, vm.categories.map { it.name }) },
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                fun catMod(f: String) = if (vm.filter == f) Modifier.focusRequester(catFocus) else Modifier
                item(key = LiveFilters.FAV) { LiveCategoryRow(Icons.Default.Star, "Favoritos", vm.favCount, vm.filter == LiveFilters.FAV && vm.query.isBlank(), { vm.focusFilter(LiveFilters.FAV) }, catMod(LiveFilters.FAV)) { vm.selectFilter(LiveFilters.FAV) } }
                item(key = LiveFilters.REC) { LiveCategoryRow(Icons.Default.History, "Recentes", vm.recCount, vm.filter == LiveFilters.REC && vm.query.isBlank(), { vm.focusFilter(LiveFilters.REC) }, catMod(LiveFilters.REC)) { vm.selectFilter(LiveFilters.REC) } }
                item(key = LiveFilters.ALL) { LiveCategoryRow(Icons.Default.Apps, "Todos", vm.allCount, vm.filter == LiveFilters.ALL && vm.query.isBlank(), { vm.focusFilter(LiveFilters.ALL) }, catMod(LiveFilters.ALL)) { vm.selectFilter(LiveFilters.ALL) } }
                itemsIndexed(vm.categories, key = { i, c -> "g:$i:" + c.name }) { _, cat ->
                    LiveCategoryRow(Icons.Default.Folder, cat.name, cat.count, vm.filter == cat.name && vm.query.isBlank(), { vm.focusFilter(cat.name) }, catMod(cat.name)) { vm.selectFilter(cat.name) }
                }
            }

            // ---------- 2) canais ----------
            Box(Modifier.weight(1f).fillMaxHeight().background(HugoColors.Background)) {
                when {
                    vm.loading && vm.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = HugoColors.Accent) }
                    vm.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                vm.query.isNotBlank() -> "Nenhum canal encontrado."
                                vm.filter == LiveFilters.FAV -> "Sem favoritos ainda.\nSegure OK (ou MENU) num canal para favoritar."
                                vm.filter == LiveFilters.REC -> "Os canais que você assistir aparecem aqui."
                                else -> "Nenhum canal."
                            },
                            color = HugoColors.TextMuted, textAlign = TextAlign.Center,
                        )
                    }
                    else -> LazyColumn(
                        state = listState,
                        // volta para o canal focado (sem focusRestorer: crash no Fire TV)
                        modifier = Modifier.fillMaxSize().lazyFocusRestore(listState, focusTarget) { vm.focusedIndex }.onPreviewKeyEvent { ev ->
                            // controle: DIREITA da lista vai sempre para a prévia (sem busca de foco "livre" entrando no vídeo)
                            if (showPreview && vm.focused != null && liveDpadRight(ev.key.nativeKeyCode, ev.type == KeyEventType.KeyDown)) {
                                safeRequestFocus(previewFocus)
                            } else false
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(vm.items, key = { _, c -> c.id }) { index, c ->
                            val mod = if (index == vm.focusedIndex) Modifier.focusRequester(focusTarget) else Modifier
                            LiveChannelRow(
                                number = index + 1,
                                channel = c,
                                now = vm.cachedEpg(c)?.now,
                                compact = shortScreen,
                                isFavorite = vm.favorites[c.url] == true,
                                selected = vm.focused?.url == c.url,
                                modifier = mod,
                                onFocused = { vm.setFocus(c, index); CrashLog.step("TV ao vivo: canal ${index + 1}") },
                                onClick = {
                                    // toque (tablet): 1º toque mostra a prévia, 2º assiste; no controle o foco já selecionou
                                    if (!showPreview || vm.focused?.url == c.url) play(c, index) else vm.setFocus(c, index)
                                },
                                onToggleFavorite = { vm.toggleFavorite(c) },
                            )
                        }
                    }
                }
            }

            // ---------- 3) prévia + EPG ----------
            if (showPreview) PreviewPanel(
                vm = vm,
                previewAllowed = previewAllowed,
                playerHolder = preview,
                onWatch = { vm.focused?.let { play(it, vm.focusedIndex) } },
                previewFocus = previewFocus,
                onBackToList = { safeRequestFocus(focusTarget) },
                compact = shortScreen || w < 840.dp,
                modifier = Modifier.width(previewWidth).fillMaxHeight(),
            )
        }
    }
    }
}

/** Chips do topo: categoria atual, total da lista, data e nome da playlist, por prioridade conforme a largura. */
@Composable
private fun HeaderStats(
    playlistName: String?, total: Int, categoryLabel: String, categoryCount: Int, loading: Boolean,
    nf: NumberFormat, compact: Boolean, modifier: Modifier,
) {
    val today = remember { java.text.SimpleDateFormat("EEE, dd/MM", Locale("pt", "BR")).format(java.util.Date()).replaceFirstChar { it.uppercase() } }
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterEnd) {
        val items = buildList {
            add(Triple(Icons.Default.Folder, categoryLabel, if (loading) "…" else nf.format(categoryCount)))
            if (total > 0) add(Triple(Icons.Default.LiveTv, "Total", nf.format(total)))
            add(Triple(Icons.Default.CalendarToday, today, null))
            playlistName?.let { add(Triple(Icons.AutoMirrored.Filled.PlaylistPlay, it, null)) }
        }
        val fit = headerChipsThatFit(maxWidth.value, items.map { (_, label, value) -> label.length + (value?.length ?: 0) }, compact)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // ordem na tela: playlist, data, total, categoria (a categoria, mais útil, fica sempre)
            items.take(fit).asReversed().forEach { (icon, label, value) -> HeaderChip(icon, label, value, compact) }
        }
    }
}

@Composable
private fun HeaderChip(icon: ImageVector, label: String, value: String?, compact: Boolean) {
    Row(
        Modifier.height(if (compact) 28.dp else 34.dp).background(HugoColors.Card, RoundedCornerShape(9.dp))
            .border(1.dp, Color(0xFF2A3148), RoundedCornerShape(9.dp)).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = HugoColors.Accent, modifier = Modifier.size(if (compact) 13.dp else 15.dp))
        Spacer(Modifier.width(6.dp))
        if (value != null) {
            Text(value, color = Color.White, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = HugoColors.TextMuted, fontSize = if (compact) 10.sp else 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    Text(formatHm(now), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LiveCategoryRow(icon: ImageVector, name: String, count: Int, selected: Boolean, onFocused: () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        onFocused = onFocused,
        selected = selected,
        selectedColor = HugoColors.Accent,
        color = HugoColors.CardAlt,
        focusedColor = Color(0xFF4C3A86),
        focusScale = 1.02f,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().height(44.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) Color.White else HugoColors.TextMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("$count", color = if (selected) Color.White else HugoColors.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LiveChannelRow(
    number: Int,
    channel: ChannelEntity,
    now: EpgProgram?,
    isFavorite: Boolean,
    selected: Boolean,
    modifier: Modifier,
    compact: Boolean = false,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    var longPressed by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        onFocused = onFocused,
        color = if (selected) Color(0xFF1B1F33) else HugoColors.Card,
        focusedColor = Color(0xFF2A2350),
        focusScale = 1.0f,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().height(if (compact) 54.dp else 62.dp).onPreviewKeyEvent { ev ->
            val code = ev.key.nativeKeyCode
            // segurar OK ou apertar MENU / ★ = favoritar
            val longOk = (code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER) &&
                ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 1
            val menu = (code == AndroidKeyEvent.KEYCODE_MENU || code == AndroidKeyEvent.KEYCODE_BOOKMARK) && ev.type == KeyEventType.KeyUp
            val ok = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER
            when {
                longOk -> { longPressed = true; onToggleFavorite(); true }
                menu -> { onToggleFavorite(); true }
                // soltar o OK depois de segurar não pode abrir o canal
                ok && ev.type == KeyEventType.KeyUp && longPressed -> { longPressed = false; true }
                ok && ev.nativeKeyEvent.repeatCount > 0 -> true
                else -> false
            }
        },
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$number", color = if (focused) Color.White else HugoColors.TextMuted, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(44.dp),
            )
            Spacer(Modifier.width(10.dp))
            ChannelLogo(channel.logo, channel.name, size = if (compact) 38.dp else 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) {
                    Text(now.title, color = HugoColors.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { now.progress() },
                        color = HugoColors.Accent, trackColor = Color(0xFF2A3148),
                        modifier = Modifier.fillMaxWidth(0.6f).height(2.dp),
                    )
                }
            }
            if (isFavorite) Icon(Icons.Default.Star, "Favorito", tint = Color(0xFFFACC15), modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PreviewPanel(
    vm: LiveTvViewModel,
    previewAllowed: Boolean,
    playerHolder: MutableState<ExoPlayer?>,
    onWatch: () -> Unit,
    previewFocus: FocusRequester,
    onBackToList: () -> Boolean,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val c = vm.focused
    var videoHeight by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<String?>(null) }
    // topo FIXO (prévia larga + cartão compacto); só a programação embaixo rola
    var backDown by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.background(HugoColors.Panel).onPreviewKeyEvent { ev ->
        // VOLTAR com foco na 3ª coluna: primeiro volta para a lista de canais (não sai da tela)
        if (ev.key.nativeKeyCode != AndroidKeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
        when (ev.type) {
            KeyEventType.KeyDown -> { backDown = onBackToList(); backDown }
            KeyEventType.KeyUp -> { val h = backDown; backDown = false; h }
            else -> false
        }
    }) {
    val layout = previewLayout(maxWidth.value, maxHeight.value)
    val short = layout.short
    val pad = layout.pad.dp
    Column(Modifier.fillMaxSize().padding(pad)) {
        // prévia: ocupa a largura da coluna; tocar (ou OK com foco nela) abre em tela cheia
        val previewSrc = remember { MutableInteractionSource() }
        val previewFocused by previewSrc.collectIsFocusedAsState()
        LaunchedEffect(previewFocused) { if (previewFocused) CrashLog.step("TV ao vivo: foco na prévia (vídeo=${previewAllowed})") }
        Box(
            Modifier.align(Alignment.CenterHorizontally).width(layout.boxWidth.dp).height(layout.boxHeight.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black)
                .border(2.dp, if (previewFocused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                .focusRequester(previewFocus)
                .onPreviewKeyEvent { ev ->
                    // ESQUERDA na prévia volta para a linha do canal
                    if (ev.type == KeyEventType.KeyDown && ev.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) onBackToList() else false
                }
                .clickable(interactionSource = previewSrc, indication = null, enabled = c != null) { onWatch() },
            contentAlignment = Alignment.Center,
        ) {
            if (previewAllowed && c != null) {
                // vídeo sempre 16:9; se a caixa for mais baixa, corta um pouco em cima/embaixo (sem esticar)
                Box(Modifier.requiredSize(layout.videoWidth.dp, layout.videoHeight.dp).focusProperties { canFocus = false }) {
                    LivePreview(c.url, playerHolder, onVideoHeight = { videoHeight = it }, onPlaying = { playing = it })
                }
                Row(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.VolumeOff, null, tint = HugoColors.TextMuted, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Prévia", color = HugoColors.TextMuted, fontSize = 10.sp)
                }
                if (previewFocused) {
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp).background(HugoColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("OK tela cheia", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (c != null) {
                ChannelLogo(c.logo, c.name, size = if (compact) 64.dp else 96.dp)
            } else {
                Icon(Icons.Default.LiveTv, null, tint = HugoColors.TextDim, modifier = Modifier.size(48.dp))
            }
        }
        LaunchedEffect(c?.url) { videoHeight = 0; playing = null }
        Spacer(Modifier.height(pad))
        if (c == null) {
            Text("Escolha um canal", color = HugoColors.TextMuted)
            return@Column
        }
        // cartão compacto: número, nome, selos e estrela numa linha
        val chips = qualityChips(c, videoHeight, playing)
        val fav = vm.favorites[c.url] == true
        Row(
            Modifier.fillMaxWidth().height(layout.cardHeight.dp).background(HugoColors.Card, RoundedCornerShape(10.dp)).padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${vm.focusedIndex + 1}", color = HugoColors.Accent, fontSize = if (short) 13.sp else 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(c.name, color = Color.White, fontSize = if (compact || short) 13.sp else 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            chips.take(3).forEach { Spacer(Modifier.width(4.dp)); Chip(it) }
            IconButton(onClick = { vm.toggleFavorite(c) }, modifier = Modifier.size(if (short) 30.dp else 36.dp)) {
                Icon(
                    if (fav) Icons.Default.Star else Icons.Default.StarBorder, if (fav) "Remover dos favoritos" else "Favoritar",
                    tint = if (fav) Color(0xFFFACC15) else HugoColors.TextMuted, modifier = Modifier.size(if (short) 18.dp else 20.dp),
                )
            }
        }
        Spacer(Modifier.height(pad))

        // programação: lista própria que rola sozinha (toque ou setas), sem mexer no topo
        val now = System.currentTimeMillis()
        val nn = vm.cachedEpg(c)
        val programs = remember(vm.guide, nn) { epgDisplayList(vm.guide, listOfNotNull(nn?.now, nn?.next), now) }
        if (programs.isEmpty()) {
            Text("Sem programação (EPG) para este canal.", color = HugoColors.TextDim, fontSize = 12.sp)
        } else {
            val epgState = rememberLazyListState()
            LaunchedEffect(c.url) { epgState.scrollToItem(0) }
            LazyColumn(
                state = epgState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // chave por posição: EPG de painel às vezes repete o mesmo horário (chave duplicada derrubava o app)
                itemsIndexed(programs, key = { i, p -> "$i:${p.start}" }) { _, p ->
                    EpgCard(p, live = p.isLive(now), compact = compact || short)
                }
            }
        }
    }
    }
}

@Composable
private fun EpgCard(p: EpgProgram, live: Boolean, compact: Boolean) {
    // focável: no controle as setas descem pela programação (a lista rola, o topo fica)
    FocusSurface(
        onClick = {},
        onFocused = { CrashLog.step("TV ao vivo: guia") },
        color = if (live) Color(0xFF1F2A4A) else HugoColors.Card,
        focusedColor = Color(0xFF2A2350),
        focusScale = 1.0f,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Text(
                        "AGORA", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(HugoColors.Pink, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text("${formatHm(p.start)} – ${formatHm(p.stop)}", color = if (live) Color(0xFFFACC15) else HugoColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(p.title, color = Color.White, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (live) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { p.progress() }, color = HugoColors.Accent, trackColor = Color(0xFF2A3148), modifier = Modifier.fillMaxWidth().height(3.dp))
            }
            p.desc?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = HugoColors.TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Chip(text: String, muted: Boolean = false) {
    Text(
        text, color = if (muted) HugoColors.TextMuted else Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 150.dp).border(1.dp, if (muted) Color(0xFF2A3148) else HugoColors.Accent, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Prévia muda do canal focado (TextureView: sem "buraco" de SurfaceView, nada de fantasma no Fire TV).
 * Começa após uma pausa no foco, para ao sair do app (ON_STOP) e volta no ON_START.
 */
@OptIn(UnstableApi::class)
@Composable
private fun LivePreview(url: String, holder: MutableState<ExoPlayer?>, onVideoHeight: (Int) -> Unit, onPlaying: (String?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember {
        HugoPlayerFactory.create(context, playerUserAgent(context), lowLatencyPreview = true, decoderFallback = true).apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            // prévia leve: no HLS pega a variante menor
            trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
        }
    }
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    // mesmas tentativas do player (.m3u8 <-> .ts, lembrando o formato que funcionou no servidor)
    val attempts = remember(url) { liveAttempts(url) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    val cur = attempts.getOrNull(attempt) ?: LiveAttempt(url, false)

    val playingCb by rememberUpdatedState(onPlaying)
    val heightCb by rememberUpdatedState(onVideoHeight)
    DisposableEffect(player) {
        holder.value = player
        onDispose {
            if (holder.value === player) holder.value = null
            player.release()
        }
    }
    // ouvinte refeito a cada canal: antes ficava preso às tentativas do 1º canal e o fallback não rodava nos outros
    DisposableEffect(player, attempts) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                // qualquer erro: tenta a outra variante (.ts <-> .m3u8) antes de mostrar "indisponível"
                if (attempt + 1 < attempts.size) { attempt++; return }
                failed = true; loading = false
            }
            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    failed = false
                    attempts.getOrNull(attempt)?.let { a -> LiveFormatMemory.remember(a.url); playingCb(playingFormat(a.url, a.forceHls)) }
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) { heightCb(videoSize.height) }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { foreground = false; player.stop(); player.clearMediaItems() }
                Lifecycle.Event.ON_START -> foreground = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(cur, foreground) {
        playingCb(null)
        CrashLog.step("TV ao vivo: prévia tocando")
        player.stop()
        player.clearMediaItems()
        failed = false
        if (!foreground) return@LaunchedEffect
        delay(if (attempt == 0) 700 else 200)
        loading = true
        player.setMediaItem(HugoPlayerFactory.mediaItem(cur.url, forceHls = cur.forceHls))
        player.prepare()
        player.playWhenReady = true
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            // a View do vídeo fica fora da navegação por foco (Fire TV / Android TV)
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    val tv = TextureView(ctx).apply { isFocusable = false; isFocusableInTouchMode = false }
                    addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    player.setVideoTextureView(tv)
                }
            },
            onRelease = { fl -> (fl.getChildAt(0) as? TextureView)?.let { runCatching { player.clearVideoTextureView(it) } } },
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        )
        when {
            failed -> Text("Prévia indisponível", color = HugoColors.TextMuted, fontSize = 12.sp, modifier = Modifier.background(Color.Black).padding(8.dp))
            loading -> CircularProgressIndicator(color = HugoColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        }
    }
}

/** Pede foco sem derrubar o app se o alvo ainda não estiver na tela (FocusRequester não ligado). */
private fun safeRequestFocus(r: FocusRequester): Boolean = runCatching { r.requestFocus() }.isSuccess
