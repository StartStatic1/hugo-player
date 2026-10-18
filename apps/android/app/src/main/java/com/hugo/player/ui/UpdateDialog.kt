package com.hugo.player.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.hugo.player.data.AppUpdate
import com.hugo.player.data.UpdateInfo
import kotlinx.coroutines.launch

/**
 * Checa update no arranque (qualquer tela) e mostra popup se o servidor
 * tiver versionCode maior que o APK instalado.
 */
@Composable
fun UpdateCheckerHost() {
    val context = LocalContext.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        info = AppUpdate.check()
    }

    val current = info ?: return
    AlertDialog(
        onDismissRequest = { if (!downloading) info = null },
        title = {
            Text(
                if (current.versionName.isNotBlank()) {
                    "Atualização ${current.versionName}"
                } else {
                    "Atualização disponível"
                },
            )
        },
        text = {
            Text(
                buildString {
                    append(current.notes.ifBlank { "Nova versão disponível." })
                    if (error != null) {
                        append("\n\n")
                        append(error)
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    downloading = true
                    error = null
                    scope.launch {
                        val err = AppUpdate.downloadAndInstall(context, current.apkUrl)
                        downloading = false
                        if (err != null) {
                            error = err
                        } else {
                            info = null
                        }
                    }
                },
            ) {
                Text(if (downloading) "Baixando..." else "Baixar e instalar")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !downloading,
                onClick = { info = null },
            ) { Text("Depois") }
        },
    )
}
