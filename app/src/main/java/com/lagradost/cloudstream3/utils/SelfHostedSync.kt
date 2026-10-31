package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Keeps this device in sync with a self-hosted server (see sync-server/).
 *
 * Everything the app stores lives in two SharedPreferences files (the datastore and the
 * default settings). Each transferable key is one sync entry, namespaced "d:" or "s:".
 * Conflicts are resolved per key, newest write wins. Pull happens as soon as the app
 * process starts, then changes are pushed and pulled every [INTERVAL_MS].
 *
 * Keys that [BackupUtils] refuses to export (account tokens, plugin lists, download paths…)
 * are never synced either.
 */
object SelfHostedSync {
    private const val CONFIG_PREFS = "self_hosted_sync_config"
    private const val META_PREFS = "self_hosted_sync_meta"
    /** Polling interval while there is no WebSocket (also the fallback for an older server). */
    private const val INTERVAL_MS = 10_000L

    /** With a live WebSocket changes arrive as notifications; this is only a safety net. */
    private const val SAFETY_POLL_MS = 60_000L

    /** Lets a burst of local changes (a playing video saves its position often) become one push. */
    private const val WAKE_DEBOUNCE_MS = 1_500L
    private const val MIN_UI_REFRESH_MS = 2_000L

    /** Re-read a few entries behind the cursor; applying an entry twice is harmless. */
    private const val CURSOR_OVERLAP = 20L
    private const val MAX_PUSH_ENTRIES = 500
    private const val MAX_PUSH_CHARS = 3_000_000
    private const val DELETED = "-"
    private const val FULL_SCAN_INTERVAL_MS = 10 * 60_000L
    private const val PLUGIN_CHECK_INTERVAL_MS = 60_000L
    private val URL_PATTERN = Regex("^https?://[^/\\s]+(/.*)?$")

