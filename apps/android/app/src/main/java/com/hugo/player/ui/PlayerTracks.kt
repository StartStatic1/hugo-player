package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val language: String? = null)

private fun langName(code: String?): String? {
    if (code.isNullOrBlank()) return null
    val c = code.lowercase().substringBefore('-').substringBefore('_')
    return when (c) {
        "pt", "por" -> "Português"
        "en", "eng" -> "Inglês"
        "es", "spa" -> "Espanhol"
        "fr", "fra", "fre" -> "Francês"
        "de", "ger", "deu" -> "Alemão"
        "it", "ita" -> "Italiano"
        "ja", "jpn" -> "Japonês"
        "ko", "kor" -> "Coreano"
        "zh", "chi", "zho" -> "Chinês"
        "ru", "rus" -> "Russo"
        "ar", "ara" -> "Árabe"
        "und", "null", "unknown" -> null
        else -> code.uppercase()
    }
}

/** Rótulo legível; evita "Faixa 1" / "pt (pt)" / "en". */
private fun formatLabel(f: Format, index: Int, kind: String): String {
    val fromLabel = f.label?.trim()?.takeIf { it.isNotBlank() && !it.equals("und", true) }
    val fromLang = langName(f.language)
    return when {
        fromLabel != null && fromLang != null && !fromLabel.contains(fromLang, true) ->
            "$fromLang · $fromLabel"
        fromLang != null -> fromLang
        fromLabel != null -> fromLabel
        else -> "$kind ${index + 1}"
    }
}

/**
 * Muitos HLS de TV mandam uma "legenda" vazia/forçada sem texto útil.
 * Filtramos faixas de texto suspeitas para não poluir o menu.
 */
private fun isUsefulTextTrack(f: Format): Boolean {
    val lang = f.language?.lowercase()?.substringBefore('-')
    val label = f.label?.trim().orEmpty()
    // Sem idioma e sem label → quase sempre lixo de live
    if (lang.isNullOrBlank() && label.isBlank()) return false
    if (lang in setOf("und", "null", "unknown", "zxx")) return false
    // codecs de legenda vazia às vezes vêm sem sampleMimeType útil
    val mime = f.sampleMimeType?.lowercase().orEmpty()
    if (mime.contains("application/cea") || mime.contains("cea-608") || mime.contains("cea-708")) {
        // CEA em live BR quase nunca tem conteúdo real nas listas IPTV
        return false
    }
    return true
}

fun collectTrackOptions(tracks: Tracks): Pair<List<TrackOption>, List<TrackOption>> {
    val aud = mutableListOf<TrackOption>()
    val txt = mutableListOf<TrackOption>()
    for (gi in 0 until tracks.groups.size) {
        val g = tracks.groups[gi]
        for (ti in 0 until g.length) {
            if (!g.isTrackSupported(ti)) continue
            val f = g.getTrackFormat(ti)
            when (g.type) {
                C.TRACK_TYPE_AUDIO -> {
                    aud.add(TrackOption(gi, ti, formatLabel(f, ti, "Áudio"), f.language?.lowercase()))
                }
                C.TRACK_TYPE_TEXT -> {
                    if (!isUsefulTextTrack(f)) continue
                    txt.add(TrackOption(gi, ti, formatLabel(f, ti, "Legenda"), f.language?.lowercase()))
                }
            }
        }
    }
    return aud to txt
}

/** Idioma do áudio que está tocando (null = arquivo não informa). */
fun selectedAudioLanguage(tracks: Tracks): String? {
    for (g in tracks.groups) {
        if (g.type != C.TRACK_TYPE_AUDIO || !g.isSelected) continue
        for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i).language?.lowercase()?.takeIf { it.isNotBlank() && it != "und" }
    }
    return null
}

/** Legenda automática só onde ela ajuda: versão legendada ([L]/LEG) ou áudio que sabidamente não é português. */
fun wantsAutoSubtitle(title: String, audioLanguage: String?): Boolean {
    val legendado = Regex("""\[\s*L\s*]|\bLEG\b|legendad[oa]""", RegexOption.IGNORE_CASE).containsMatchIn(title)
    if (legendado) return true
    val lang = audioLanguage ?: return false
    return !isPortuguese(lang)
}

