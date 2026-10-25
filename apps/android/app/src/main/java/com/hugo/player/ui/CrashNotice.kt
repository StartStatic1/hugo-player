package com.hugo.player.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.data.CrashLog
import com.hugo.player.ui.components.HugoColors

/** Na abertura seguinte a um fechamento inesperado, mostra o registro (sem precisar achar Configurações). */
@Composable
fun CrashNotice() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(CrashLog.unseen(context)) }
    val log = text ?: return
    AlertDialog(
        onDismissRequest = { CrashLog.markSeen(context); text = null },
        containerColor = HugoColors.Panel,
        title = { Text("O app fechou da última vez", color = Color.White) },
        text = {
            Text(
                "Tire uma foto desta tela e envie ao suporte (também fica em Configurações > Diagnóstico).\n\n" + log.lineSequence().take(30).joinToString("\n"),
                color = HugoColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = { CrashLog.markSeen(context); text = null }) { Text("OK") } },
    )
}
