package com.hugo.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hugo.player.data.AppSettings
import com.hugo.player.data.CrashLog
import com.hugo.player.ui.AppNavigation
import com.hugo.player.ui.CrashNotice
import com.hugo.player.ui.LiveTvCache

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this, BuildConfig.VERSION_NAME)
        AppSettings.init(this)
        LiveTvCache.load(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF07080F))) {
                Surface(Modifier.fillMaxSize()) {
                    AppNavigation()
                    CrashNotice()
                }
            }
        }
    }

    override fun onResume() { super.onResume(); CrashLog.setForeground(true) }
    override fun onPause() { CrashLog.setForeground(false); super.onPause() }
}