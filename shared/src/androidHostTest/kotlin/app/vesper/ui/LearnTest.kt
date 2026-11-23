package app.vesper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Goals are just names now: the only rule left is how a goal dims when it is left alone. */
class LearnTest {
    @Test fun dimmingStartsAfterAWeekAndStopsAt45Percent() {
        val now = 100 * DAY_MS
        assertEquals(1f, goalBrightness(now, now))
        assertEquals(1f, goalBrightness(now - 7 * DAY_MS, now))
        assertEquals(1f - 0.55f / 3f, goalBrightness(now - 14 * DAY_MS, now), 1e-4f)
        assertEquals(0.45f, goalBrightness(now - 28 * DAY_MS, now), 1e-4f)
        assertEquals(0.45f, goalBrightness(now - 400 * DAY_MS, now), 1e-4f)
        assertEquals(1f, goalBrightness(now + DAY_MS, now)) // a clock ahead on another device
    }

    @Test fun aGoalNameMayBeAShortSentence() {
        assertTrue("room for 'Learn how my computer works'", GOAL_NAME_MAX >= "Learn how my computer works".length)
    }
}
