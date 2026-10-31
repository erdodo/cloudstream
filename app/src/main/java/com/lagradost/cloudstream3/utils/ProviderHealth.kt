package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps track of how fast and how healthy every installed provider is.
 *
 * - Speed: every [PING_INTERVAL_MS] the main url of each provider is requested and the time is
 *   kept as a moving average. Search uses it to pick the fastest provider for a title.
 * - Health: every [TEST_INTERVAL_MS] the built-in provider test runs. A provider that fails
 *   [FAILS_TO_DISABLE] times in a row is removed from the active provider list (it stays
 *   installed); a disabled provider that passes again is added back.
 *
 * State is kept per device on purpose, a device behind another network may not reach a site.
 */
object ProviderHealth {
    private const val PREFS = "provider_health"
    // Older/slower devices (32 bit TVs, about 2 GB RAM) check less often and one at a time.
    private var pingIntervalMs = 10 * 60_000L
    private var testIntervalMs = 6 * 60 * 60_000L
    private var startDelayMs = 90_000L
    private const val PING_TIMEOUT_MS = 8_000L
    private const val PING_PENALTY_MS = 30_000L
    private var pingParallelism = 4
    private const val TEST_TIMEOUT_MS = 120_000L
    private var testParallelism = 3
    private const val FAILS_TO_DISABLE = 2

    /** If more than this share of providers fail, assume the network is the problem. */
    private const val MAX_FAIL_RATIO = 0.8
    private const val CONNECTIVITY_URL = "https://www.gstatic.com/generate_204"

    private class State(var latencyMs: Long, var fails: Int, var disabled: Boolean)

    private val states = ConcurrentHashMap<String, State>()
    private val scope = CoroutineScope(SupervisorJob() + DeviceTier.backgroundDispatcher)
    private var job: Job? = null
    private var loaded = false

    @Volatile
    var lastSummary: String = "Not run yet"
        private set

    private val onPluginsLoaded: (Boolean) -> Unit = { applyDisabled() }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAutoDisableEnabled(context: Context): Boolean =
        prefs(context).getBoolean("auto_disable", true)

