package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import app.vesper.data.Idea
import app.vesper.data.Lobe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pinned-notes corkboard: its lobe is kept apart from the main brain, its papers show the titles, its place is clear of the rest of the map. */
class BrainBoardTest {
    @After fun home() = NoteMoves.d.clear()

    private val lobes = listOf(
        Lobe(id = 1, name = "", colorIndex = 0, slot = 0, uid = "l1"), Lobe(id = 2, name = "Tasks", colorIndex = 1, slot = 1, uid = "l2"),
        Lobe(id = 3, name = " pinned ", colorIndex = 2, slot = 2, uid = "l3"), Lobe(id = 4, name = "Coding", colorIndex = 3, slot = 3, uid = "l4"),
    )
    private fun idea(id: Long, lobe: Long, title: String, at: Long = id) = Idea(id = id, lobeId = lobe, title = title, body = "", uid = "i$id", updatedAt = at)
    private val ideas = listOf(idea(1, 1, "loose"), idea(2, 2, "a task"), idea(3, 3, "milk run", 30), idea(4, 3, "call mum", 40), idea(5, 4, "old coding"))

    @Test fun pinnedNotesStayOutOfTheMainBrain() {
        assertEquals(listOf(5L, 1L), mainBrainNotes(lobes, ideas).map { it.id })
        assertEquals(listOf(4L, 3L), pinnedNotes(lobes, ideas).map { it.id })
        assertEquals("pinned", placeName(lobes[2]))
        // the sparks round the main brain are built from the main notes only
        val l = buildLayout(emptyList(), mainBrainNotes(lobes, ideas), pinned = pinnedNotes(lobes, ideas))
        assertEquals(listOf(5L, 1L), l.notes.map { it.id })
        assertEquals(listOf(4L, 3L), l.pinned.map { it.id })
    }

    @Test fun searchFindsPinnedNotes() {
        val hit = searchLibrary(lobes, ideas, "mum").single()
        assertEquals("call mum", hit.label); assertEquals("pinned", hit.where); assertEquals(3L, hit.lobe.id)
    }

    @Test fun pickersKnowPinned() {
        assertEquals(listOf(0, 1, 2, 0), lobes.map { placeIndex(it) })
        assertEquals(listOf("", "Tasks", "Pinned"), PLACE_NAMES)
    }

    @Test fun papersCarryTheNewestTitlesAndTheRestIsAPlusN() {
        val many = (1..14).map { idea(it.toLong(), 3, "note $it", at = it.toLong()) }.reversed() // newest first
        val l = buildLayout(emptyList(), pinned = many)
        assertEquals(BOARD_MAX, l.pinned.size)
        assertEquals(14, l.pinnedTotal)
        assertEquals((14 downTo 3).map { "note $it" }, l.pinned.map(::ideaLabel))
    }

    /** 2.7.7: each paper at its own random spot on the cork, seeded by its uid; apart while there is room, never off the cork. */
    @Test fun papersScatterOnTheCorkSeededByUid() {
        val inner = floatArrayOf(-BOARD_W / 2f + BOARD_FRAME, -BOARD_H / 2f + BOARD_FRAME, BOARD_W / 2f - BOARD_FRAME, BOARD_H / 2f - BOARD_FRAME)
        assertEquals(paperSpots(listOf("abc"))[0].toList(), paperSpots(listOf("abc"))[0].toList())
        assertEquals("an older paper keeps its spot when a newer one comes", paperSpots(listOf("a1", "a2"))[0].toList(), paperSpots(listOf("a1", "a2", "a3"))[0].toList())
        assertFalse("other uids, other spots", paperSpots(listOf("abc"))[0].contentEquals(paperSpots(listOf("xyz"))[0]))
        for (set in 0 until 30) for (n in 1..BOARD_MAX) {
            val ps = paperSpots((1..n).map { "s$set-$it" })
            for (c in ps) assertTrue("set $set n=$n: off the cork", c[0] >= inner[0] && c[2] <= inner[2] && c[1] >= inner[1] && c[3] <= inner[3])
            if (n <= 3) for (a in ps.indices) for (b in a + 1 until ps.size) {
                val p = ps[a]; val q = ps[b]
                assertFalse("set $set n=$n: papers $a and $b overlap with room to spare", p[0] < q[2] && q[0] < p[2] && p[1] < q[3] && q[1] < p[3])
            }
        }
        // the layout keeps the newest first, the spots placed oldest first
        val pins = (1..5).map { idea(it.toLong(), 3, "p$it") }.reversed()
        assertEquals(paperSpots(pins.reversed().map { it.uid }).reversed().map { it.toList() }, buildLayout(emptyList(), pinned = pins).paperAt.map { it.toList() })
    }

