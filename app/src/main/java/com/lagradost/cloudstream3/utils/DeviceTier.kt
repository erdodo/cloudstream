package com.lagradost.cloudstream3.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Tells apart devices that need the lighter settings (older 32 bit TVs and boxes with about
 * 2 GB of RAM or less) and provides a dispatcher for work that must never compete with the UI.
 */
object DeviceTier {
    @Volatile
    private var cached: Boolean? = null

    fun isLowEnd(context: Context): Boolean =
        cached ?: compute(context.applicationContext).also { cached = it }

    private fun compute(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = info.totalMem / (1024 * 1024)
        val is32Bit = Build.SUPPORTED_64_BIT_ABIS.isEmpty()
        return am.isLowRamDevice || totalMb <= 2200 || is32Bit
    }

    /** Two background-priority threads: sync, speed pings and provider tests run here. */
    val backgroundDispatcher: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(2) { runnable ->
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }.apply { name = "cs3-background"; isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
