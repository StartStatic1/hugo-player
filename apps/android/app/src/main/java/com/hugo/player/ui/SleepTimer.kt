package com.hugo.player.ui

/** Timer de desligar: minutos fixos ou "fim do episódio" (só VOD). */
enum class SleepOption(val minutes: Int, val label: String) {
    OFF(0, "Desligado"),
    M15(15, "15 min"),
    M30(30, "30 min"),
    M60(60, "60 min"),
    M90(90, "90 min"),
    END_OF_EPISODE(-1, "Fim do episódio");

    companion object {
        fun cycle(current: SleepOption, allowEndOfEpisode: Boolean): SleepOption {
            val list = entries.filter { allowEndOfEpisode || it != END_OF_EPISODE }
            return list[(list.indexOf(current) + 1) % list.size]
        }
    }
}

/** Velocidades do player de filmes/séries. */
val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

fun nextSpeed(current: Float): Float {
    val i = PlaybackSpeeds.indexOfFirst { it == current }
    return PlaybackSpeeds[(if (i < 0) PlaybackSpeeds.indexOf(1f) else i + 1) % PlaybackSpeeds.size]
}

fun speedLabel(s: Float): String = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().replace('.', ',')) + "x"

/** "29 min" / "45 s" restantes. */
fun sleepRemainingLabel(remainingMs: Long): String =
    if (remainingMs >= 60_000) "${(remainingMs + 59_999) / 60_000} min" else "${(remainingMs / 1000).coerceAtLeast(0)} s"
