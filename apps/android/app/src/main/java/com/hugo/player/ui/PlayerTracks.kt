package com.hugo.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        for (ti in 0 until g.length) {
            if (!g.isTrackSupported(ti)) continue
            val f = g.getTrackFormat(ti)
            val base = f.label ?: f.language ?: "Faixa ${ti + 1}"
            val lang = f.language?.let { " ($it)" } ?: ""
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
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
        .build()
}

fun applyTextTrack(player: Player, groupIndex: Int?, trackIndex: Int?) {
    val builder = player.trackSelectionParameters.buildUpon()
    if (groupIndex == null || trackIndex == null) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        val groups = player.currentTracks.groups
        if (groupIndex !in groups.indices) return
        val group = groups[groupIndex]
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
    }
    player.trackSelectionParameters = builder.build()
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
                    Spacer(Modifier.height(6.dp))
                    audio.forEach { opt ->
                        TextButton(onClick = { onPickAudio(opt) }) { Text(opt.label, color = Color.White) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (text.isNotEmpty()) {
                    Text("Legendas", fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { onPickText(null) }) { Text("Desligadas", color = Color.White) }
                    text.forEach { opt ->
                        TextButton(onClick = { onPickText(opt) }) { Text(opt.label, color = Color.White) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        },
    )
}
