package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.data.Catalog
import com.hugo.player.data.CatalogRepository
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import kotlinx.coroutines.launch

enum class BrowseTab(val route: String, val label: String, val color: Color) {
    LIVE("live", "TV ao Vivo", HugoColors.Accent),
    MOVIES("movies", "Filmes", HugoColors.Pink),
    SERIES("series", "Séries", HugoColors.Cyan);

    companion object {
        fun from(route: String?) = entries.firstOrNull { it.route == route } ?: LIVE
    }
}

/**
 * Tela principal de conteúdo com abas TV ao Vivo / Filmes / Séries
 * (estilo IBO / Shamel). Usa o catálogo separado pelo backend.
 */
@Composable
fun BrowseScreen(
    playlistId: String,
    deviceId: String,
    deviceKey: String,
    initialTab: BrowseTab,
    onPlay: (PlaybackRequest) -> Unit,
    onBack: () -> Unit,
) {
    // Garante configurações + cache em disco mesmo se a MainActivity não inicializar
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    remember { CatalogRepository.init(appContext); true }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var catalog by remember { mutableStateOf(CatalogRepository.cached(playlistId)) }
    var loading by remember { mutableStateOf(catalog == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load(force: Boolean) {
        loading = true
        error = null
        try {
            catalog = CatalogRepository.load(playlistId, deviceId, deviceKey, force)
        } catch (e: Exception) {
            error = e.message ?: "Erro ao carregar a lista"
        }
        loading = false
    }

    LaunchedEffect(playlistId) { if (catalog == null) load(false) }

    Column(Modifier.fillMaxSize().background(HugoColors.Background)) {
        // Barra superior: voltar, abas, busca, atualizar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Hugo Player", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(CatalogRepository.playlistName ?: LiveTvCache.playlistName ?: "Playlist", color = HugoColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                if (catalog != null) {
                    val (label, color) = cacheLabel()
                    Text(label, color = color, fontSize = 10.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.width(20.dp))
            BrowseTab.entries.forEach { t ->
                val count = catalog?.let { countFor(it, t) }
                FocusSurface(
                    onClick = { tab = t },
                    selected = tab == t,
                    selectedColor = t.color,
                    color = Color.Transparent,
                    focusedColor = t.color.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(end = 8.dp).height(38.dp)
                ) {
                    Row(Modifier.fillMaxHeight().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (count != null) {
                            Spacer(Modifier.width(6.dp))
                            Text("$count", color = Color(0xCCFFFFFF), fontSize = 11.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar em ${tab.label}", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = tab.color,
                        cursorColor = tab.color,
                    ),
                    modifier = Modifier.width(260.dp).height(52.dp)
                )
            }
            IconButton(onClick = { showSearch = !showSearch; if (!showSearch) query = "" }) {
                Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color.White)
            }
            IconButton(onClick = { scope.launch { load(true) } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Atualizar lista", tint = Color.White)
            }
        }

        val c = catalog
        when {
            loading && c == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = tab.color)
                    Spacer(Modifier.height(12.dp))
                    Text("Carregando lista…", color = HugoColors.TextMuted)
                }
            }
            error != null && c == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "Erro", color = Color(0xFFF87171), fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { scope.launch { load(true) } }, colors = ButtonDefaults.buttonColors(containerColor = tab.color)) {
                        Text("Tentar novamente")
                    }
                }
            }
            c != null -> {
                if (loading) LinearProgressIndicator(color = tab.color, modifier = Modifier.fillMaxWidth().height(2.dp))
                error?.let { Text(it, color = Color(0xFFF87171), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
                when (tab) {
                    BrowseTab.LIVE -> LiveContent(c, query, onPlay)
                    BrowseTab.MOVIES -> MoviesContent(c, query, onPlay)
                    BrowseTab.SERIES -> SeriesContent(c, query, onPlay)
                }
            }
        }
    }
}

private fun cacheLabel(): Pair<String, Color> {
    val mins = ((System.currentTimeMillis() - CatalogRepository.savedAt) / 60_000).coerceAtLeast(0)
    val ago = when {
        mins < 1 -> "agora"
        mins < 60 -> "há $mins min"
        mins < 48 * 60 -> "há ${mins / 60} h"
        else -> "há ${mins / 1440} dias"
    }
    return when (CatalogRepository.origin) {
        CatalogRepository.Origin.STALE_DISK -> "Offline · lista salva $ago" to Color(0xFFFBBF24)
        CatalogRepository.Origin.DISK, CatalogRepository.Origin.MEMORY -> "Atualizada $ago" to HugoColors.TextDim
        CatalogRepository.Origin.NETWORK -> "Atualizada $ago" to HugoColors.TextDim
    }
}

private fun countFor(c: Catalog, t: BrowseTab) = when (t) {
    BrowseTab.LIVE -> c.live.items.size
    BrowseTab.MOVIES -> c.movies.items.size
    BrowseTab.SERIES -> c.series.items.size
}

const val ALL_CATEGORIES = "__all__"

/** Filtro comum: categoria + busca (sem acentos, sem caixa). */
fun matchesQuery(name: String, query: String): Boolean {
    if (query.isBlank()) return true
    return normalizeText(name).contains(normalizeText(query.trim()))
}

fun normalizeText(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