    private fun task(i: Int) = Idea(id = i.toLong(), lobeId = 2, title = "task number $i", body = "", uid = "u$i", done = i % 4 == 0)
    private fun goal(i: Int) = app.vesper.data.Goal(id = i.toLong(), name = "Goal $i", uid = "g$i", updatedAt = 0L)

    @Test fun boardSitsCentredBelowEverythingWithAClearGap() {
        val notes = (0 until NOTE_CAP).map { Idea(id = 900L + it, lobeId = 1, title = "n$it", body = "", uid = "n$it") }
        for (n in listOf(0, 1, 4, 9, 15, 25)) for (g in listOf(0, 6, 10)) {
            val l = buildLayout((1..n).map(::task), notes, (1..g).map(::goal))
            val board = boardBox(l.boardAt)
            assertEquals(0f, l.boardAt.x, 0f)
            assertTrue("n=$n g=$g: below the vault name", board[1] >= BOARD_MIN_TOP)
            for (i in l.tasks.indices) assertTrue("n=$n: task $i reaches the board", taskNodeBox(Offset(l.tx[i], l.ty[i]))[3] + 8f <= board[1])
            for (i in l.goals.indices) assertTrue("g=$g: goal $i reaches the board", moonBox(l.goalAt[i])[3] + 8f <= board[1])
            for (b in fixedBoxes(l.noteAt)) assertTrue("n=$n: board on ${b.toList()}", b[3] + 8f <= board[1])
        }
    }

    @Test fun taskAndGoalPlacementIgnoresTheBoard() {
        val notes = (0 until 12).map { Idea(id = 900L + it, lobeId = 1, title = "n$it", body = "", uid = "n$it") }
        val tasks = (1..9).map(::task); val goals = (1..3).map(::goal)
        val plain = buildLayout(tasks, notes, goals)
        val pinned = buildLayout(tasks, notes, goals, (1..14).map { idea(it.toLong(), 3, "p$it") })
        assertEquals(plain.tx.toList(), pinned.tx.toList()); assertEquals(plain.ty.toList(), pinned.ty.toList())
        assertEquals(plain.goalAt, pinned.goalAt)
    }

    @Test fun hitTests() {
        val c = Offset(0f, 300f)
        assertTrue(onBoard(c, c)); assertFalse(onBoard(c, Offset(0f, 0f)))
        assertTrue("the +N tag hangs under the frame", onBoard(c, Offset(BOARD_W / 2f - 19f, 300f + BOARD_H / 2f + 4f)))
        assertFalse(onBoard(c, Offset(-BOARD_W, 300f)))
    }

    @Test fun papersAreTiltedEachTheirOwnWayAndNeverPink() {
        val tilts = (1..20).map { paperTilt("uid-$it") }
        assertTrue(tilts.all { kotlin.math.abs(it) in 0.025f..0.0851f })
        assertTrue("some lean each way", tilts.any { it < 0 } && tilts.any { it > 0 })
        assertEquals(paperTilt("same"), paperTilt("same"), 0f)
        for (rgb in PAPER_RGB) assertTrue("lilac or white", (rgb shr 16 and 255) >= 0xD6 && (rgb and 255) >= 0xFF - 0x29 && (rgb shr 8 and 255) >= 0xC8 && (rgb and 255) >= (rgb shr 16 and 255))
    }
}
