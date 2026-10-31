package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfHostedSyncTest {
    // How a PosDur is stored in a sync entry: {"t":"s","v":"<json of position and duration>"}
    private fun encoded(pos: Long, dur: Long) =
        """{"t":"s","v":"{\"position\":$pos,\"duration\":$dur}"}"""

    @Test
    fun readsThePositionOutOfAStoredValue() {
        assertEquals(2_115_000L, SelfHostedSync.positionOf(encoded(2_115_000, 6_107_000)))
        assertEquals(0L, SelfHostedSync.positionOf(encoded(0, 6_107_000)))
    }

    @Test
    fun otherOrBrokenValuesAreNotPositions() {
        assertNull(SelfHostedSync.positionOf(null))
        assertNull(SelfHostedSync.positionOf("not json"))
        assertNull(SelfHostedSync.positionOf("""{"t":"s","v":"hello"}"""))
        assertNull(SelfHostedSync.positionOf("""{"t":"b","v":true}"""))
    }
}
