package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SeriesScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("S\u00e9ries", color = Color.White, fontSize = 28.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "As s\u00e9ries ser\u00e3o carregadas da playlist selecionada.\nCapas e informa\u00e7\u00f5es v\u00eam da lista do provedor.",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))) {
            Text("Voltar")
        }
    }
}