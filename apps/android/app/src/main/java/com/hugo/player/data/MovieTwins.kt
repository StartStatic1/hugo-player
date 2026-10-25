package com.hugo.player.data

/**
 * Filmes que o painel manda duas vezes: "Nome (Ano)" (dublado) e "Nome [L] (Ano)" (legendado).
 * Só junta quando o nome é idêntico tirando o " [L]" — o resto fica como está.
 */
object MovieTwins {
    private const val TAG = " [L]"
    private val yearTail = Regex("^(.*\\S) \\((\\d{4})\\)$")

    fun isLegendado(name: String): Boolean = name.contains(TAG)

    /** "Nome [L] (2021)" -> "Nome (2021)" (o mesmo que o SQL faz com replace). */
    fun dubbedName(name: String): String = name.replace(TAG, "")

    /** Nomes possíveis da versão legendada de um título dublado. */
    fun legendadoCandidates(dubbed: String): List<String> {
        if (isLegendado(dubbed)) return emptyList()
        val m = yearTail.find(dubbed)
        return if (m != null) listOf("${m.groupValues[1]}$TAG (${m.groupValues[2]})", dubbed + TAG) else listOf(dubbed + TAG)
    }
}
