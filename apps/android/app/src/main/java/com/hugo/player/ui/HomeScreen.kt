package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(playlistName: String, onNavigate: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF07080F)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Hugo Player", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(playlistName, color = Color(0xFF8B5CF6), fontSize = 16.sp)
        Spacer(Modifier.height(40.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HomeTile("TV ao Vivo", Color(0xFF8B5CF6), Modifier.weight(1f)) { onNavigate("live") }
            HomeTile("Filmes", Color(0xFFEC4899), Modifier.weight(1f)) { onNavigate("movies") }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HomeTile("S\u00e9ries", Color(0xFF06B6D4), Modifier.weight(1f)) { onNavigate("series") }
            HomeTile("Configura\u00e7\u00f5es", Color(0xFF64748B), Modifier.weight(1f)) { onNavigate("settings") }
        }
    }
}

@Composable
private fun HomeTile(title: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151827)),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.height(140.dp)
    ) {
        Box(Modifier.fillMaxSize().background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}