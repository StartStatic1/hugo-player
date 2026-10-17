package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ActivationScreen(onActivated: (String, String) -> Unit) {
    val deviceId = "AA:BB:CC:DD:EE:FF"
    val deviceKey = "ABC12345"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(40.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Hugo Player", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Ative seu dispositivo no site", color = Color(0xFF94A3B8))
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { onActivated(deviceId, deviceKey) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Text("Já ativei no site", fontSize = 16.sp)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Device ID", color = Color(0xFF94A3B8), fontSize = 14.sp)
            Text(deviceId, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            Text("Device Key", color = Color(0xFF94A3B8), fontSize = 14.sp)
            Text(deviceKey, color = Color(0xFF8B5CF6), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
        }
    }
}