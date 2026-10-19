package com.hugo.player.ui

import androidx.compose.foundation.background
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
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String)

fun collectTrackOptions(tracks: Tracks): Pair<List<TrackOption>, List<TrackOption>> {
    val aud = mutableListOf<TrackOption>()
    val txt = mutableListOf<TrackOption>()
    for (gi in 0 until tracks.groups.size) {
        val g = tracks.groups[gi]
        // Só faixas selecionáveis (não forced-only se o player marcar)
        for (ti in 0 until g.length) {
            if (!g.isTrackSupported(ti)) continue
            val f = g.getTrackFormat(ti)
            val base = f.label?.takeIf { it.isNotBlank() }
                ?: f.language?.takeIf { it.isNotBlank() }
                ?: "Faixa ${ti + 1}"
            val lang = f.language?.takeIf { it.isNotBlank() && it != base }?.let { " ($it)" } ?: ""
            val label = base + lang
            when (g.type) {
                C.TRACK_TYPE_AUDIO -> aud.add(TrackOption(gi, ti, label))
                C.TRACK_TYPE_TEXT -> txt.add(TrackOption(gi, ti, label))
            }
        }
    }
    return aud to txt
}

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
        // Desliga legendas
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
        // Ajuda em alguns HLS/live a priorizar o idioma
        format.language?.takeIf { it.isNotBlank() }?.let { lang ->
            builder.setPreferredTextLanguage(lang)
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(
                if (focused) Color(0xFF8B5CF6).copy(alpha = 0.45f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
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
        title = { Text("Áudio e legendas") },
        text = {
            Column {
                if (audio.isNotEmpty()) {
                    Text("Áudio", fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    audio.forEach { opt ->
                        TrackRow(opt.label) { onPickAudio(opt) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (text.isNotEmpty()) {
                    Text("Legendas", fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    TrackRow("Desligadas") { onPickText(null) }
                    text.forEach { opt ->
                        TrackRow(opt.label) { onPickText(opt) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        },
    )
}