/** pt, pt-BR, por, pob… */
fun isPortuguese(language: String?): Boolean =
    language?.lowercase()?.substringBefore('-')?.substringBefore('_') in setOf("pt", "por", "pob")

/** Faixa de legenda em português que já vem no arquivo (para ligar ao trocar o áudio). */
fun portugueseTextTrack(text: List<TrackOption>): TrackOption? =
    text.firstOrNull { isPortuguese(it.language) } ?: text.firstOrNull { it.label.startsWith("Português") }

/** Só mostra o botão se houver algo útil (2+ áudios ou legenda de verdade). */
fun hasUsefulTrackMenu(audio: List<TrackOption>, text: List<TrackOption>): Boolean =
    audio.size > 1 || text.isNotEmpty()

fun applyAudioTrack(player: Player, groupIndex: Int, trackIndex: Int) {
    val groups = player.currentTracks.groups
    if (groupIndex !in groups.indices) return
    val group = groups[groupIndex]
    if (trackIndex !in 0 until group.length) return
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        .apply {
            // idioma preferido também: se o vídeo for recarregado (ex.: legenda da internet), o áudio escolhido continua
            group.getTrackFormat(trackIndex).language?.takeIf { it.isNotBlank() && it != "und" }?.let { setPreferredAudioLanguage(it) }
        }
        .build()
}

fun applyTextTrack(player: Player, groupIndex: Int?, trackIndex: Int?) {
    val builder = player.trackSelectionParameters
        .buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)

    if (groupIndex == null || trackIndex == null) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        val groups = player.currentTracks.groups
        if (groupIndex !in groups.indices) return
        val group = groups[groupIndex]
        if (trackIndex !in 0 until group.length) return
        val format = group.getTrackFormat(trackIndex)
        builder
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        format.language?.takeIf { it.isNotBlank() }?.let { builder.setPreferredTextLanguage(it) }
    }
    player.trackSelectionParameters = builder.build()
}

@Composable
private fun TrackRow(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        text = label,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .then(
                if (focused) Modifier
                    .border(2.dp, Color(0xFF8B5CF6), RoundedCornerShape(10.dp))
                    .background(Color(0xFF8B5CF6).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                else Modifier.background(Color(0xFF1E293B), RoundedCornerShape(10.dp))
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/** Legenda da internet ainda não aplicada (aparece embaixo das faixas do arquivo). */
data class ExternalRow(val label: String, val onClick: () -> Unit)

@Composable
fun TrackPickerDialog(
    audio: List<TrackOption>,
    text: List<TrackOption>,
    onPickAudio: (TrackOption) -> Unit,
    onPickText: (TrackOption?) -> Unit,
    onDismiss: () -> Unit,
    external: List<ExternalRow> = emptyList(),
    /** null = sem busca (ex.: ao vivo) */
    onSearch: (() -> Unit)? = null,
    searchStatus: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        titleContentColor = Color.White,
        title = {
            Text("Áudio e legendas", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("ÁUDIO", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (audio.size > 1) {
                    audio.forEach { opt ->
                        TrackRow(opt.label) { onPickAudio(opt) }
                        Spacer(Modifier.height(6.dp))
                    }
                } else {
                    Text("Só uma faixa de áudio" + (audio.firstOrNull()?.let { " (${it.label})" } ?: ""), color = Color(0xFFCBD5E1), fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))
                Text("LEGENDA", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (text.isEmpty() && external.isEmpty()) {
                    Text("Este conteúdo não tem legendas", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                }
                if (text.isNotEmpty() || external.isNotEmpty()) {
                    TrackRow("Desligada") { onPickText(null) }
                    Spacer(Modifier.height(6.dp))
                    text.forEach { opt ->
                        TrackRow(opt.label) { onPickText(opt) }
                        Spacer(Modifier.height(6.dp))
                    }
                    external.forEach { row ->
                        TrackRow(row.label, row.onClick)
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (onSearch != null) {
                    TrackRow(if (external.isEmpty()) "Buscar legenda na internet" else "Buscar outra legenda", onSearch)
                    Spacer(Modifier.height(4.dp))
                }
                searchStatus?.let { Text(it, color = Color(0xFFFBBF24), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = Color(0xFFA78BFA))
            }
        },
    )
}
