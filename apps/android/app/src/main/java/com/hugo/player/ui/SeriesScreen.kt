package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class SeriesItem(val title: String, val year: String, val poster: String?, val rating: Double)

@Composable
fun SeriesScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<SeriesItem>>(emptyList()) }
    var status by remember { mutableStateOf("Busque uma série") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Séries", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar série") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                scope.launch {
                    status = "Buscando..."
                    items = searchSeries(query)
                    status = if (items.isEmpty()) "Nenhum resultado" else "${items.size} resultados"
                }
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Buscar")
        }

        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                Card {
                    Column {
                        AsyncImage(
                            model = item.poster?.let { "https://image.tmdb.org/t/p/w500$it" },
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                            contentScale = ContentScale.Crop
                        )
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                        Text("${item.year} • ${item.rating}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Voltar") }
    }
}

suspend fun searchSeries(query: String): List<SeriesItem> = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext emptyList()
    return@withContext try {
        val url = "http://65.21.48.50:8080/tmdb/search?q=${query.encode()}&type=tv"
        val text = URL(url).readText()
        val json = JSONObject(text)
        val results = json.optJSONArray("results") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until results.length()) {
                val s = results.getJSONObject(i)
                add(
                    SeriesItem(
                        title = s.optString("name"),
                        year = s.optString("first_air_date").take(4),
                        poster = s.optString("poster_path").takeIf { it.isNotBlank() },
                        rating = s.optDouble("vote_average")
                    )
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun String.encode() = java.net.URLEncoder.encode(this, "UTF-8")