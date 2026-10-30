package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import java.text.Normalizer

/**
 * Collapses search results that are the same title from different providers into one entry.
 * The entry that is kept comes from the fastest provider; the others are remembered in
 * [alternativesOf] so they stay reachable.
 */
object SearchMerge {
    /** Providers that were never measured count as moderately slow, not as the worst. */
    const val UNKNOWN_SPEED_MS = 6_000L

    private val noise = Regex(
        "\\b(izle|full|hd|1080p|720p|dublaj|altyazili|turkce|online|bedava)\\b"
    )
    private val brackets = Regex("[(\\[].*?[)\\]]")
    private val symbols = Regex("[^\\p{L}\\p{N}]+")
    private val marks = Regex("\\p{M}+")

    private val alternatives = java.util.concurrent.ConcurrentHashMap<String, List<SearchResponse>>()

    /** Other providers' results for the entry whose url is [url] (empty if none/unknown). */
    fun alternativesOf(url: String): List<SearchResponse> = alternatives[url] ?: emptyList()

    fun normalize(name: String): String {
        var s = name.lowercase(java.util.Locale.ROOT).replace('ı', 'i')
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")
        s = s.replace(brackets, " ")
        s = noise.replace(s, " ")
        return s.replace(symbols, " ").trim()
    }

    private fun year(item: SearchResponse): Int? = when (item) {
        is MovieSearchResponse -> item.year
        is TvSeriesSearchResponse -> item.year
        is AnimeSearchResponse -> item.year
        else -> null
    }

    private fun kind(type: TvType?): String = when (type) {
        TvType.Movie, TvType.AnimeMovie, TvType.Documentary -> "movie"
        TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.Cartoon, TvType.AsianDrama -> "series"
        else -> "other"
    }

    private fun keyOf(item: SearchResponse): String? {
        val title = normalize(item.name)
        if (title.length < 2) return null
        return "$title|${kind(item.type)}|${year(item) ?: ""}"
    }

    /**
     * @param speedOf response time in ms per provider name, lower is faster
     * @return the merged list, in the order of each title's first appearance
     */
    fun merge(items: List<SearchResponse>, speedOf: (String) -> Long): List<SearchResponse> {
        val groups = LinkedHashMap<String, MutableList<SearchResponse>>()
        val result = ArrayList<Any>() // either a group key (String) or an ungrouped item
        for (item in items) {
            val key = keyOf(item)
            if (key == null) {
                result += item
                continue
            }
            val group = groups[key]
            if (group == null) {
                groups[key] = mutableListOf(item)
                result += key
            } else {
                group += item
            }
        }

        val merged = ArrayList<SearchResponse>(result.size)
        for (entry in result) {
            if (entry is SearchResponse) {
                merged += entry
                continue
            }
            val group = groups.getValue(entry as String)
            // minByOrNull keeps the earliest item on ties, so provider order still matters.
            val best = group.minByOrNull { speedOf(it.apiName) } ?: group.first()
            merged += best
            alternatives[best.url] = group.filter { it !== best }
        }
        return merged
    }
}
