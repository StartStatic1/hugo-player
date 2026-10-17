package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ActivationScreen(onActivated: (String, String) -> Unit) {
    // In a real app these would come from Keystore / DataStore
    var deviceId by remember { mutableStateOf("AA:BB:CC:DD:EE:FF") }
    var deviceKey by remember { mutableStateOf("ABC12345") }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Ativação", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("Device ID: $deviceId")
        Text("Device Key: $deviceKey")
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onActivated(deviceId, deviceKey) }) {
            Text("Já ativei no site")
        }
    }
}