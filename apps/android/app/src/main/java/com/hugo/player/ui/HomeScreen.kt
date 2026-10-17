package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Hugo Player", style = MaterialTheme.typography.headlineLarge)
        Text("Escolha uma seção", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        Button(onClick = { onNavigate("live") }, modifier = Modifier.fillMaxWidth()) {
            Text("Live TV")
        }
        Button(onClick = { onNavigate("movies") }, modifier = Modifier.fillMaxWidth()) {
            Text("Filmes")
        }
        Button(onClick = { onNavigate("series") }, modifier = Modifier.fillMaxWidth()) {
            Text("Séries")
        }
        Button(onClick = { onNavigate("settings") }, modifier = Modifier.fillMaxWidth()) {
            Text("Configurações")
        }
    }
}