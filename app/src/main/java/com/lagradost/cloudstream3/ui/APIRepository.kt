package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class APIRepository(val api: MainAPI) {
    companion object {
        // 2 minute timeout to prevent bad extensions/extractors from hogging the resources
        // No real provider should take longer, so we hard kill them.
        private const val DEFAULT_TIMEOUT = 120_000L
        private const val MAX_TIMEOUT = 4 * DEFAULT_TIMEOUT
        private const val MIN_TIMEOUT = 5_000L

        var dubStatusActive = HashSet<DubStatus>()

        val noneApi = object : MainAPI() {
            override var name = "None"
            override val supportedTypes = emptySet<TvType>()
            override var lang = ""
        }
        val randomApi = object : MainAPI() {
            override var name = "Random"
            override val supportedTypes = emptySet<TvType>()
            override var lang = ""
        }

        fun isInvalidData(data: String): Boolean {
            return data.isEmpty() || data == "[]" || data == "about:blank"
        }

        data class SavedLoadResponse(
            val unixTime: Long,
            val response: LoadResponse,
            val hash: Pair<String, String>
        )

        /**
         * Stale-while-revalidate cache for home pages and search results: a fresh entry is
         * returned as is, a stale one is returned immediately and refreshed in the background.
         */
        // Soft reference: the system may drop cached pages itself when memory runs short.
        private class Timed<T>(value: T, val time: Long = unixTimeMS) {
            private val ref = java.lang.ref.SoftReference(value)
            val value: T? get() = ref.get()
        }

        private const val MAIN_FRESH_MS = 5 * 60_000L
        private const val MAIN_STALE_MS = 6 * 60 * 60_000L
        private const val SEARCH_FRESH_MS = 5 * 60_000L
        private const val SEARCH_STALE_MS = 60 * 60_000L
        private const val MAX_CACHE_ENTRIES = 60

        private val mainPageCache = ConcurrentHashMap<String, Timed<List<HomePageResponse?>>>()
        private val searchCache = ConcurrentHashMap<String, Timed<SearchResponseList>>()
        private val refreshing = ConcurrentHashMap.newKeySet<String>()
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun <T> ConcurrentHashMap<String, Timed<T>>.put(key: String, value: T) {
            if (size >= MAX_CACHE_ENTRIES) {
                entries.minByOrNull { it.value.time }?.key?.let { remove(it) }
            }
            this[key] = Timed(value)
        }

        /** Drops every cached page; called when the system reports low memory. */
        fun clearCaches() {
            mainPageCache.clear()
            searchCache.clear()
            cache.clear()
        }

        private val cache = atomicListOf<SavedLoadResponse>()
        private var cacheIndex: Int = 0
        const val CACHE_SIZE = 20

        fun getTimeout(desired: Long?): Long {
            return (desired ?: DEFAULT_TIMEOUT).coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
        }
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        if (forceReload) {
            cache.clear()
            mainPageCache.clear()
            searchCache.clear()
        }
    }

    init {
        afterPluginsLoadedEvent += ::afterPluginsLoaded
    }

    val hasMainPage = api.hasMainPage
    val providerType = api.providerType
    val name = api.name
    val mainUrl = api.mainUrl
    val mainPage = api.mainPage
    val hasQuickSearch = api.hasQuickSearch
    val vpnStatus = api.vpnStatus

    suspend fun load(url: String): Resource<LoadResponse> {
        return safeApiCall {
            withTimeout(getTimeout(api.loadTimeoutMs)) {
                if (isInvalidData(url)) throw ErrorLoadingException()
                val fixedUrl = api.fixUrl(url)
                val lookingForHash = Pair(api.name, fixedUrl)

                val cached = cache.withLock {
                    var found: LoadResponse? = null
                    for (item in cache) {
                        // 30 min save
                        if (item.hash == lookingForHash && (unixTime - item.unixTime) < 60 * 30) {
                            found = item.response
                            break
                        }
                    }
                    found
                }

                if (cached != null) return@withTimeout cached
                api.load(fixedUrl)?.also { response ->
                    // Remove all blank tags as early as possible
                    response.tags = response.tags?.filter { it.isNotBlank() }
                    val add = SavedLoadResponse(unixTime, response, lookingForHash)

                    cache.withLock {
                        if (cache.size > CACHE_SIZE) {
                            cache[cacheIndex] = add // rolling cache
                            cacheIndex = (cacheIndex + 1) % CACHE_SIZE
                        } else {
                            cache.add(add)
                        }
                    }
                } ?: throw ErrorLoadingException()
            }
        }
    }

    suspend fun search(query: String, page: Int): Resource<SearchResponseList> {
        if (query.isEmpty())
            return Resource.Success(newSearchResponseList(emptyList()))

        val key = "${api.name}|$query|$page"
        searchCache[key]?.let { hit ->
            val cachedValue = hit.value ?: return@let
            val age = unixTimeMS - hit.time
            if (age < SEARCH_STALE_MS) {
                if (age >= SEARCH_FRESH_MS && refreshing.add("s|$key")) {
                    refreshScope.launch {
                        try {
                            (fetchSearch(query, page) as? Resource.Success)
                                ?.let { searchCache.put(key, it.value) }
                        } finally {
                            refreshing.remove("s|$key")
                        }
                    }
                }
                return Resource.Success(cachedValue)
            }
        }

        return fetchSearch(query, page).also { result ->
            if (result is Resource.Success) searchCache.put(key, result.value)
        }
    }

    private suspend fun fetchSearch(query: String, page: Int): Resource<SearchResponseList> {
        return safeApiCall {
            withTimeout(getTimeout(api.searchTimeoutMs)) {
                (api.search(query, page)
                    ?: throw ErrorLoadingException())
                //                .filter { typesActive.contains(it.type) }
            }
        }
    }

    suspend fun quickSearch(query: String): Resource<SearchResponseList> {
        if (query.isEmpty())
            return Resource.Success(newSearchResponseList(emptyList()))

        return safeApiCall {
            withTimeout(getTimeout(api.quickSearchTimeoutMs)) {
                newSearchResponseList(
                    api.quickSearch(query) ?: throw ErrorLoadingException(),
                    false
                )
            }
        }
    }

    suspend fun waitForHomeDelay() {
        val delta = api.sequentialMainPageScrollDelay + api.lastHomepageRequest - unixTimeMS
        if (delta < 0) return
        delay(delta)
    }

    /**
     * @param useCache serve a cached home page (and refresh it in the background if it is
     * getting old). Pass false for an explicit refresh; the fetched page still updates the cache.
     */
    suspend fun getMainPage(
        page: Int,
        nameIndex: Int? = null,
        useCache: Boolean = false,
    ): Resource<List<HomePageResponse?>> {
        val key = "${api.name}|$page|$nameIndex"
        if (useCache) {
            mainPageCache[key]?.let { hit ->
                val cachedValue = hit.value ?: return@let
                val age = unixTimeMS - hit.time
                if (age < MAIN_STALE_MS) {
                    if (age >= MAIN_FRESH_MS && refreshing.add("m|$key")) {
                        refreshScope.launch {
                            try {
                                (fetchMainPage(page, nameIndex) as? Resource.Success)
                                    ?.let { mainPageCache.put(key, it.value) }
                            } finally {
                                refreshing.remove("m|$key")
                            }
                        }
                    }
                    return Resource.Success(cachedValue)
                }
            }
        }

        return fetchMainPage(page, nameIndex).also { result ->
            if (result is Resource.Success) mainPageCache.put(key, result.value)
        }
    }

    private suspend fun fetchMainPage(
        page: Int,
        nameIndex: Int?
    ): Resource<List<HomePageResponse?>> {
        return safeApiCall {
            withTimeout(getTimeout(api.getMainPageTimeoutMs)) {
                api.lastHomepageRequest = unixTimeMS

                nameIndex?.let { api.mainPage.getOrNull(it) }?.let { data ->
                    listOf(
                        api.getMainPage(
                            page,
                            MainPageRequest(data.name, data.data, data.horizontalImages)
                        )
                    )
                } ?: run {
                    if (api.sequentialMainPage) {
                        var first = true
                        api.mainPage.map { data ->
                            if (!first) // dont want to sleep on first request
                                delay(api.sequentialMainPageDelay)
                            first = false

                            api.getMainPage(
                                page,
                                MainPageRequest(data.name, data.data, data.horizontalImages)
                            )
                        }
                    } else {
                        with(CoroutineScope(coroutineContext)) {
                            api.mainPage.map { data ->
                                async {
                                    api.getMainPage(
                                        page,
                                        MainPageRequest(data.name, data.data, data.horizontalImages)
                                    )
                                }
                            }.map { it.await() }
                        }
                    }
                }
            }
        }
    }

    suspend fun extractorVerifierJob(extractorData: String?) {
        safeApiCall {
            api.extractorVerifierJob(extractorData)
        }
    }

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (isInvalidData(data)) return false // this makes providers cleaner
        return try {
            withTimeout(getTimeout(api.loadLinksTimeoutMs)) {
                api.loadLinks(data, isCasting, subtitleCallback, callback)
            }
        } catch (throwable: Throwable) {
            logError(throwable)
            return false
        }
    }
}
