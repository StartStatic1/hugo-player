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
        com.hugo.player.ui.I18n.init()
        com.hugo.player.net.SecureDns.mode = AppSettings.secureDns
        LiveTvCache.load(this)
        if (BuildConfig.PLAY_STORE) hideSystemBars()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF07080F))) {
                Surface(Modifier.fillMaxSize()) {
                    AppNavigation()
                    CrashNotice()
                }
            }
        }
    }

    // build play (targetSdk 36 = edge-to-edge obrigatório): tela cheia imersiva; barras voltam com um deslize
    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && BuildConfig.PLAY_STORE) hideSystemBars()
    }

    override fun onResume() { super.onResume(); CrashLog.setForeground(true) }
    override fun onPause() { CrashLog.setForeground(false); super.onPause() }
}