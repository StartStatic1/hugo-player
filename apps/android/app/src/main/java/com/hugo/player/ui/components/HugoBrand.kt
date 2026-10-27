package com.hugo.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val HugoGradient = Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)))

/** Logo "▶ Hugo Player" usado no topo das telas. */
@Composable
fun HugoLogo(modifier: Modifier = Modifier, markSize: Dp = 36.dp, textSize: TextUnit = 22.sp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // mesmo desenho do ícone do app (TV retrô + play, ciano → roxo)
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.hugo.player.R.drawable.hugo_logo_mark),
            contentDescription = null,
            modifier = Modifier.size(markSize).clip(RoundedCornerShape(markSize * 0.24f)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White)) { append("Hugo ") }
                withStyle(SpanStyle(color = HugoColors.Accent)) { append("Player") }
            },
            fontSize = textSize,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** Botão compacto com ícone (e texto opcional), focável por D-pad. */
@Composable
fun HugoIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    tint: Color = Color.White,
    background: Color = HugoColors.CardAlt,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (focused) HugoColors.Accent else background)
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (label != null) 12.dp else 9.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else HugoColors.TextDim, modifier = Modifier.size(18.dp))
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = if (enabled) Color.White else HugoColors.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Bloco com brilho animado para skeleton de carregamento. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(12.dp)) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    val base = HugoColors.Card
    val hi = HugoColors.CardAlt
    Box(
        modifier.clip(shape).background(
            Brush.linearGradient(
                colors = listOf(base, hi, base),
                start = androidx.compose.ui.geometry.Offset(x * 1200f - 400f, 0f),
                end = androidx.compose.ui.geometry.Offset(x * 1200f, 200f),
            )
        )
    )
}

/** Avatar redondo com a inicial do nome, em gradiente. */
@Composable
fun InitialAvatar(name: String, size: Dp, modifier: Modifier = Modifier, brush: Brush = HugoGradient, icon: ImageVector? = null) {
    Box(modifier.size(size).clip(CircleShape).background(brush), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.45f))
        else Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp
        )
    }
}
