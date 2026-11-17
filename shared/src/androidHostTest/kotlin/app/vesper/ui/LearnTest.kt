package app.vesper.ui

import app.vesper.data.Goal
import app.vesper.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LearnTest {
    private val g1 = Goal(id = 1, name = "Panduri", position = 0, updatedAt = 0)
    private val g2 = Goal(id = 2, name = "Spanish", position = 1, updatedAt = 0)
    private fun step(id: Long, goal: Long, pos: Int, done: Boolean = false, day: Long? = null, at: Long = 0) =
        Step(id = id, goalId = goal, title = "s$id", position = pos, done = done, day = day, updatedAt = at)

    @Test fun progressIsDoneOverAll() {
        assertEquals(0f, goalProgress(emptyList()))
        assertEquals(0.25f, goalProgress(listOf(step(1, 1, 0, done = true), step(2, 1, 1), step(3, 1, 2), step(4, 1, 3))))
        assertEquals(1f, goalProgress(listOf(step(1, 1, 0, done = true))))
    }

    @Test fun dimmingStartsAfterAWeekAndStopsAt45Percent() {
        val now = 100 * DAY_MS
        assertEquals(1f, goalBrightness(now, now))
        assertEquals(1f, goalBrightness(now - 7 * DAY_MS, now))
        assertEquals(1f - 0.55f / 3f, goalBrightness(now - 14 * DAY_MS, now), 1e-4f)
        assertEquals(0.45f, goalBrightness(now - 28 * DAY_MS, now), 1e-4f)
        assertEquals(0.45f, goalBrightness(now - 400 * DAY_MS, now), 1e-4f)
        assertEquals(1f, goalBrightness(now + DAY_MS, now)) // a clock ahead on another device
    }

    @Test fun anyStepWorkTouchesTheGoal() {
        val g = g1.copy(updatedAt = 5)
        assertEquals(5L, goalTouchedAt(g, emptyList()))
        assertEquals(9L, goalTouchedAt(g, listOf(step(1, 1, 0, at = 9), step(2, 1, 1, at = 3))))
    }

    @Test fun pathIsByPositionThenId() {
        val steps = listOf(step(5, 1, 2), step(3, 1, 0), step(9, 2, 0), step(4, 1, 0))
        assertEquals(listOf(3L, 4L, 5L), pathOf(steps, 1).map { it.id })
    }

    @Test fun dueIsUndoneTodayOrEarlierOldestFirst() {
        val today = 20000L
        val steps = listOf(
            step(1, 2, 0, day = today),          // today, second goal
            step(2, 1, 1, day = today),          // today, first goal, later on its path
            step(3, 1, 0, day = today - 3),      // overdue: first of all
            step(4, 1, 2, day = today + 1),      // tomorrow: not due
            step(5, 1, 3, day = today - 9, done = true), // done: never due
            step(6, 1, 4),                       // no day
            step(7, 99, 0, day = today - 20),    // goal unknown (deleted meanwhile)
            step(8, 1, 0, day = today),          // same day, goal and position as 2's goal's first: by position
        )
        assertEquals(listOf(3L, 8L, 2L, 1L), dueSteps(listOf(g1, g2), steps, today).map { it.id })
        assertTrue(dueSteps(listOf(g1), emptyList(), today).isEmpty())
    }

    @Test fun overdueAndDayLabels() {
        val today = java.time.LocalDate.of(2026, 11, 17).toEpochDay()
        assertTrue(isOverdue(step(1, 1, 0, day = today - 1), today))
        assertTrue(!isOverdue(step(1, 1, 0, day = today), today))
        assertTrue(!isOverdue(step(1, 1, 0, day = today - 1, done = true), today))
        assertTrue(!isOverdue(step(1, 1, 0), today))
        assertEquals("today", dayLabel(today, today))
        assertEquals("tomorrow", dayLabel(today + 1, today))
        assertEquals("Nov 20", dayLabel(today + 3, today))
        assertEquals("Nov 16", dayLabel(today - 1, today))
    }

    /** Lower right of the core, below the today strip, clear of every portal, for any ring the app can draw. */
    @Test fun learnSpotKeepsClearOfPortalsAndTheCore() {
        for (rx in listOf(58f, 63f, 100f, 190f)) for (n in 0..9) {
            val portals = (0 until n).map { ringPos(it, n, rx) }
            val p = learnSpot(portals)
            assertTrue("rx $rx n $n: $p", p.x >= 14f && p.y >= 50f) // lower right, below the today strip, clear of the heart's nerve
            for (q in portals) assertTrue("rx $rx n $n: $p too close to $q", hypot(p.x - q.x, p.y - q.y) >= 26f)
        }
    }
}
