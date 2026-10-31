package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType

/**
 * Collapses search results that are the same title from different providers into one entry.
 * The entry that is kept comes from the fastest provider; the others are remembered in
 * [alternativesOf] so they stay reachable.
 */
object SearchMerge {
    /** Providers that were never measured count as moderately slow, not as the worst. */
    const val UNKNOWN_SPEED_MS = 6_000L

    /** Words providers add to titles; ignored when comparing (already folded to ASCII). */
    private val noiseWords = hashSetOf(
        "izle", "full", "hd", "1080p", "720p", "dublaj", "altyazili", "turkce", "online", "bedava"
    )

    // Titles repeat across searches and publishes, so each one is normalised only once.
    private val normalized = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val alternatives = java.util.concurrent.ConcurrentHashMap<String, List<SearchResponse>>()

    /** Other providers' results for the entry whose url is [url] (empty if none/unknown). */
    fun alternativesOf(url: String): List<SearchResponse> = alternatives[url] ?: emptyList()

    fun normalize(name: String): String {
        normalized[name]?.let { return it }
        if (normalized.size > 20_000) normalized.clear()
        return compute(name).also { normalized[name] = it }
    }

    /** Folds the letters that differ between languages (ı, ç, ğ, ö, ş, ü, accents) to ASCII. */
    private fun fold(c: Char): Char {
        val l = c.lowercaseChar()
        return when (l) {
            'ı' -> 'i'
            'ç' -> 'c'
            'ğ' -> 'g'
            'ö', 'ó', 'ò', 'ô', 'õ' -> 'o'
            'ş' -> 's'
            'ü', 'ú', 'ù', 'û' -> 'u'
            'á', 'à', 'ä', 'â', 'ã', 'å' -> 'a'
            'é', 'è', 'ê', 'ë' -> 'e'
            'í', 'ì', 'ï', 'î' -> 'i'
            'ñ' -> 'n'
            else -> l
        }
    }

    // One pass over the characters, no regular expressions or Unicode normalisation: this runs
    // for every result of every provider on devices with a slow CPU.
    private fun compute(name: String): String {
        val sb = StringBuilder(name.length)
        var depth = 0
        for (c in name) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            when {
                c == '(' || c == '[' -> { depth++; sb.append(' ') }
                c == ')' || c == ']' -> { if (depth > 0) depth--; sb.append(' ') }
                depth > 0 -> {}
                else -> {
                    val f = fold(c)
                    sb.append(if (f.isLetterOrDigit()) f else ' ')
                }
            }
        }
        val out = StringBuilder(sb.length)
        for (word in sb.split(' ')) {
            if (word.isEmpty() || word in noiseWords) continue
            if (out.isNotEmpty()) out.append(' ')
            out.append(word)
        }
        return out.toString()
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
