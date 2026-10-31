package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.ui.WatchType

/**
 * When a title counts as watched, kept apart from the player so it can be tested on its own.
 *
 * - Under one minute: not added to the watch history (unless it was already there).
 * - Over three minutes: moved to the "Watching" folder.
 * - At 90 % or more of the last episode (or of a movie): moved to "Completed".
 */
object WatchHistoryRules {
    const val MIN_HISTORY_MS = 60_000L
    const val WATCHING_MS = 180_000L
    const val COMPLETED_PERCENT = 90

    /** False for a first, very short look at something: nothing is saved about it. */
    fun shouldRecord(positionMs: Long, alreadyInHistory: Boolean): Boolean =
        positionMs >= MIN_HISTORY_MS || alreadyInHistory

    /**
     * The folder a title should move to, or null to leave it where it is.
     * Folders the user picked on purpose (on hold, dropped) are only changed by finishing.
     */
    fun targetFolder(
        current: WatchType,
        positionMs: Long,
        percentage: Int,
        isLastEpisode: Boolean,
    ): WatchType? {
        if (isLastEpisode && percentage >= COMPLETED_PERCENT) {
            return if (current == WatchType.COMPLETED) null else WatchType.COMPLETED
        }
        if (positionMs < WATCHING_MS) return null
        return when (current) {
            WatchType.NONE, WatchType.PLANTOWATCH -> WatchType.WATCHING
            else -> null
        }
    }
}
