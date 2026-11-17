package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.vesper.data.Goal
import app.vesper.data.Lobe
import kotlin.math.abs
import kotlin.math.min
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

    private fun overlaps(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    /**
     * The learn brain, its orbit and its label touch nothing: no portal with its glow, no portal name (placed by the real [lobeNames]),
     * not the core, today strip, heart, its nerve or its name, for any portal count and the zooms the map settles at (phone to wide).
     */
    private fun assertClear(names: List<String>, rx: Float, k: Float) {
        val n = names.size
        val lobes = names.mapIndexed { i, s -> Lobe(id = i + 1L, name = s, colorIndex = i, slot = i, uid = "l$i") }
        val layout = buildLayout(lobes, Web(emptyList(), emptyList()), rx = rx)
        val portals = (0 until n).map { Offset(layout.lx[it], layout.ly[it]) }
        val p = learnSpot(portals, names.map { it.length })
        val ctx = "n $n rx $rx k $k spot $p"
        val sizes = names.map { Size(min(150f, it.length * NAME_CHAR_DP + 8f), LEARN_LABEL_H) }
        val labels = lobeNames(layout, sizes, k, 1f)
        fun dp(b: FloatArray) = floatArrayOf(b[0] * k, b[1] * k, b[2] * k, b[3] * k)
        val mine = listOf(dp(learnBrainBox(p)), dp(learnLabelBox(p, k)))
        val others = ArrayList<Pair<String, FloatArray>>()
        others.add("core" to floatArrayOf(-36f, -36f, 36f, 36f).let(::dp))
        others.add("strip" to floatArrayOf(-150f / k, 24f, 150f / k, 24f + 25f / k).let(::dp))
        others.add("nerve" to floatArrayOf(-8f, 36f, 8f, SUN_Y - HEART_MID).let(::dp))
        others.add("heart" to floatArrayOf(SUN_X + HEART_BOX[0], SUN_Y + HEART_BOX[1], SUN_X + HEART_BOX[2], SUN_Y + HEART_BOX[3]).let(::dp))
        val vw = 5 * NAME_CHAR_DP / 2f
        others.add("vault" to floatArrayOf(SUN_X * k - vw, (SUN_Y + 12f) * k, SUN_X * k + vw, (SUN_Y + 12f) * k + LEARN_LABEL_H))
        for (i in 0 until n) {
            val g = PORTAL_OUT * 1.7f
            others.add("portal $i" to dp(floatArrayOf(portals[i].x - g, portals[i].y - g, portals[i].x + g, portals[i].y + g)))
            labels[i]?.let { others.add("name $i" to floatArrayOf(it.x, it.y, it.x + sizes[i].width, it.y + sizes[i].height)) }
        }
        for ((what, b) in others) for (m in mine) assertTrue("$ctx overlaps $what ${b.toList()} mine ${m.toList()}", !overlaps(m, b))
    }

    @Test fun learnSpotKeepsClearOfEverythingForAnyPortalCount() {
        for (rx in listOf(58f, 63f, 100f, 190f)) for (k in listOf(1.8f, 2.3f, 3f)) for (n in 0..12)
            assertClear(List(n) { "Sub brain ${"x".repeat(it % 5)}$it" }, rx, k)
    }

    /** The owner's phone: seven portals, the learn brain used to sit on Coding. */
    @Test fun learnSpotOnTheSevenPortalPhoneMap() {
        val names = listOf("Music/Dances", "Learn", "Poetry", "My Father", "Personal", "Tasks", "Coding")
        for (k in listOf(1.8f, 2.3f, 2.6f)) assertClear(names, 58f, k)
        val p = learnSpot((0 until 7).map { ringPos(it, 7, 58f) }, names.map { it.length })
        assertTrue("beside the heart: $p", abs(p.y - (SUN_Y - HEART_MID)) <= 10f && abs(p.x) >= 30f)
    }
}
