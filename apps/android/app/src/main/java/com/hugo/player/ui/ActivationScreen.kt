package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ActivationScreen(onActivated: (String, String) -> Unit) {
    val deviceId = "AA:BB:CC:DD:EE:FF"
    val deviceKey = "ABC12345"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Ativação", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(Modifier.height(24.dp))

        Text("Device ID", color = Color(0xFF94A3B8), fontSize = 14.sp)
        Text(deviceId, color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(vertical = 4.dp))

        Spacer(Modifier.height(16.dp))
        Text("Device Key", color = Color(0xFF94A3B8), fontSize = 14.sp)
        Text(deviceKey, color = Color(0xFF8B5CF6), fontSize = 24.sp, modifier = Modifier.padding(vertical = 4.dp))

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { onActivated(deviceId, deviceKey) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            Text("Já ativei no site", fontSize = 16.sp)
        }
    }
}