    private val JSON_TYPE = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + DeviceTier.backgroundDispatcher)
    private var job: Job? = null

    /** Human readable state for the settings dialog. */
    @Volatile
    var status: String = "Off"
        private set

    /** Per key: hash of the last value we agreed on with the server, and its timestamp. */
    private class KeyState(val hash: String, val ts: Long)

    /** A plugin change from the server that still has to be applied on this device. */
    private class Pending(val value: String?, val ts: Long)

    private class Meta(
        var cursor: Long,
        var clockOffset: Long,
        var bootstrapped: Boolean,
        val keys: MutableMap<String, KeyState>,
        val pending: MutableMap<String, Pending>,
    )

    private class Entry(
        val key: String,
        val value: String?,
        val deleted: Boolean,
        val updatedAt: Long,
        val deviceId: String = "",
    )

    private var meta: Meta? = null

    // Only keys that changed since the last cycle are encoded and compared. A full scan is the
    // safety net (first run, cleared prefs, every FULL_SCAN_INTERVAL_MS).
    private val dirtyKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var fullScanDue = true
    private var lastFullScan = 0L
    private var lastPluginCheck = 0L
    private var lastSavedSignature = 0L
    private var listenersInstalled = false

    // Real-time channel: the server tells us when another device wrote something.
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val wsClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    }
    @Volatile
    private var wsConnected = false
    private var socket: WebSocket? = null
    private var socketKey: String? = null
    private var nextSocketAttemptAt = 0L
    private var socketFailures = 0

    // A phone that slept has a dead connection it does not know about yet. On return the
    // connection is rebuilt and everything is pulled at once, before anything is opened.
    @Volatile
    private var backgroundedAt = 0L
    @Volatile
    private var urgent = false
    private const val FOREGROUND_RECONNECT_AFTER_MS = 10_000L

    // Keys this device wrote itself a moment ago (key -> time). While a device is playing it
    // rewrites its position every second; a stale value from another device must not undo that.
    private val ownWrites = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val OWN_WRITE_GUARD_MS = 20_000L

    // Pulling is only needed when the server said so (or after a while); a local change only pushes.
    @Volatile
    private var pullRequested = true
    private var lastPull = 0L

    // Screens hold their own copy of watch progress and library; tell them when it changed.
    @Volatile
    private var uiRefreshNeeded = false
    private var lastUiRefresh = 0L

    private val dataListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) fullScanDue = true else dirtyKeys.add("d:$key")
        wakeups.trySend(Unit)
    }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) fullScanDue = true else dirtyKeys.add("s:$key")
        wakeups.trySend(Unit)
    }

    private fun installListeners(context: Context) {
        if (listenersInstalled) return
        listenersInstalled = true
        context.getSharedPrefs().registerOnSharedPreferenceChangeListener(dataListener)
        context.getDefaultSharedPrefs().registerOnSharedPreferenceChangeListener(settingsListener)
    }

    data class Config(val url: String, val token: String, val enabled: Boolean)

    private fun configPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)

    private fun metaPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)

    fun getConfig(context: Context): Config {
        val prefs = configPrefs(context)
        return Config(
            prefs.getString("url", "") ?: "",
            prefs.getString("token", "") ?: "",
            prefs.getBoolean("enabled", false),
        )
    }

    fun configure(context: Context, url: String, token: String, enabled: Boolean) {
        configPrefs(context).edit()
            .putString("url", url.trim().trimEnd('/'))
            .putString("token", token.trim())
            .putBoolean("enabled", enabled)
            .apply()
        start(context)
    }

    /** (Re)starts the loop. Safe to call repeatedly. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        installListeners(appContext)
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try {
                    val cfg = getConfig(appContext)
                    if (cfg.enabled && URL_PATTERN.matches(cfg.url) && cfg.token.isNotEmpty()) {
                        ensureSocket(appContext, cfg)
                        syncOnce(appContext, cfg)
                        reconcilePlugins(appContext)
                    } else {
                        closeSocket()
                        status = "Off"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    status = "Offline: ${e.message}"
                } catch (e: Exception) {
                    status = "Error: ${e.message}"
                    logError(e)
                }
                // A server notification or a local change wakes the loop at once; otherwise it
                // polls (rarely when the WebSocket is up, every INTERVAL_MS when it is not).
                withTimeoutOrNull(if (wsConnected) SAFETY_POLL_MS else INTERVAL_MS) {
                    wakeups.receive()
                }
                if (urgent) urgent = false else delay(WAKE_DEBOUNCE_MS)
            }
        }
    }

    fun onBackground() {
        backgroundedAt = System.currentTimeMillis()
    }

    /** The app is in front again (also after the screen was off). */
    fun onForeground() {
        val away = if (backgroundedAt > 0) System.currentTimeMillis() - backgroundedAt else 0L
        backgroundedAt = 0
        pullRequested = true
        urgent = true
        if (away > FOREGROUND_RECONNECT_AFTER_MS) {
            closeSocket()
            socketFailures = 0
            nextSocketAttemptAt = 0
        }
        wakeups.trySend(Unit)
    }

    // ---- WebSocket ----------------------------------------------------------------

    @Synchronized
    private fun closeSocket() {
        socket?.close(1000, "closing")
        socket = null
        socketKey = null
        wsConnected = false
    }

    @Synchronized
    private fun socketDown() {
        wsConnected = false
        socket = null
        socketFailures++
        // 2 s, 4 s, 8 s ... at most 5 minutes; an old server without WebSocket just keeps polling.
        val backoff = minOf(5 * 60_000L, 2_000L shl minOf(socketFailures, 7))
        nextSocketAttemptAt = System.currentTimeMillis() + backoff
        wakeups.trySend(Unit)
    }

    @Synchronized
    private fun ensureSocket(context: Context, cfg: Config) {
        val key = cfg.url + "|" + cfg.token
        if (socketKey != null && socketKey != key) closeSocket() // address or token changed
        if (socket != null) return
        if (System.currentTimeMillis() < nextSocketAttemptAt) return

        val request = Request.Builder()
            .url(cfg.url.replaceFirst("http", "ws") + "/v1/ws") // http -> ws, https -> wss
            .header("Authorization", "Bearer ${cfg.token}")
            .header("X-Device-Id", deviceId(context))
            .build()
        socketKey = key
        socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected = true
                socketFailures = 0
                pullRequested = true
                wakeups.trySend(Unit) // catch up on whatever happened while we were away
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"changed\"")) {
                    pullRequested = true
                    wakeups.trySend(Unit)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socketDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socketDown()
            }
        })
    }

    @Synchronized
    private fun syncOnce(context: Context, cfg: Config) {
        val state = loadMeta(context)
        val deviceId = deviceId(context)

        if (!state.bootstrapped) {
            // First run on this device: whatever the server already has wins, and
            // what this device has on top is pushed afterwards. Keep a copy of local data first.
            writeSafetyBackup(context)
            pull(context, cfg, state, unconditional = true)
            state.bootstrapped = true
        }

        push(context, cfg, state, deviceId)
        val now = System.currentTimeMillis()
        val pollEvery = if (wsConnected) SAFETY_POLL_MS else INTERVAL_MS
        if (pullRequested || now - lastPull >= pollEvery) {
            pullRequested = false
            lastPull = now
            try {
                pull(context, cfg, state, unconditional = false)
            } catch (e: Exception) {
                pullRequested = true
                throw e
            }
        }
        saveMeta(context, state)
        status = (if (wsConnected) "Live " else "Synced ") +
            java.text.DateFormat.getTimeInstance().format(java.util.Date())
        refreshUiIfNeeded()
    }

    /** Playback position in ms from an encoded "video_pos_dur" value, or null if it is not one. */
    internal fun positionOf(encoded: String?): Long? {
        if (encoded == null) return null
        return try {
            val pos = JSONObject(JSONObject(encoded).getString("v")).optLong("position", -1L)
            if (pos >= 0) pos else null
        } catch (e: Exception) {
            null
        }
    }

    private fun isUiRelevant(prefKey: String): Boolean =
        prefKey.contains("video_pos_dur") || prefKey.contains("result_resume_watching") ||
            prefKey.contains("result_watch_state") || prefKey.contains("result_subscribed")

    /** Watch progress or library changed on another device: make the open screens re-read it. */
    private fun refreshUiIfNeeded() {
        if (!uiRefreshNeeded) return
        val now = System.currentTimeMillis()
        if (now - lastUiRefresh < MIN_UI_REFRESH_MS) return
        uiRefreshNeeded = false
        lastUiRefresh = now
        Handler(Looper.getMainLooper()).post {
            try {
                MainActivity.bookmarksUpdatedEvent(true)
                MainActivity.reloadLibraryEvent(true)
                ResultFragment.updateUI()
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    // ---- local data ---------------------------------------------------------------

    private fun readLocal(context: Context): Map<String, Any> {
        val out = HashMap<String, Any>()
        context.getSharedPrefs().all.forEach { (k, v) ->
            if (v != null && BackupUtils.isTransferableKey(k)) out["d:$k"] = v
        }
        context.getDefaultSharedPrefs().all.forEach { (k, v) ->
            if (v != null && BackupUtils.isTransferableKey(k)) out["s:$k"] = v
        }
        return out
    }

    private fun encode(value: Any): String? {
        val json = JSONObject()
        when (value) {
            is Boolean -> json.put("t", "b").put("v", value)
            is Int -> json.put("t", "i").put("v", value)
            is Long -> json.put("t", "l").put("v", value)
            is Float -> json.put("t", "f").put("v", value.toDouble())
            is String -> json.put("t", "s").put("v", value)
            is Set<*> -> {
                val strings = value.filterIsInstance<String>()
                if (strings.size != value.size) return null
                json.put("t", "ss").put("v", JSONArray(strings.sorted()))
            }
            else -> return null
        }
        return json.toString()
    }

    private fun applyValue(editor: SharedPreferences.Editor, key: String, encoded: String) {
        val json = JSONObject(encoded)
        when (json.getString("t")) {
            "b" -> editor.putBoolean(key, json.getBoolean("v"))
            "i" -> editor.putInt(key, json.getInt("v"))
            "l" -> editor.putLong(key, json.getLong("v"))
            "f" -> editor.putFloat(key, json.getDouble("v").toFloat())
            "s" -> editor.putString(key, json.getString("v"))
            "ss" -> {
                val array = json.getJSONArray("v")
                editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
            }
        }
    }

    private fun hash(encoded: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun writeSafetyBackup(context: Context) {
        try {
            val json = JSONObject()
            readLocal(context).forEach { (k, v) -> encode(v)?.let { json.put(k, it) } }
            File(context.filesDir, "sync_pre_bootstrap_backup.json").writeText(json.toString())
        } catch (e: Exception) {
            logError(e)
        }
    }

    // ---- push ---------------------------------------------------------------------

    private fun push(context: Context, cfg: Config, state: Meta, deviceId: String) {
        val nowMs = System.currentTimeMillis()
        val full = fullScanDue || nowMs - lastFullScan >= FULL_SCAN_INTERVAL_MS
        val pluginsDue = full || nowMs - lastPluginCheck >= PLUGIN_CHECK_INTERVAL_MS
        val dirty: Set<String> =
            if (full) emptySet() else dirtyKeys.toSet().also { dirtyKeys.removeAll(it) }
        if (!full && dirty.isEmpty() && !pluginsDue) return

        try {
            pushChanges(context, cfg, state, deviceId, full, pluginsDue, dirty)
            if (full) {
                fullScanDue = false
                lastFullScan = nowMs
            }
            if (pluginsDue) lastPluginCheck = nowMs
        } catch (e: Exception) {
            // Nothing is lost: look at the same keys again next cycle.
            dirtyKeys.addAll(dirty)
            if (full) fullScanDue = true
            throw e
        }
    }

    private fun pushChanges(
        context: Context,
        cfg: Config,
        state: Meta,
        deviceId: String,
        full: Boolean,
        pluginsDue: Boolean,
        dirty: Set<String>,
    ) {
        val now = System.currentTimeMillis() + state.clockOffset
        val local = HashMap<String, String>()
        if (full) {
            readLocal(context).forEach { (k, v) -> encode(v)?.let { local[k] = it } }
        } else if (dirty.isNotEmpty()) {
            val dataAll = context.getSharedPrefs().all
            val settingsAll = context.getDefaultSharedPrefs().all
            for (key in dirty) {
                val name = key.substring(2)
                val value = (if (key.startsWith("d:")) dataAll else settingsAll)[name]
                if (value != null && BackupUtils.isTransferableKey(name)) {
                    encode(value)?.let { local[key] = it }
                }
            }
        }
        if (pluginsDue) local.putAll(localPluginEntries(context))

        val outgoing = ArrayList<Entry>()
        val newStates = HashMap<String, KeyState>()
        for ((key, encoded) in local) {
            val h = hash(encoded)
            if (state.keys[key]?.hash != h) {
                outgoing += Entry(key, encoded, false, now)
                newStates[key] = KeyState(h, now)
            }
        }

        // Which known keys might have disappeared?
        val candidates: Collection<String> = when {
            full -> state.keys.keys.toList()
            else -> dirty + (if (pluginsDue) state.keys.keys.filter { it.startsWith("p:") } else emptyList())
        }
        for (key in candidates) {
            val known = state.keys[key] ?: continue
            // A plugin counts as present as long as its file exists, even if its repository
            // is not (yet) in the local repository list.
            val present = if (key.startsWith("p:")) pluginFile(context, key)?.exists() == true
            else key in local
            if (!present && known.hash != DELETED) {
                outgoing += Entry(key, null, true, now)
                newStates[key] = KeyState(DELETED, now)
            }
        }
        if (outgoing.isEmpty()) return

        var batch = ArrayList<Entry>()
        var chars = 0
        fun flush() {
            if (batch.isEmpty()) return
            postBatch(cfg, deviceId, batch)
            val pushedAt = System.currentTimeMillis()
            batch.forEach {
                state.keys[it.key] = newStates.getValue(it.key)
                if (!it.deleted) ownWrites[it.key] = pushedAt
            }
            batch = ArrayList()
            chars = 0
        }
        for (entry in outgoing) {
            val size = entry.key.length + (entry.value?.length ?: 0)
            if (batch.size >= MAX_PUSH_ENTRIES || (chars + size > MAX_PUSH_CHARS && batch.isNotEmpty())) flush()
            batch += entry
            chars += size
        }
        flush()
    }

    private fun postBatch(cfg: Config, deviceId: String, entries: List<Entry>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject()
                    .put("key", e.key)
                    .put("value", e.value ?: JSONObject.NULL)
                    .put("deleted", e.deleted)
                    .put("updatedAt", e.updatedAt)
            )
        }
        val body = JSONObject().put("deviceId", deviceId).put("entries", array).toString()
        val request = Request.Builder()
            .url("${cfg.url}/v1/changes")
            .header("Authorization", "Bearer ${cfg.token}")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("push failed: HTTP ${response.code}")
        }
    }

    // ---- pull ---------------------------------------------------------------------

    private fun pull(context: Context, cfg: Config, state: Meta, unconditional: Boolean) {
        var more: Boolean
        do {
            val since = maxOf(0L, state.cursor - CURSOR_OVERLAP)
            val request = Request.Builder()
                .url("${cfg.url}/v1/changes?since=$since")
                .header("Authorization", "Bearer ${cfg.token}")
                .build()
            val text = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("pull failed: HTTP ${response.code}")
                response.body.string()
            }
            val json = JSONObject(text)
            state.clockOffset = json.optLong("now", 0L).takeIf { it > 0 }
                ?.let { it - System.currentTimeMillis() } ?: state.clockOffset

            val array = json.getJSONArray("entries")
            val entries = (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Entry(
                    o.getString("key"),
                    if (o.isNull("value")) null else o.getString("value"),
                    o.getBoolean("deleted"),
                    o.getLong("updatedAt"),
                    o.optString("deviceId"),
                )
            }
            val fresh = (0 until array.length()).count { array.getJSONObject(it).optLong("seq") > state.cursor }
            if (fresh > 0) {
                android.util.Log.d("SelfHostedSync", "pulled $fresh new entries (ws=$wsConnected)")
            }
            applyRemote(context, state, entries, unconditional)
            state.cursor = maxOf(state.cursor, json.optLong("cursor", state.cursor))
            more = json.optBoolean("more", false)
        } while (more)
    }

    private fun applyRemote(
        context: Context,
        state: Meta,
        entries: List<Entry>,
        unconditional: Boolean,
    ) {
        if (entries.isEmpty()) return
        val dataEditor = context.getSharedPrefs().edit()
        val settingsEditor = context.getDefaultSharedPrefs().edit()
        // Only read local values if an entry actually needs applying.
        val localNow by lazy { readLocal(context) }

        for (entry in entries) {
            if (entry.key.startsWith("p:")) {
                handlePluginEntry(context, state, entry, unconditional)
                continue
            }
            val scopeEditor = when {
                entry.key.startsWith("d:") -> dataEditor
                entry.key.startsWith("s:") -> settingsEditor
                else -> continue
            }
            val prefKey = entry.key.substring(2)
            if (!BackupUtils.isTransferableKey(prefKey)) continue

            val known = state.keys[entry.key]
            if (!unconditional && known != null && entry.updatedAt <= known.ts) continue

            // Watch position of a film this device is playing right now: a copy from another
            // device that is further back (it started from an old position) must not win just
            // because it was written later. Keep ours and send it again with a fresh time.
            if (!unconditional && !entry.deleted && entry.value != null &&
                prefKey.contains("video_pos_dur") && entry.deviceId != deviceId(context)
            ) {
                val ownAt = ownWrites[entry.key]
                if (ownAt != null && System.currentTimeMillis() - ownAt < OWN_WRITE_GUARD_MS) {
                    val remotePos = positionOf(entry.value)
                    val localPos = positionOf(localNow[entry.key]?.let { encode(it) })
                    if (remotePos != null && localPos != null && remotePos < localPos) {
                        state.keys[entry.key] = KeyState("", entry.updatedAt) // forces a push
                        dirtyKeys.add(entry.key)
                        wakeups.trySend(Unit)
                        continue
                    }
                }
            }

            if (entry.deleted || entry.value == null) {
                scopeEditor.remove(prefKey)
                if (isUiRelevant(prefKey)) uiRefreshNeeded = true
                state.keys[entry.key] = KeyState(DELETED, entry.updatedAt)
            } else {
                // Skip rewriting identical values.
                val currentEncoded = localNow[entry.key]?.let { encode(it) }
                if (currentEncoded != entry.value) {
                    try {
                        applyValue(scopeEditor, prefKey, entry.value)
                        if (isUiRelevant(prefKey)) uiRefreshNeeded = true
                    } catch (e: Exception) {
                        logError(e)
                        continue
                    }
                }
                state.keys[entry.key] = KeyState(hash(entry.value), entry.updatedAt)
                ownWrites.remove(entry.key)
            }
        }
        dataEditor.apply()
        settingsEditor.apply()
    }

    // ---- plugins ------------------------------------------------------------------
    // Plugin files live on the device, so they are synced as small "installed from repo X"
    // entries (key "p:<repo folder>/<plugin name>") and downloaded/removed locally.

    private fun allRepositories(): List<RepositoryData> =
        ((getKey<Array<RepositoryData>>(REPOSITORIES_KEY) ?: emptyArray()) +
            RepositoryManager.PREBUILT_REPOSITORIES).toList()

    /** Installed online plugins as sync entries: key -> encoded value. */
    private fun localPluginEntries(context: Context): Map<String, String> {
        val repos = allRepositories()
        val out = HashMap<String, String>()
        for (data in PluginManager.getPluginsOnline()) {
            if (!data.isOnline) continue
            val url = data.url ?: continue
            val file = File(data.filePath)
            if (!file.exists()) continue
            val folder = file.parentFile?.name ?: continue
            val repo = repos.firstOrNull {
                PluginManager.getPluginSanitizedFileName(it.url) == folder
            } ?: continue
            out["p:$folder/${data.internalName}"] = JSONObject()
                .put("n", data.internalName)
                .put("u", url)
                .put("r", repo.url)
                .toString()
        }
        return out
    }

    private fun pluginFile(context: Context, key: String): File? {
        val rest = key.removePrefix("p:")
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val folder = rest.substring(0, slash)
        val name = rest.substring(slash + 1)
        return File(
            context.filesDir,
            "${RepositoryManager.ONLINE_PLUGINS_FOLDER}/$folder/" +
                "${PluginManager.getPluginSanitizedFileName(name)}.cs3"
        )
    }

    private fun handlePluginEntry(
        context: Context,
        state: Meta,
        entry: Entry,
        unconditional: Boolean,
    ) {
        val known = state.keys[entry.key]
        if (!unconditional && known != null && entry.updatedAt <= known.ts) return
        val installed = pluginFile(context, entry.key)?.exists() == true

        if (entry.deleted || entry.value == null) {
            state.keys[entry.key] = KeyState(DELETED, entry.updatedAt)
            if (installed) state.pending[entry.key] = Pending(null, entry.updatedAt)
            else state.pending.remove(entry.key)
        } else if (installed) {
            state.pending.remove(entry.key)
            state.keys[entry.key] = KeyState(hash(entry.value), entry.updatedAt)
        } else {
            state.pending[entry.key] = Pending(entry.value, entry.updatedAt)
        }
    }

    /** Applies pending plugin installs/removals. Needs a foreground activity for downloads. */
    private suspend fun reconcilePlugins(context: Context) {
        val activity = CommonActivity.activity ?: return
        val state = synchronized(this) { loadMeta(context) }
        val work = synchronized(this) { state.pending.toMap() }
        if (work.isEmpty()) return
        val repoUrls = allRepositories().map { it.url }.toSet()

        for ((key, change) in work) {
            try {
                val file = pluginFile(context, key)
                if (change.value == null) {
                    if (file != null && file.exists()) PluginManager.deletePlugin(file)
                    synchronized(this) { state.pending.remove(key) }
                } else {
                    val json = JSONObject(change.value)
                    val repoUrl = json.getString("r")
                    // The repository list is itself synced; wait until it has arrived.
                    if (repoUrl !in repoUrls) continue
                    val ok = PluginManager.downloadPlugin(
                        activity, json.getString("u"), null, json.getString("n"), repoUrl, true
                    )
                    if (ok) synchronized(this) {
                        state.keys[key] = KeyState(hash(change.value), change.ts)
                        state.pending.remove(key)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }
        synchronized(this) { saveMeta(context, state) }
    }

    // ---- persisted bookkeeping ----------------------------------------------------

    private fun deviceId(context: Context): String {
        val prefs = metaPrefs(context)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private fun loadMeta(context: Context): Meta {
        meta?.let { return it }
        val prefs = metaPrefs(context)
        val keys = HashMap<String, KeyState>()
        prefs.getString("keys", null)?.let { stored ->
            try {
                val json = JSONObject(stored)
                json.keys().forEach { k ->
                    val parts = json.getString(k).split(":")
                    if (parts.size == 2) keys[k] = KeyState(parts[0], parts[1].toLong())
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
        val pending = HashMap<String, Pending>()
        prefs.getString("pending", null)?.let { stored ->
            try {
                val json = JSONObject(stored)
                json.keys().forEach { k ->
                    val o = json.getJSONObject(k)
                    pending[k] = Pending(
                        if (o.isNull("v")) null else o.getString("v"),
                        o.getLong("t"),
                    )
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
        return Meta(
            prefs.getLong("cursor", 0L),
            prefs.getLong("clock_offset", 0L),
            prefs.getBoolean("bootstrapped", false),
            keys,
            pending,
        ).also { meta = it }
    }

    private fun saveMeta(context: Context, state: Meta) {
        // Cheap fingerprint: skip serialising thousands of keys when nothing changed.
        var signature = state.cursor * 31 + state.keys.size * 17L + state.pending.size
        for ((k, v) in state.keys) signature += v.ts xor v.hash.hashCode().toLong() xor k.hashCode().toLong()
        if (signature == lastSavedSignature) return
        lastSavedSignature = signature
        val json = JSONObject()
        state.keys.forEach { (k, v) -> json.put(k, "${v.hash}:${v.ts}") }
        val pending = JSONObject()
        state.pending.forEach { (k, v) ->
            pending.put(k, JSONObject().put("v", v.value ?: JSONObject.NULL).put("t", v.ts))
        }
        metaPrefs(context).edit()
            .putString("pending", pending.toString())
            .putLong("cursor", state.cursor)
            .putLong("clock_offset", state.clockOffset)
            .putBoolean("bootstrapped", state.bootstrapped)
            .putString("keys", json.toString())
            .apply()
    }

    /** Wipes sync bookkeeping so the next run behaves like the first one. */
    fun resetState(context: Context) {
        metaPrefs(context).edit().clear().apply()
        meta = null
    }
}
