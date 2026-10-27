package com.hugo.player.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.data.AppUpdate
import com.hugo.player.data.UpdateStep
import com.hugo.player.data.updateStepText
import com.hugo.player.ui.components.HugoColors

/** Está baixando/abrindo (botões não disparam outro download). */
internal val UpdateStep.busy: Boolean
    get() = this is UpdateStep.Checking || this is UpdateStep.Downloading || this is UpdateStep.Opening

/** Texto do botão principal conforme a etapa. */
internal fun updateButtonText(step: UpdateStep, idleText: String): String = when (step) {
    UpdateStep.Checking -> "Verificando…"
    is UpdateStep.Downloading -> step.percent?.let { "Baixando… $it%" } ?: "Baixando…"
    UpdateStep.Opening -> "Abrindo instalador…"
    UpdateStep.Opened -> "Abrir instalador de novo"
    is UpdateStep.Failed -> "Tentar de novo"
    UpdateStep.Idle -> idleText
}

/**
 * Baixa e abre o instalador, publicando cada etapa em [setStep].
 * [resolveUrl] != null → pergunta ao servidor o link mais novo antes (mostra "Verificando…").
 */
internal suspend fun runAppUpdate(
    context: Context,
    apkUrl: String?,
    resolveUrl: Boolean,
    setStep: (UpdateStep) -> Unit,
) {
    val url = if (resolveUrl || apkUrl.isNullOrBlank()) {
        setStep(UpdateStep.Checking)
        AppUpdate.latestApkUrl(context)
    } else apkUrl
    setStep(UpdateStep.Downloading(0, -1))
    val err = AppUpdate.downloadAndInstall(context, url) { setStep(it) }
    if (err != null) setStep(UpdateStep.Failed(err))
}

/** Barra + texto: % quando o tamanho é conhecido; barra correndo quando não é. */
@Composable
internal fun UpdateProgress(step: UpdateStep, modifier: Modifier = Modifier, textColor: Color = HugoColors.TextMuted) {
    if (step == UpdateStep.Idle) return
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val pct = (step as? UpdateStep.Downloading)?.percent
        when {
            step is UpdateStep.Downloading && pct != null ->
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = HugoColors.Accent)
            step.busy ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp), color = HugoColors.Accent)
            else -> {}
        }
        Spacer(Modifier.height(8.dp))
        Text(
            updateStepText(step),
            color = if (step is UpdateStep.Failed) Color(0xFFF87171) else textColor,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}
