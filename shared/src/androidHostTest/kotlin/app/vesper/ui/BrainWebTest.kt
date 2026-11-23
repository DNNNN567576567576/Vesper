package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import app.vesper.data.Goal
import app.vesper.data.Idea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** Where the main map puts things: the learn and tasks brains either side of the heart, the tasks scattered round the tasks brain, the goal moons round the learn brain. */
class BrainWebTest {
    private fun task(id: Long, done: Boolean = false) = Idea(id = id, lobeId = 1, title = "t$id", body = "", uid = "u$id", done = done)
    private fun overlaps(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
    private fun apart(a: FloatArray, b: FloatArray) = !overlaps(a, b)

    @Test fun openTasksComeFirstThenDoneOnesEachInTheOrderTheyCame() {
        val l = buildLayout(listOf(task(5, done = true), task(3), task(9), task(1, done = true), task(2)))
        assertEquals(listOf(2L, 3L, 9L, 1L, 5L), l.tasks.map { it.id })
    }

    private fun goal(id: Long, name: String) = Goal(id = id, name = name, uid = "g$id", updatedAt = 0L)
    private fun note(i: Int) = Idea(id = 500L + i, lobeId = 2, title = "note $i", body = "", uid = "n$i")
    private fun footOf(l: Layout, i: Int) = taskNodeBox(Offset(l.tx[i], l.ty[i])) // at home only the diamonds show

    @Test fun tasksScatterAllRoundTheTasksBrainNothingOnAnythingAndStayOnAPhoneWideMap() {
        for (n in listOf(0, 1, 2, 5, 9, 15, 20)) {
            val notes = (0 until 12).map(::note); val goals = listOf(goal(1, "Panduri"), goal(2, "Spanish for the whole summer trip"), goal(3, "Run 5k"))
            val l = buildLayout((1..n).map { task(it.toLong(), done = it % 4 == 0) }, notes, goals)
            val fixed = fixedBoxes(l.noteAt) + l.goalAt.map(::moonBox)
            for (i in 0 until n) {
                val f = footOf(l, i)
                assertTrue("n=$n: task $i leaves the phone-wide map ${f.toList()}", f[0] >= -X_MAX[0] && f[2] <= X_MAX[0])
                for (b in fixed) assertTrue("n=$n: task $i on ${b.toList()}", apart(f, b))
                for (j in i + 1 until n) assertTrue("n=$n: tasks $i and $j on each other", apart(f, footOf(l, j)))
            }
            val g = l.goalAt.map(::moonBox)
            for (a in g.indices) {
                for (b in fixedBoxes(l.noteAt)) assertTrue("n=$n: goal $a on ${b.toList()}", apart(g[a], b))
                for (b in a + 1 until g.size) assertTrue("n=$n: goals $a and $b on each other", apart(g[a], g[b]))
            }
        }
    }

    @Test fun tasksSitAtVariedAnglesAndDistancesOnMoreThanOneSide() {
        val l = buildLayout((1..15).map { task(it.toLong()) })
        val d = (0 until 15).map { hypot(l.tx[it] - TASKS_AT.x, l.ty[it] - TASKS_AT.y) }
        assertTrue("distances vary: ${d.max() - d.min()}", d.max() - d.min() > 25f)
        assertTrue("out to its left and on its heart side", (0 until 15).any { l.tx[it] < TASKS_AT.x - 20f } && (0 until 15).any { l.tx[it] > TASKS_AT.x })
        assertTrue("above and below it", (0 until 15).any { l.ty[it] < TASKS_AT.y - 20f } && (0 until 15).any { l.ty[it] > TASKS_AT.y + 20f })
    }

    @Test fun placementIsStableMarkingDoneOrAddingALaterTaskMovesNothing() {
        val ts = (1..9).map { task(it.toLong()) }
        fun spots(l: Layout) = l.tasks.indices.associate { l.tasks[it].uid to Offset(l.tx[it], l.ty[it]) }
        val base = spots(buildLayout(ts))
        assertEquals(base, spots(buildLayout(ts)))
        assertEquals(base, spots(buildLayout(ts.map { if (it.id == 3L) it.copy(done = true) else it })))
        val more = spots(buildLayout(ts + task(10)))
        for ((k, v) in base) assertEquals(v, more[k])
    }

    @Test fun mainSparkNamesAppearOnlyPastTheOverviewFit() {
        assertEquals(0f, sparkNameAlpha(0.85f, 0.85f), 0f)
        assertEquals(0f, sparkNameAlpha(0.85f * 1.15f, 0.85f), 1e-6f)
        assertEquals(0f, sparkNameAlpha(0.5f, 0.85f), 0f)
        assertEquals(1f, sparkNameAlpha(0.85f * 1.5f, 0.85f), 1e-6f)
        assertEquals(1f, sparkNameAlpha(6f, 0.85f), 0f)
        val mid = sparkNameAlpha(0.85f * 1.3f, 0.85f)
        assertTrue("fades in: $mid", mid > 0.2f && mid < 0.8f)
        assertEquals(0f, sparkNameAlpha(3f, 0f), 0f) // no fit known yet: no names
    }

    @Test fun theGoalMoonIsAPurplePixelDiscWithCratersAndNeverPink() {
        assertEquals(11, MOON_ROWS.size)
        assertTrue(MOON_ROWS.all { it.length == 11 })
        val used = MOON_ROWS.joinToString("").toSet() - '.'
        assertTrue("lit edge, dark side and craters: $used", used.containsAll(listOf('W', 'L', 'D', 'K', 'C')))
        for (ch in used) {
            val c = moonRgb(ch); val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            assertTrue("$ch is not purple, white or black: ${c.toString(16)}", c == 0xFFFFFF || c == 0 || (b >= r && b > g))
        }
    }

    /** The learn brain and the tasks brain with their orbits and labels touch nothing: not the core, today strip, heart, its nerve or its name, nor each other. */
    @Test fun bothSmallBrainsSitOnTheRingClearOfEverything() {
        for (k in listOf(1.44f, 1.8f, 2.3f, 3f, 4.8f)) {
            fun dp(b: FloatArray) = floatArrayOf(b[0] * k, b[1] * k, b[2] * k, b[3] * k)
            val others = ArrayList<Pair<String, FloatArray>>()
            others.add("core" to floatArrayOf(-36f, -36f, 36f, 36f).let(::dp))
            others.add("strip" to floatArrayOf(-150f / k, 24f, 150f / k, 24f + 25f / k).let(::dp))
            others.add("nerve" to floatArrayOf(-8f, 36f, 8f, SUN_Y - HEART_MID).let(::dp))
            others.add("heart" to floatArrayOf(SUN_X + HEART_BOX[0], SUN_Y + HEART_BOX[1], SUN_X + HEART_BOX[2], SUN_Y + HEART_BOX[3]).let(::dp))
            val vw = 5 * NAME_CHAR_DP / 2f
            others.add("vault" to floatArrayOf(SUN_X * k - vw, (SUN_Y + 12f) * k, SUN_X * k + vw, (SUN_Y + 12f) * k + LEARN_LABEL_H))
            val learn = listOf(dp(learnBrainBox(LEARN_AT)), dp(learnLabelBox(LEARN_AT, k)))
            val tasks = listOf(dp(learnBrainBox(TASKS_AT)), dp(learnLabelBox(TASKS_AT, k)))
            for ((what, b) in others) for (m in learn + tasks) assertTrue("k $k: a small brain overlaps the $what", !overlaps(m, b))
            for (a in learn) for (b in tasks) assertTrue("k $k: the two small brains overlap", !overlaps(a, b))
        }
        assertEquals(Offset(-LEARN_AT.x, LEARN_AT.y), TASKS_AT)
        assertTrue("on the ring: ${LEARN_AT.getDistance()}", abs(LEARN_AT.getDistance() - RING_R) < 1e-3f)
    }
}
