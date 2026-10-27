package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    /** idioma (da tag do arquivo ou deduzido pelo nome da faixa: "Inglês", "English", "Eng"…) */
    val language: String? = null,
    /** está tocando/ligada agora (marca ✓ no menu) */
    val selected: Boolean = false,
    /** legenda "forçada" (só placas/falas estrangeiras): não serve como legenda completa */
    val forced: Boolean = false,
)

/**
 * Idioma de uma faixa. Usa a tag do arquivo; sem tag (ou "und"), deduz pelo nome da faixa.
 * Devolve código curto ("pt", "en", "es"…) ou null quando não dá para saber.
 */
fun inferLanguage(code: String?, label: String?): String? {
    val c = code?.trim()?.lowercase()?.takeIf { it.isNotBlank() && it !in setOf("und", "null", "unknown", "zxx", "mul") }
    if (c != null) return c
    val l = " " + (label ?: "").lowercase().replace(Regex("[^a-zà-ú0-9]+"), " ") + " "
    return when {
        Regex(" (portugu[eê]s|portuguese|pt|ptbr|pt br|por|brasil|brazil|brasileiro|dublado|dub) ").containsMatchIn(l) -> "pt"
        Regex(" (ingl[eê]s|english|eng|en|ing) ").containsMatchIn(l) -> "en"
        Regex(" (espanhol|espa[nñ]ol|spanish|castellano|latino|spa|es) ").containsMatchIn(l) -> "es"
        Regex(" (franc[eê]s|fran[cç]ais|french|fre|fra) ").containsMatchIn(l) -> "fr"
        Regex(" (italiano|italian|ita) ").containsMatchIn(l) -> "it"
        Regex(" (alem[aã]o|deutsch|german|ger|deu) ").containsMatchIn(l) -> "de"
        Regex(" (japon[eê]s|japanese|jpn) ").containsMatchIn(l) -> "ja"
        Regex(" (coreano|korean|kor) ").containsMatchIn(l) -> "ko"
        else -> null
    }
}

private val forcedLabel = Regex("""forc|forç|forced""", RegexOption.IGNORE_CASE)
private fun isForced(f: Format): Boolean =
    (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 || forcedLabel.containsMatchIn(f.label.orEmpty())

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
                    val lang = inferLanguage(f.language, f.label)
                    aud.add(TrackOption(gi, ti, formatLabel(f, ti, "Áudio"), lang, selected = g.isTrackSelected(ti)))
                }
                C.TRACK_TYPE_TEXT -> {
                    if (!isUsefulTextTrack(f)) continue
                    val forced = isForced(f)
                    val label = formatLabel(f, ti, "Legenda") + if (forced && !forcedLabel.containsMatchIn(f.label.orEmpty())) " (forçada)" else ""
                    txt.add(TrackOption(gi, ti, label, inferLanguage(f.language, f.label), selected = g.isTrackSelected(ti), forced = forced))
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
        for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i).let { inferLanguage(it.language, it.label) }
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
    text.filterNot { it.forced }.let { full -> full.firstOrNull { isPortuguese(it.language) } ?: full.firstOrNull { it.label.startsWith("Português") } }

/**
 * Áudio escolhido é estrangeiro? Com idioma conhecido: não-português. Sem idioma: estrangeiro só se
 * outra faixa do arquivo for português (dual áudio "Português + faixa sem nome").
 */
fun isForeignAudio(picked: TrackOption, all: List<TrackOption>): Boolean {
    val lang = picked.language
    if (lang != null) return !isPortuguese(lang)
    return all.any { it !== picked && isPortuguese(it.language) }
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
private fun TrackRow(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .then(
                if (focused) Modifier
                    .border(2.dp, Color(0xFF8B5CF6), RoundedCornerShape(10.dp))
                    .background(Color(0xFF8B5CF6).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                else Modifier.background(if (selected) Color(0xFF312E81) else Color(0xFF1E293B), RoundedCornerShape(10.dp))
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) Text("✓", color = Color(0xFFA78BFA), fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Botão pequeno da sincronia (− / +). */
@Composable
private fun SyncButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .then(
                if (focused) Modifier.border(2.dp, Color(0xFF8B5CF6), RoundedCornerShape(10.dp)).background(Color(0xFF8B5CF6).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                else Modifier.background(Color(0xFF1E293B), RoundedCornerShape(10.dp))
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

/** Legenda da internet (aparece embaixo das faixas do arquivo). */
data class ExternalRow(val label: String, val selected: Boolean = false, val onClick: () -> Unit)

/** Ajuste de sincronia (só para a legenda da internet). */
data class SubtitleSync(val label: String, val onShift: (Long) -> Unit, val onReset: () -> Unit)

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
    /** legenda desligada agora (marca ✓ em "Desligada") */
    textOff: Boolean = false,
    /** null = sem legenda da internet ligada (não dá para ajustar) */
    sync: SubtitleSync? = null,
) {
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        titleContentColor = Color.White,
        title = {
            Text("Áudio e legendas", fontWeight = FontWeight.Bold, fontSize = 19.sp)
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                Text("ÁUDIO", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (audio.size > 1) {
                    audio.forEach { opt ->
                        TrackRow(opt.label, selected = opt.selected) { onPickAudio(opt) }
                        Spacer(Modifier.height(5.dp))
                    }
                } else {
                    Text("Só uma faixa de áudio" + (audio.firstOrNull()?.let { " (${it.label})" } ?: ""), color = Color(0xFFCBD5E1), fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("LEGENDA", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                // busca logo no topo da seção (no celular ficava escondida embaixo de "Desligada")
                if (onSearch != null) {
                    TrackRow(if (external.isEmpty()) "Buscar legenda na internet" else "Buscar outra legenda", onClick = onSearch)
                    Spacer(Modifier.height(4.dp))
                    searchStatus?.let { Text(it, color = Color(0xFFFBBF24), fontSize = 12.sp); Spacer(Modifier.height(4.dp)) }
                    Spacer(Modifier.height(2.dp))
                }
                if (text.isEmpty() && external.isEmpty()) {
                    Text("Este conteúdo não tem legendas no arquivo", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                }
                if (text.isNotEmpty() || external.isNotEmpty()) {
                    TrackRow("Desligada", selected = textOff) { onPickText(null) }
                    Spacer(Modifier.height(5.dp))
                    text.forEach { opt ->
                        TrackRow(opt.label, selected = opt.selected) { onPickText(opt) }
                        Spacer(Modifier.height(5.dp))
                    }
                    external.forEach { row ->
                        TrackRow(row.label, selected = row.selected, onClick = row.onClick)
                        Spacer(Modifier.height(5.dp))
                    }
                }
                if (sync != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("SINCRONIA DA LEGENDA", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text("Atrasada? use +. Adiantada? use −.  Agora: ${sync.label}", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SyncButton("−1s", Modifier.weight(1f)) { sync.onShift(-1000) }
                        SyncButton("−0,25", Modifier.weight(1f)) { sync.onShift(-250) }
                        SyncButton("+0,25", Modifier.weight(1f)) { sync.onShift(250) }
                        SyncButton("+1s", Modifier.weight(1f)) { sync.onShift(1000) }
                    }
                    Spacer(Modifier.height(6.dp))
                    SyncButton("Zerar (${sync.label})", Modifier.fillMaxWidth()) { sync.onReset() }
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
