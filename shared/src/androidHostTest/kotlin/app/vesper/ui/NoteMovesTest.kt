package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.vesper.Settings
import app.vesper.data.Goal
import app.vesper.data.Idea
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.7.6: the brains sit still on a symmetric ring; notes are what the player drags, each saved per device by its uid. */
class NoteMovesTest {
    private class Mem : Settings {
        val m = HashMap<String, String>()
        override fun getString(key: String) = m[key]
        override fun putString(key: String, value: String) { m[key] = value }
        override fun getBoolean(key: String, default: Boolean) = default
        override fun putBoolean(key: String, value: Boolean) {}
    }

    @After fun home() = NoteMoves.d.clear()

    private fun task(i: Int) = Idea(id = i.toLong(), lobeId = 2, title = "task number $i", body = "", uid = "u$i", done = i % 4 == 0)
    private fun goal(i: Int) = Goal(id = i.toLong(), name = "Goal number $i", uid = "g$i", updatedAt = 0L)
    private fun pin(i: Int) = Idea(id = 300L + i, lobeId = 3, title = "pin $i", body = "", uid = "p$i")
    private fun note(i: Int) = Idea(id = 900L + i, lobeId = 1, title = "n$i", body = "", uid = "n$i")
    private fun overlaps(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    @Test fun dragPositionsSaveLoadAndReset() {
        val s = Mem()
        NoteMoves.move("u1", 30f, -12f); NoteMoves.move("u1", 1f, 2f); NoteMoves.move("g2", -5f, 7f)
        NoteMoves.save(s); NoteMoves.d.clear(); NoteMoves.load(s)
        assertEquals(Offset(31f, -10f), NoteMoves["u1"])
        assertEquals(Offset(-5f, 7f), NoteMoves["g2"])
        assertEquals(Offset.Zero, NoteMoves["never-moved"])
        NoteMoves.reset(s); NoteMoves.move("u1", 9f, 9f); NoteMoves.load(s)
        assertTrue("reset forgets every note's place", NoteMoves.d.isEmpty())
    }

    @Test fun brokenOrOldSavedValuesAreIgnored() {
        val s = Mem()
        s.m["brainMoves"] = "1,2,3,4,5,6,7,8,9,10" // where 2.7.5 kept the moved brains: never read again
        s.m[NoteMoves.KEY] = "junk;u1,3,4;u2,NaN,1;u3,1;,1,2;u4,1e9,-1e9"
        NoteMoves.load(s)
        assertEquals(mapOf("u1" to Offset(3f, 4f), "u4" to Offset(2000f, -2000f)), NoteMoves.d.toMap())
        s.m.remove(NoteMoves.KEY); NoteMoves.load(s)
        assertTrue(NoteMoves.d.isEmpty())
    }

    @Test fun burnedNotesDropTheirPlace() {
        NoteMoves.move("u1", 1f, 1f); NoteMoves.move("gone", 2f, 2f)
        assertTrue(NoteMoves.keepOnly(setOf("u1", "u7")))
        assertEquals(setOf("u1"), NoteMoves.d.keys)
        assertFalse("nothing more to drop", NoteMoves.keepOnly(setOf("u1")))
    }

    @Test fun aDraggedPaperSlidesAboutTheBoardButNeverOffIt() {
        val l = buildLayout(emptyList(), pinned = (1..5).map(::pin))
        val home = l.paperCell(1)
        NoteMoves.move("p2", 6f, -3f)
        assertEquals(listOf(home[0] + 6f, home[1] - 3f, home[2] + 6f, home[3] - 3f), l.paperCell(1).toList())
        NoteMoves.move("p2", 900f, 900f)
        val c = l.paperCell(1)
        assertEquals(BOARD_W / 2f - BOARD_FRAME, c[2], 1e-4f); assertEquals(BOARD_H / 2f - BOARD_FRAME, c[3], 1e-4f)
        assertEquals(paperOffset(l.paperAt[1], NoteMoves["p2"]), Offset(c[0] - l.paperAt[1][0], c[1] - l.paperAt[1][1]))
        assertTrue(onBoard(l.boardAt, l.boardAt + Offset(c[0], c[1])))
    }

    @Test fun theRingIsSymmetricAndTheHeartSitsStraightBelow() {
        assertEquals(Offset(-LEARN_AT.x, LEARN_AT.y), TASKS_AT)
        assertEquals(Offset(-RESERVED_AT[1].x, RESERVED_AT[1].y), RESERVED_AT[0])
        assertEquals(Offset(TASKS_AT.x, -TASKS_AT.y), RESERVED_AT[0])
        for (p in listOf(TASKS_AT, LEARN_AT) + RESERVED_AT) assertEquals(RING_R, p.getDistance(), 1e-3f)
        assertTrue("tasks bottom left, learn bottom right", TASKS_AT.x < 0f && TASKS_AT.y > 0f && LEARN_AT.x > 0f)
        assertTrue("reserved top left and top right", RESERVED_AT[0].x < 0f && RESERVED_AT[0].y < 0f && RESERVED_AT[1].x > 0f)
        assertEquals(0f, HEART_AT.x, 0f)
        assertTrue("heart inside or just under the ring: ${HEART_AT.y}", HEART_AT.y in RING_R - 20f..RING_R + 20f)
        // nothing fixed touches anything else fixed
        val fixed = listOf(floatArrayOf(-36f, -36f, 36f, 36f), floatArrayOf(HEART_AT.x + HEART_BOX[0], SUN_Y + HEART_BOX[1], HEART_AT.x + HEART_BOX[2], SUN_Y + HEART_BOX[3])) +
            (listOf(TASKS_AT, LEARN_AT) + RESERVED_AT).flatMap { listOf(learnBrainBox(it), learnLabelBox(it, HOME_K)) }
        for (a in fixed.indices) for (b in a + 1 until fixed.size) if (a < 2 || a / 2 != b / 2) assertFalse("fixed boxes $a and $b overlap", overlaps(fixed[a], fixed[b]))
    }

    @Test fun tasksAndGoalsScatterRoundTheirBrainsAndKeepOffTheReservedSpots() {
        val reserved = RESERVED_AT.map { floatArrayOf(it.x - 10f, it.y - 10f, it.x + 10f, it.y + 10f) } // a small circle round each kept free
        for (n in listOf(1, 6, 12, 20)) for (g in listOf(1, 6, 10)) {
            val l = buildLayout((1..n).map(::task), (0 until 20).map(::note), (1..g).map(::goal))
            val feet = l.tasks.indices.map { taskNodeBox(Offset(l.tx[it], l.ty[it])) } + l.goalAt.map(::moonBox)
            for (f in feet) for (r in reserved) assertFalse("n=$n g=$g: a note on a reserved spot", overlaps(f, r))
            for (f in feet) assertFalse("n=$n g=$g: a note on the board", overlaps(f, boardBox(l.boardAt)))
            for (i in l.tasks.indices) assertTrue("tasks keep to the left half", feet[i][2] <= 0f)
            for (i in l.goals.indices) assertTrue("goals keep to the right half", feet[l.tasks.size + i][0] >= 0f)
        }
        // all sides: with a dozen tasks some sit above the tasks brain and some below it, likewise the goals round the learn brain
        val l = buildLayout((1..12).map(::task), (0 until 20).map(::note), (1..6).map(::goal))
        assertTrue((0 until 12).any { l.ty[it] < TASKS_AT.y - 15f } && (0 until 12).any { l.ty[it] > TASKS_AT.y + 15f })
        assertTrue((0 until 6).any { l.goalAt[it].y < LEARN_AT.y - 10f } && (0 until 6).any { l.goalAt[it].y > LEARN_AT.y + 10f })
    }

    /** The home view: the whole map and the whole board fit a ~390dp phone at a readable size, with no task or goal names showing. */
    @Test fun theHomeFitHoldsTheWholeBoard() {
        val pxdp = 2.28f; val w = 390f; val h = 700f; val top = 76f
        val l = realMap()
        val aim = fitOverview({ z -> overviewBoxes(l, Size(55f, 28f), z, pxdp) }, w, h, top, pxdp, NODE_ZOOM, 0.3f)
        val k = pxdp * aim[2]
        val b = boardBox(l.boardAt)
        val left = (b[0] - aim[0]) * k + w / 2; val right = (b[2] - aim[0]) * k + w / 2
        val upper = (b[1] - aim[1]) * k + h / 2; val lower = (b[3] - aim[1]) * k + h / 2
        assertTrue("board in view at z=${aim[2]}: $left..$right x $upper..$lower", left >= 0f && right <= w && upper >= top && lower <= h)
        assertTrue("brains, board and papers at a readable size: ${k}dp per unit", k >= HOME_K * 0.95f)
    }

    private val realNames = listOf(
        "Kurumi update", "Singing learner App", "Job Searching", "Anzori's phone", "Vision Glasses", "ბანკში წასვლა და ქვითარი", "Renew passport",
        "Dentist on friday", "Pay the internet bill", "Call the tailor", "Fix the bike", "Book train tickets", "Clean the flat", "Email landlord", "Water plants",
    )
    private val realGoals = listOf("Panduri", "Spanish", "Run 5k", "Learn how my computer works", "Read the Bible", "Drawing", "Georgian grammar", "Chess")
    private fun realMap() = buildLayout(
        realNames.mapIndexed { i, n -> Idea(id = i + 1L, lobeId = 2, title = n, body = "", uid = "task-$i", done = i % 6 == 5) },
        (0 until 20).map(::note),
        realGoals.mapIndexed { i, n -> Goal(id = i + 1L, name = n, uid = "goal-$i", updatedAt = 0L) },
        (1..5).map(::pin),
    )

    /** 2.7.7: the tasks are a round flower of diamonds round the tasks brain, on the left, above and below it, none on another or on anything fixed. */
    @Test fun tasksFormARoundFlower() {
        val l = realMap()
        val nodes = l.tasks.indices.map { Offset(l.tx[it], l.ty[it]) }
        val w = nodes.maxOf { it.x } - nodes.minOf { it.x }; val h = nodes.maxOf { it.y } - nodes.minOf { it.y }
        assertTrue("round: $w wide, $h tall", h <= 1.3f * w && w <= 1.3f * h)
        assertTrue("left of it", nodes.any { it.x < TASKS_AT.x - 20f })
        assertTrue("above it", nodes.any { it.y < TASKS_AT.y - 20f })
        assertTrue("below it", nodes.any { it.y > TASKS_AT.y + 20f })
        assertTrue("compact", nodes.all { (it - TASKS_AT).getDistance() < 110f })
        val home = nodes.map(::taskNodeBox)
        for (a in home.indices) {
            for (b in a + 1 until home.size) assertFalse("diamonds $a and $b touch", overlaps(home[a], home[b]))
            for (f in hardBoxes(l.noteAt) + labelBoxes() + listOf(boardBox(l.boardAt)) + l.goalAt.map(::moonBox)) assertFalse("diamond $a on ${f.toList()}", overlaps(home[a], f))
        }
    }

    @Test fun goalsFormTheMirrorFlowerRoundLearn() {
        val l = realMap()
        val moons = l.goalAt
        assertEquals(8, moons.size)
        val w = moons.maxOf { it.x } - moons.minOf { it.x }; val h = moons.maxOf { it.y } - moons.minOf { it.y }
        assertTrue("round: $w wide, $h tall", h <= 1.3f * w && w <= 1.3f * h)
        assertTrue(moons.any { it.x > LEARN_AT.x + 20f } && moons.any { it.y < LEARN_AT.y - 20f } && moons.any { it.y > LEARN_AT.y + 20f })
        val boxes = moons.map(::moonBox)
        for (a in boxes.indices) for (b in a + 1 until boxes.size) assertFalse("moons $a and $b", overlaps(boxes[a], boxes[b]))
        // the same items round each brain land in mirror spots
        val fixed = hardBoxes(emptyList()) + labelBoxes()
        val t = ringPlace(10, TASKS_AT, ::moonBox, fixed, maxX = -GOAL_MIN_X)
        val g = ringPlace(10, LEARN_AT, ::moonBox, fixed, minX = GOAL_MIN_X)
        for (i in 0 until 10) { assertEquals(-t[i].x, g[i].x, 1e-3f); assertEquals(t[i].y, g[i].y, 1e-3f) }
    }

    /** The flowers are decoration: a tap on a task's diamond or a goal's moon opens nothing; a tap on a paper or a spark does. */
    @Test fun tasksAndGoalsAreNoTapTargets() {
        val l = realMap()
        for (i in l.tasks.indices) assertNull("task $i", l.noteHit(Offset(l.tx[i], l.ty[i]), 7f))
        for (g in l.goalAt) assertNull("goal at $g", l.noteHit(g, 7f))
        assertEquals(l.notes[0], l.noteHit(l.noteAt[0], 7f)?.first)
        val c = l.paperCell(0)
        assertEquals(l.pinned[0], l.noteHit(l.boardAt + Offset((c[0] + c[2]) / 2f, (c[1] + c[3]) / 2f), 7f)?.first)
    }
}
