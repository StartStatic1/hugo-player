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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class Movie(val id: Int, val title: String, val poster: String?)

@Composable
fun MoviesScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var movies by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        movies = searchMovies("")
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar filme") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            loading = true
            // simple search trigger
        }) { Text("Buscar") }

        Spacer(Modifier.height(16.dp))

        if (loading) {
            CircularProgressIndicator()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(movies) { m ->
                    Card {
                        Column {
                            AsyncImage(
                                model = m.poster,
                                contentDescription = m.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.67f),
                                contentScale = ContentScale.Crop
                            )
                            Text(m.title, maxLines = 2, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack) { Text("Voltar") }
    }
}

suspend fun searchMovies(q: String): List<Movie> = withContext(Dispatchers.IO) {
    try {
        val endpoint = if (q.isBlank()) {
            "http://65.21.48.50:8080/tmdb/trending/movie/week"
        } else {
            "http://65.21.48.50:8080/tmdb/search/movie?query=${java.net.URLEncoder.encode(q, "UTF-8")}"
        }
        val text = URL(endpoint).readText()
        val json = JSONObject(text)
        val results = json.optJSONArray("results") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until results.length().coerceAtMost(40)) {
                val item = results.getJSONObject(i)
                val path = item.optString("poster_path")
                val poster = if (path.isNotEmpty()) "https://image.tmdb.org/t/p/w342$path" else null
                add(Movie(item.getInt("id"), item.getString("title"), poster))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}