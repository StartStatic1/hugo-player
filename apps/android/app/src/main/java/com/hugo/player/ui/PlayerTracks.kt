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

data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String)

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
                    aud.add(TrackOption(gi, ti, formatLabel(f, ti, "Áudio")))
                }
                C.TRACK_TYPE_TEXT -> {
                    if (!isUsefulTextTrack(f)) continue
                    txt.add(TrackOption(gi, ti, formatLabel(f, ti, "Legenda")))
                }
            }
        }
    }
    return aud to txt
}

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

@Composable
fun TrackPickerDialog(
    audio: List<TrackOption>,
    text: List<TrackOption>,
    onPickAudio: (TrackOption) -> Unit,
    onPickText: (TrackOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        titleContentColor = Color.White,
        title = {
            Text("Áudio e legendas", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
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
                if (text.isEmpty()) Text("Este conteúdo não tem legendas", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                if (text.isNotEmpty()) {
                    TrackRow("Desligada") { onPickText(null) }
                    Spacer(Modifier.height(6.dp))
                    text.forEach { opt ->
                        TrackRow(opt.label) { onPickText(opt) }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = Color(0xFFA78BFA))
            }
        },
    )
}
