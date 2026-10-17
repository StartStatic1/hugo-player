package com.hugo.player

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.hugo.player.ui.AppNavigation
import com.hugo.player.ui.ActivationScreen

private val HugoColors = darkColorScheme(
    primary = Color(0xFF7C5CFF),
    secondary = Color(0xFF22D3EE),
    background = Color(0xFF0B0D17),
    surface = Color(0xFF151827),
    surfaceVariant = Color(0xFF1E2236),
    onPrimary = Color.White,
    onBackground = Color(0xFFE6E8F2),
    onSurface = Color(0xFFE6E8F2),
    onSurfaceVariant = Color(0xFF9CA3C4)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val identity = DeviceIdentity.get(applicationContext)
        val isTv = packageManager.hasSystemFeature("android.software.leanback") ||
            (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature("android.software.leanback_only"))

        setContent {
            MaterialTheme(colorScheme = HugoColors) {
                var activated by remember { mutableStateOf(false) }

                if (!activated) {
                    ActivationScreen(
                        identity = identity,
                        isTv = isTv,
                        onActivated = { activated = true }
                    )
                } else {
                    AppNavigation()
                }
            }
        }
    }
}