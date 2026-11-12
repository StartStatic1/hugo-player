package com.hugo.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
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
import com.hugo.player.vpn.VpnManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** diálogo do sistema "Permitir conexão VPN" (uma vez só; depois o Android lembra) */
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        VpnManager.onConsentResult(it.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnManager.init(this)
        if (VpnManager.isEnabled) lifecycleScope.launch {
            VpnManager.consentRequest.collect { intent ->
                if (intent != null) runCatching { vpnConsent.launch(intent) }
                    .onFailure { VpnManager.onConsentResult(false) }
            }
        }
        CrashLog.install(this, BuildConfig.VERSION_NAME)
        AppSettings.init(this)
        com.hugo.player.ui.I18n.init()
        com.hugo.player.net.SecureDns.mode = AppSettings.secureDns
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