package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

@Composable
fun MoviesScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("Busque um filme (via servidor Hugo)") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Filmes", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar filme") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                scope.launch {
                    result = searchMovies(query)
                }
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Buscar")
        }

        Spacer(Modifier.height(16.dp))
        Text(result, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Voltar") }
    }
}

suspend fun searchMovies(query: String): String = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext "Digite um nome"
    return@withContext try {
        val url = "http://65.21.48.50:8080/tmdb/search?q=${query.encode()}&type=movie"
        val text = URL(url).readText()
        val json = JSONObject(text)
        val results = json.optJSONArray("results")
        if (results == null || results.length() == 0) {
            "Nenhum resultado"
        } else {
            buildString {
                for (i in 0 until minOf(results.length(), 5)) {
                    val movie = results.getJSONObject(i)
                    appendLine("${movie.optString("title")} (${movie.optString("release_date").take(4)})")
                    appendLine("Nota: ${movie.optDouble("vote_average")}")
                    appendLine()
                }
            }
        }
    } catch (e: Exception) {
        "Erro: ${e.message}"
    }
}

private fun String.encode() = java.net.URLEncoder.encode(this, "UTF-8")