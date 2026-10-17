package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hugo.player.DeviceIdentity

@Composable
fun ActivationScreen(
    identity: DeviceIdentity.Identity,
    isTv: Boolean,
    onActivated: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Hugo Player", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        Text("Device ID: ${identity.deviceId}")
        Text("Device Key: ${identity.deviceKey}")
        Spacer(Modifier.height(32.dp))
        Button(onClick = onActivated) {
            Text("Continuar para o app")
        }
    }
}