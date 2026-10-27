package com.hugo.player.ui

import androidx.compose.ui.graphics.Color
import com.hugo.player.data.ListExpiry
import com.hugo.player.ui.components.HugoColors

/** Amarelo quando faltam ≤ 7 dias, vermelho vencida; resto discreto. */
fun expiryColor(level: ListExpiry.Level): Color = when (level) {
    ListExpiry.Level.EXPIRED -> Color(0xFFF87171)
    ListExpiry.Level.SOON -> Color(0xFFFBBF24)
    ListExpiry.Level.OK -> Color(0xFF86EFAC)
    ListExpiry.Level.UNLIMITED -> HugoColors.TextMuted
}
