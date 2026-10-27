package com.hugo.player.data

import android.content.Context
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.player.PlaybackRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * Episódios de uma série: os salvos no aparelho ou, em Xtream, a lista do servidor.
 * Usado pela tela da série e pelo "Continuar assistindo" da Home (para manter o próximo episódio).
 */
object SeriesEpisodes {
    data class EpisodeMeta(val plot: String?, val durationSecs: Int?)
    data class Loaded(val episodes: List<ChannelEntity>, val meta: Map<String, EpisodeMeta>, val error: String?)

    suspend fun load(context: Context, playlistId: String, name: String): Loaded {
        val repo = CatalogRepository.get(context)
        val local = withContext(Dispatchers.IO) { repo.episodes(playlistId, name) }
        val marker = local.firstOrNull()?.url
        if (marker == null || !marker.startsWith("xtream://series/")) return Loaded(local, emptyMap(), null)
        val seriesId = marker.removePrefix("xtream://series/")
        // só o id da playlist + aparelho: o servidor usa o link guardado (sem usuário/senha na URL)
        val identity = DeviceIdentity.get(context)
        val endpoint = "${HugoApi.baseUrl}/channels/series-episodes?playlistId=${java.net.URLEncoder.encode(playlistId, "UTF-8")}&seriesId=$seriesId"
        return try {
            val body = withContext(Dispatchers.IO) {
                val conn = URL(endpoint).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("x-device-id", identity.deviceId)
                conn.setRequestProperty("x-device-key", identity.deviceKey)
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                val code = conn.responseCode
                val b = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw Exception("HTTP $code: ${b.take(200)}")
                b
            }
            val arr = JSONObject(body).optJSONArray("episodes")
            val list = mutableListOf<ChannelEntity>()
            val metaOut = HashMap<String, EpisodeMeta>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val plot = e.optString("plot").takeIf { it.isNotBlank() && it != "null" }
                    val dur = e.optInt("durationSecs", 0).takeIf { it > 0 }
                    if (plot != null || dur != null) metaOut[e.getString("url")] = EpisodeMeta(plot, dur)
                    list.add(ChannelEntity(name = e.optString("name", name), url = e.getString("url"), logo = e.optString("logo").takeIf { it.isNotEmpty() }, group = "", type = "series", playlistId = playlistId, seriesName = name, season = e.optInt("season", 1), episode = e.optInt("episode", i + 1)))
                }
            }
            Loaded(list, metaOut, null)
        } catch (ex: Exception) {
            Loaded(
                local, emptyMap(),
                "Não deu para carregar os episódios do servidor" + (if (local.size > 1) " — mostrando os salvos." else ".") +
                    " (" + (ex.message?.substringBefore(':')?.take(40) ?: ex.javaClass.simpleName) + ")",
            )
        }
    }

    /** Ordem de reprodução: temporadas em ordem, episódios na ordem da lista. */
    fun ordered(episodes: List<ChannelEntity>): List<ChannelEntity> =
        episodes.groupBy { it.season ?: 1 }.toSortedMap().values.flatten()

    /** "T2 · E05" do subtítulo salvo no Continuar assistindo → (2, 5). */
    fun parseCode(subtitle: String?): Pair<Int, Int>? {
        val m = Regex("""^T(\d+) · E(\d+)""").find(subtitle ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** Próximos episódios depois do que está em [url] (ou do T/E do subtítulo). Vazio se não achar. */
    fun queueAfter(
        episodes: List<ChannelEntity>, url: String, subtitle: String?,
        build: (ChannelEntity) -> PlaybackRequest,
    ): List<PlaybackRequest> {
        val ordered = ordered(episodes)
        var idx = ordered.indexOfFirst { it.url == url }
        if (idx < 0) {
            val code = parseCode(subtitle) ?: return emptyList()
            idx = ordered.indexOfFirst { (it.season ?: 1) == code.first && it.episode == code.second }
        }
        if (idx < 0) return emptyList()
        return ordered.drop(idx + 1).take(50).map(build)
    }
}
