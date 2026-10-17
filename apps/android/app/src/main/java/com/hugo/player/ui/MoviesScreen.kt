package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hugo.player.data.TmdbClient
import kotlinx.coroutines.launch

@Composable
fun MoviesScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("Busque um filme para ver metadados do TMDB") }
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
                    result = TmdbClient.searchMovie(query) ?: "Chave TMDB não configurada ou erro"
                }
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Buscar no TMDB")
        }

        Spacer(Modifier.height(16.dp))
        Text(result, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Voltar") }
    }
}