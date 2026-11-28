package app.vesper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyTest {
    private fun at(h: Int, m: Int = 0) = skyRgb(h * 60 + m)

    @Test fun phasesHoldTheirColour() {
        assertEquals(0x05030A, at(23)); assertEquals(0x05030A, at(2)); assertEquals(0x05030A, at(5))
        assertEquals(0x1C1033, at(6)); assertEquals(0x1C1033, at(7, 30)); assertEquals(0x1C1033, at(8))
        assertEquals(0x2A1A4A, at(10)); assertEquals(0x2A1A4A, at(13)); assertEquals(0x2A1A4A, at(16))
        assertEquals(0x160B2A, at(18)); assertEquals(0x160B2A, at(19)); assertEquals(0x160B2A, at(20))
    }

    @Test fun blendsSmoothlyBetweenPhases() {
        val mid = at(9) // halfway dawn -> day
        assertTrue(mid != 0x1C1033 && mid != 0x2A1A4A)
        assertEquals(0x23, mid shr 16 and 255) // (1C + 2A) / 2
    }

    @Test fun neverJumpsMoreThanAStepPerMinute() {
        var prev = at(0)
        for (m in 1..1440) {
            val c = skyRgb(m)
            for (sh in intArrayOf(16, 8, 0)) assertTrue("jump at $m", Math.abs((c shr sh and 255) - (prev shr sh and 255)) <= 2)
            prev = c
        }
        assertEquals(skyRgb(0), skyRgb(1440)) // wraps
    }

    @Test fun lightestPhaseStaysDarkEnoughForLilacText() {
        // the brightest base channel stays far below the lilac (B69AFF) text, so contrast stays high
        for (m in 0 until 1440) assertTrue((skyRgb(m) shr 16 and 255) <= 0x2A)
    }
}
