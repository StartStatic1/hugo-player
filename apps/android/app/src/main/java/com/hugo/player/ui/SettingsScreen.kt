package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Configura\u00e7\u00f5es", color = Color.White, fontSize = 28.sp)
        Spacer(Modifier.height(24.dp))
        Text("Device ID e Key ficam na tela de ativa\u00e7\u00e3o.", color = Color(0xFF94A3B8))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))) {
            Text("Voltar")
        }
    }
}