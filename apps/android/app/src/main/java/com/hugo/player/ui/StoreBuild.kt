package com.hugo.player.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hugo.player.BuildConfig

/**
 * Diferenças do build "play" (Google Play). No APK do site / Fire TV [isPlay] é false e nada aqui muda.
 *  - sem tela de compra, preço ou link de pagamento;
 *  - atualização só pela Play Store (sem baixar APK);
 *  - textos sem "playlist"/"lista": o usuário entra com os dados do próprio provedor (conexão).
 */
object StoreBuild {
    const val isPlay: Boolean = BuildConfig.PLAY_STORE

    /** Mensagem neutra de licença inativa (teste encerrado / licença vencida) — sem preço nem link. */
    const val LICENSE_INACTIVE = "Teste encerrado. Ative sua licença."

    /** Abre a página do app na Play Store (atualizar). */
    fun openStoreListing(context: Context) {
        val pkg = context.packageName
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private val WORDS = Regex("(?<![\\p{L}])(playlists|playlist|listas|lista)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Troca "playlist(s)" / "lista(s)" por "conexão(ões)" no texto já traduzido (pt/en/es).
     * Só é chamado no build "play".
     */
    fun wording(text: String, lang: String): String {
        if (text.isEmpty()) return text
        val key = "$lang\u0000$text"
        cache[key]?.let { return it }
        val out = if (!WORDS.containsMatchIn(text)) text else WORDS.replace(text) { m ->
            val w = m.value
            val plural = w.endsWith("s", ignoreCase = true)
            val isLista = w.startsWith("l", ignoreCase = true)
            val rep = when (lang) {
                "en" -> if (isLista) w else if (plural) "connections" else "connection"
                "es" -> if (plural) "conexiones" else "conexión"
                else -> if (plural) "conexões" else "conexão"
            }
            when {
                w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() } -> rep.uppercase()
                w[0].isUpperCase() -> rep.replaceFirstChar { it.uppercase() }
                else -> rep
            }
        }
        if (cache.size > 4000) cache.clear()
        cache[key] = out
        return out
    }
}
