package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchMergeTest {
    private fun api(provider: String) = object : MainAPI() {
        override var name = provider
        override var mainUrl = "https://$provider.example"
    }

    private fun movie(provider: String, title: String, year: Int? = null): SearchResponse =
        api(provider).newMovieSearchResponse(title, "https://$provider.example/$title", fix = false) {
            this.year = year
        }

    private fun series(provider: String, title: String, year: Int? = null): SearchResponse =
        api(provider).newTvSeriesSearchResponse(
            title, "https://$provider.example/$title", TvType.TvSeries, fix = false
        ) { this.year = year }

    private val speeds = mapOf("fast" to 100L, "slow" to 900L, "mid" to 400L)
    private val speedOf = { name: String -> speeds[name] ?: SearchMerge.UNKNOWN_SPEED_MS }

    @Test
    fun sameTitleKeepsTheFastestProvider() {
        val items = listOf(
            movie("slow", "Inception", 2010),
            movie("fast", "Inception", 2010),
            movie("mid", "Inception", 2010),
        )
        val merged = SearchMerge.merge(items, speedOf)
        assertEquals(1, merged.size)
        assertEquals("fast", merged.single().apiName)
        // The other two stay reachable.
        assertEquals(
            setOf("slow", "mid"),
            SearchMerge.alternativesOf(merged.single().url).map { it.apiName }.toSet()
        )
    }

    @Test
    fun titleDecorationsAndTurkishCharactersStillMatch() {
        val items = listOf(
            movie("slow", "Yüzüklerin Efendisi izle (2001)", 2001),
            movie("fast", "YUZUKLERIN EFENDISI Türkçe Dublaj", 2001),
        )
        assertEquals(1, SearchMerge.merge(items, speedOf).size)
    }

    @Test
    fun differentYearsAreDifferentTitles() {
        val items = listOf(
            movie("fast", "The Thing", 1982),
            movie("slow", "The Thing", 2011),
        )
        assertEquals(2, SearchMerge.merge(items, speedOf).size)
    }

    @Test
    fun movieAndSeriesWithTheSameNameStaySeparate() {
        val items = listOf(movie("fast", "Fargo", 1996), series("slow", "Fargo", 1996))
        assertEquals(2, SearchMerge.merge(items, speedOf).size)
    }

    @Test
    fun orderFollowsFirstAppearanceAndUnrelatedTitlesSurvive() {
        val items = listOf(
            movie("slow", "Alpha", 2000),
            movie("fast", "Beta", 2001),
            movie("fast", "Alpha", 2000),
            movie("mid", "Gamma", 2002),
        )
        val merged = SearchMerge.merge(items, speedOf)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), merged.map { it.name })
        assertEquals("fast", merged.first().apiName)
    }

    @Test
    fun measuredWorkingBeatsUnmeasuredWhichBeatsFailing() {
        val speeds = mapOf("slow" to 900L, "down" to 30_000L)
        val speedOf = { name: String -> speeds[name] ?: SearchMerge.UNKNOWN_SPEED_MS }

        val workingVsUnknown = listOf(movie("never-measured", "Delta", 1999), movie("slow", "Delta", 1999))
        assertEquals("slow", SearchMerge.merge(workingVsUnknown, speedOf).single().apiName)

        val unknownVsFailing = listOf(movie("down", "Echo", 1999), movie("never-measured", "Echo", 1999))
        assertEquals("never-measured", SearchMerge.merge(unknownVsFailing, speedOf).single().apiName)
    }
}
