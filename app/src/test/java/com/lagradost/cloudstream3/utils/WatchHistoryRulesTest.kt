package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.ui.WatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryRulesTest {
    private val sec = 1_000L

    @Test
    fun underOneMinuteIsNotRecordedUnlessAlreadyInHistory() {
        assertFalse(WatchHistoryRules.shouldRecord(59 * sec, alreadyInHistory = false))
        assertTrue(WatchHistoryRules.shouldRecord(60 * sec, alreadyInHistory = false))
        assertTrue(WatchHistoryRules.shouldRecord(10 * sec, alreadyInHistory = true))
    }

    @Test
    fun overThreeMinutesMovesNewOrPlannedTitlesToWatching() {
        assertNull(WatchHistoryRules.targetFolder(WatchType.NONE, 179 * sec, 5, false))
        assertEquals(WatchType.WATCHING, WatchHistoryRules.targetFolder(WatchType.NONE, 180 * sec, 5, false))
        assertEquals(WatchType.WATCHING, WatchHistoryRules.targetFolder(WatchType.PLANTOWATCH, 400 * sec, 20, false))
    }

    @Test
    fun foldersChosenByHandAreKeptWhileWatching() {
        for (kept in listOf(WatchType.ONHOLD, WatchType.DROPPED, WatchType.COMPLETED, WatchType.WATCHING)) {
            assertNull(WatchHistoryRules.targetFolder(kept, 600 * sec, 50, false))
        }
    }

    @Test
    fun finishingTheLastEpisodeOrMovieCompletes() {
        assertEquals(WatchType.COMPLETED, WatchHistoryRules.targetFolder(WatchType.WATCHING, 5400 * sec, 90, true))
        assertEquals(WatchType.COMPLETED, WatchHistoryRules.targetFolder(WatchType.NONE, 5400 * sec, 95, true))
        assertEquals(WatchType.COMPLETED, WatchHistoryRules.targetFolder(WatchType.ONHOLD, 5400 * sec, 99, true))
        assertNull(WatchHistoryRules.targetFolder(WatchType.COMPLETED, 5400 * sec, 99, true))
    }

    @Test
    fun finishingAnEpisodeInTheMiddleOfASeriesDoesNotComplete() {
        // 90 % of episode 3 of 10: the series is still being watched.
        assertEquals(WatchType.WATCHING, WatchHistoryRules.targetFolder(WatchType.NONE, 2400 * sec, 92, false))
        assertNull(WatchHistoryRules.targetFolder(WatchType.WATCHING, 2400 * sec, 92, false))
    }

    @Test
    fun justUnderNinetyPercentIsNotCompleted() {
        assertEquals(WatchType.WATCHING, WatchHistoryRules.targetFolder(WatchType.NONE, 5000 * sec, 89, true))
    }
}
