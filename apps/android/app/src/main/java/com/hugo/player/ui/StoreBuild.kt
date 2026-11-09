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

    /**
     * Textos que o app base ainda não traduz (só aparecem traduzidos no build "play", para as telas da loja).
     * Chave em português; valor [en, es]. Aplicado antes de [wording].
     */
    private val EXTRA: Map<String, Array<String>> = mapOf(
        "Canais, filmes e séries que você marcar" to arrayOf("Channels, movies and series you mark", "Canales, películas y series que marques"),
        "Trocar, adicionar ou editar listas" to arrayOf("Switch, add or edit connections", "Cambiar, agregar o editar conexiones"),
    )
    private val EXTRA_PATTERNS: List<Pair<Regex, Array<String>>> = listOf(
        Regex("Vence em (\\d{2}/\\d{2}/\\d{4})") to arrayOf("Expires on $1", "Vence el $1"),
        Regex(" · atualizada ") to arrayOf(" · updated ", " · actualizada "),
        Regex("(\\d[\\d.]*) canais") to arrayOf("$1 channels", "$1 canales"),
        Regex("(\\d[\\d.]*) filmes") to arrayOf("$1 movies", "$1 películas"),
        Regex("(\\d[\\d.]*) séries") to arrayOf("$1 series", "$1 series"),
    )

    /** Tradução extra do build "play" (en/es); null = usar a tradução normal. */
    fun extra(pt: String, lang: String): String? {
        if (lang == "pt") return null
        val i = if (lang == "en") 0 else 1
        EXTRA[pt]?.let { return it[i] }
        var out = pt
        for ((rx, rep) in EXTRA_PATTERNS) out = rx.replace(out, rep[i])
        return if (out != pt) out else null
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
