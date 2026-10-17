package com.hugo.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HugoTheme {
                DeviceActivationScreen()
            }
        }
    }
}

@Composable
fun DeviceActivationScreen() {
    val deviceId = remember { generateDeviceId() }
    val deviceKey = remember { generateDeviceKey() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Hugo Player",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text("Device ID", style = MaterialTheme.typography.titleMedium)
        Text(deviceId, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Device Key", style = MaterialTheme.typography.titleMedium)
        Text(deviceKey, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Acesse o site para ativar e adicionar sua playlist",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

fun generateDeviceId(): String {
    val uuid = UUID.randomUUID().toString().replace("-", "").take(12)
    return uuid.chunked(2).joinToString(":").uppercase()
}

fun generateDeviceKey(): String {
    return UUID.randomUUID().toString().take(8).uppercase()
}

@Composable
fun HugoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content
    )
}