package com.hugo.player.player

/**
 * Pedido de reprodução passado entre telas sem codificar a URL na rota
 * (URLs de IPTV costumam ter caracteres que quebram a navegação).
 */
data class PlaybackRequest(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    /** null = detectar automaticamente pelo player */
    val isLive: Boolean? = null,
    val userAgent: String? = null,
    /** Próximos itens (ex.: episódios seguintes) */
    val queue: List<PlaybackRequest> = emptyList(),
)

object PlaybackSession {
    @Volatile
    var current: PlaybackRequest? = null
}

object StreamTypes {
    const val DEFAULT_USER_AGENT = "TiviMate/4.7.0 (Android 11; NVIDIA SHIELD TV Pro)"

    enum class Kind { HLS, TS, PROGRESSIVE, UNKNOWN }

    /** Detecta o tipo pelo caminho da URL (ignorando query string). */
    fun detect(url: String): Kind {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") || path.contains("/hls/") -> Kind.HLS
            path.endsWith(".ts") -> Kind.TS
            Regex("\\.(mp4|mkv|avi|mov|webm|m4v|flv|wmv|mpg|mpeg|3gp)$").containsMatchIn(path) -> Kind.PROGRESSIVE
            else -> Kind.UNKNOWN
        }
    }
}