    fun setAutoDisableEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_disable", value).apply()
        if (!value) {
            // Switching it off gives every provider back.
            states.values.forEach { it.disabled = false; it.fails = 0 }
            save(context)
            applyDisabled()
        }
    }

    /** Smoothed response time in ms, or null when the provider was never measured. */
    fun latencyMs(apiName: String): Long? = states[apiName]?.latencyMs

    fun isDisabled(apiName: String): Boolean = states[apiName]?.disabled == true

    fun disabledNames(): List<String> = states.filterValues { it.disabled }.keys.sorted()

    /** Starts the background loops. Safe to call repeatedly. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        if (job?.isActive == true) return
        load(appContext)
        if (DeviceTier.isLowEnd(appContext)) {
            pingIntervalMs = 20 * 60_000L
            testIntervalMs = 24 * 60 * 60_000L
            startDelayMs = 5 * 60_000L
            pingParallelism = 2
            testParallelism = 1
        }
        afterPluginsLoadedEvent -= onPluginsLoaded
        afterPluginsLoadedEvent += onPluginsLoaded

        job = scope.launch {
            delay(startDelayMs)
            var lastTest = prefs(appContext).getLong("last_test", 0L)
            while (isActive) {
                try {
                    pingAll(appContext)
                    val due = System.currentTimeMillis() - lastTest >= testIntervalMs
                    if (due && isAutoDisableEnabled(appContext)) {
                        runTests(appContext)
                        lastTest = System.currentTimeMillis()
                        prefs(appContext).edit().putLong("last_test", lastTest).apply()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError(e)
                }
                delay(pingIntervalMs)
            }
        }
    }

    /** Runs the functional test of every provider right now (does not wait for the schedule). */
    fun runNow(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                pingAll(appContext)
                runTests(appContext, force = true)
                prefs(appContext).edit().putLong("last_test", System.currentTimeMillis()).apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    // ---- speed --------------------------------------------------------------------

    private suspend fun pingAll(context: Context) {
        val apis = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        val gate = Semaphore(pingParallelism)
        apis.map { api ->
            scope.async {
                gate.withPermit { pingOne(api) }
            }
        }.awaitAll()
        save(context)
    }

    private suspend fun pingOne(api: MainAPI) {
        val url = api.mainUrl
        if (!url.startsWith("http")) return
        val started = System.currentTimeMillis()
        val ok = try {
            withTimeoutOrNull(PING_TIMEOUT_MS) {
                // Any answer below 500 (even a 403 from a CDN) means the site is reachable.
                app.get(url, timeout = PING_TIMEOUT_MS / 1000).code < 500
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        val sample = if (ok) System.currentTimeMillis() - started else PING_PENALTY_MS
        val state = states.getOrPut(api.name) { State(sample, 0, false) }
        // Moving average, so one slow answer does not reorder everything.
        state.latencyMs = (state.latencyMs * 7 + sample * 3) / 10
    }

    // ---- health -------------------------------------------------------------------

    private suspend fun hasInternet(): Boolean = try {
        withTimeoutOrNull(PING_TIMEOUT_MS) { app.get(CONNECTIVITY_URL).code in 200..399 } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun runTests(context: Context, force: Boolean = false) {
        if (!hasInternet()) {
            lastSummary = "Skipped: no internet"
            return
        }
        val apis = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        if (apis.isEmpty()) return

        val gate = Semaphore(testParallelism)
        val results = apis.map { api ->
            scope.async {
                gate.withPermit {
                    val passed = try {
                        withTimeoutOrNull(TEST_TIMEOUT_MS) {
                            TestingUtils.runSingleProviderTest(api, TestingUtils.Logger()).success
                        } ?: false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    api to passed
                }
            }
        }.awaitAll()

        val failed = results.count { !it.second }
        if (results.size >= 4 && failed.toDouble() / results.size > MAX_FAIL_RATIO) {
            // Almost everything failing means the connection is bad, not the providers.
            lastSummary = "Skipped: $failed of ${results.size} failed, looks like a network problem"
            return
        }

        val newlyDisabled = ArrayList<String>()
        val newlyEnabled = ArrayList<String>()
        for ((api, passed) in results) {
            val state = states.getOrPut(api.name) { State(PING_PENALTY_MS, 0, false) }
            if (passed) {
                state.fails = 0
                if (state.disabled) {
                    state.disabled = false
                    newlyEnabled += api.name
                }
            } else {
                state.fails++
                if (!state.disabled && state.fails >= FAILS_TO_DISABLE) {
                    state.disabled = true
                    newlyDisabled += api.name
                }
            }
        }
        save(context)
        applyDisabled()

        val disabledNow = states.values.count { it.disabled }
        lastSummary = "${results.size - failed} of ${results.size} working, $disabledNow disabled"
        if (newlyDisabled.isNotEmpty()) {
            showToast("Disabled (not working): ${newlyDisabled.joinToString()}", Toast.LENGTH_LONG)
        }
        if (newlyEnabled.isNotEmpty()) {
            showToast("Enabled again: ${newlyEnabled.joinToString()}", Toast.LENGTH_LONG)
        }
    }

    /**
     * Disabled providers are taken out of the active list ([APIHolder.apis], used by home,
     * search and the provider settings) but stay installed, so they can still be tested.
     */
    private fun applyDisabled() {
        val all = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        val active = APIHolder.apis.withLock { APIHolder.apis.toList() }
        for (api in all) {
            val disabled = states[api.name]?.disabled == true
            val isActive = active.any { it === api }
            if (disabled && isActive) APIHolder.removePluginMapping(api)
            else if (!disabled && !isActive) APIHolder.addPluginMapping(api)
        }
    }

    // ---- persistence --------------------------------------------------------------

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        prefs(context).getString("states", null)?.let { stored ->
            try {
                val json = JSONObject(stored)
                json.keys().forEach { name ->
                    val o = json.getJSONObject(name)
                    states[name] = State(o.getLong("l"), o.getInt("f"), o.getBoolean("d"))
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    private fun save(context: Context) {
        val json = JSONObject()
        states.forEach { (name, s) ->
            json.put(name, JSONObject().put("l", s.latencyMs).put("f", s.fails).put("d", s.disabled))
        }
        prefs(context).edit().putString("states", json.toString()).apply()
    }
}
