@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hugo.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

object HugoColors {
    val Background = Color(0xFF07080F)
    val Panel = Color(0xFF12141C)
    val Card = Color(0xFF151827)
    val CardAlt = Color(0xFF1E2236)
    val Accent = Color(0xFF8B5CF6)
    val Pink = Color(0xFFEC4899)
    val Cyan = Color(0xFF06B6D4)
    val TextMuted = Color(0xFF94A3B8)
    val TextDim = Color(0xFF64748B)
}

/**
 * Superfície clicável e focável por D-pad: escala e borda ao receber foco.
 * Funciona igual no toque (celular) e no controle remoto (TV).
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    color: Color = HugoColors.Card,
    focusedColor: Color = HugoColors.CardAlt,
    selected: Boolean = false,
    selectedColor: Color = HugoColors.Accent,
    focusScale: Float = 1.04f,
    onFocused: (() -> Unit)? = null,
    /** borda do foco (pôsteres usam mais grossa, para enxergar de longe na TV) */
    focusBorderWidth: Dp = 2.dp,
    focusBorderColor: Color = Color.White,
    /** segurar (toque) — opcional */
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "focusScale")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                when {
                    selected -> selectedColor
                    focused -> focusedColor
                    else -> color
                }
            )
            .border(focusBorderWidth, if (focused) focusBorderColor else Color.Transparent, shape)
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .then(
                if (onLongClick != null) Modifier.combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
                else Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
            )
    ) {
        content(focused)
    }
}

/** Logo do canal com fallback para as iniciais. */
@Composable
fun ChannelLogo(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(
        modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F1220)),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNullOrBlank()) {
            Initials(name)
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(ctx).data(url).crossfade(true).size(256).build(),
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                loading = { Initials(name) },
                error = { Initials(name) },
            )
        }
    }
}

@Composable
private fun Initials(name: String) {
    val initials = name.split(' ', '-', '|', '.').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifBlank { "TV" }
    Text(initials, color = HugoColors.TextMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

/** Card de pôster 2:3 para filmes e séries. */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    subtitle: String?,
    rating: Double?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    FocusSurface(onClick = onClick, modifier = modifier, focusScale = 1.07f, onFocused = onFocused, focusBorderWidth = 3.dp, focusedColor = HugoColors.Accent.copy(alpha = 0.45f)) { focused ->
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Color(0xFF0F1220))) {
                PosterPlaceholder(title)
                if (!imageUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(ctx).data(imageUrl).crossfade(true).size(400, 600).build(),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        error = { PosterPlaceholder(title) },
                    )
                }
                if (rating != null && rating > 0) {
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star, null, tint = Color(0xFFFACC15), modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("%.1f".format(rating), color = Color.White, fontSize = 11.sp)
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                )
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    title,
                    color = if (focused) Color.White else Color(0xFFE2E8F0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    minLines = 2,
                )
                subtitle?.let { Text(it, color = HugoColors.TextMuted, fontSize = 10.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun PosterPlaceholder(title: String) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1E2236), Color(0xFF0F1220)))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title,
            color = HugoColors.TextDim,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp)
        )
    }
}

/** Item de categoria da barra lateral. */
@Composable
fun CategoryRow(name: String, count: Int?, selected: Boolean, accent: Color, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        selected = selected,
        selectedColor = accent,
        color = HugoColors.CardAlt,
        focusedColor = accent.copy(alpha = 0.55f),
        focusScale = 1.02f,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().height(44.dp)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            count?.let { Text("$it", color = if (selected) Color.White else HugoColors.TextMuted, fontSize = 11.sp) }
        }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = HugoColors.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
