package app.vesper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The main brain's sky: one even spread of stars, nothing on anything, in the order given. */
class BrainCardTest {
    private fun seeds(n: Int, base: Int) = IntArray(n) { (base * 7919 + it * 104729).hashCode() }
    private fun apart(a: FloatArray, b: FloatArray) = a[0] >= b[2] || a[2] <= b[0] || a[1] >= b[3] || a[3] <= b[1]

    @Test fun nothingLandsOnAnythingAndEverythingStaysInTheSky() {
        val rnd = Random(4)
        for (w in listOf(340f, 389f, 700f, 1100f, 1380f)) for (round in 0 until 40) {
            val s = seeds(rnd.nextInt(1, if (round % 5 == 0) 200 else 20), round)
            val sky = skyLayout(s, w)
            val ctx = "w $w round $round n ${s.size}"
            assertEquals(ctx, s.size, sky.stars.size)
            val hw = maxOf(STAR_REACH, sky.titleW / 2f)
            val boxes = sky.stars.map { floatArrayOf(it.x - hw, it.y - STAR_REACH, it.x + hw, it.y + STAR_TITLE_GAP + STAR_TITLE_H) }
            for (b in boxes) assertTrue("$ctx: ${b.toList()} leaves the sky", b[0] >= 0f && b[2] <= w && b[1] >= 0f && b[3] <= sky.h)
            for (i in boxes.indices) for (j in i + 1 until boxes.size) assertTrue("$ctx: ${boxes[i].toList()} on ${boxes[j].toList()}", apart(boxes[i], boxes[j]))
            assertEquals(ctx, sky.stars, skyLayout(s, w).stars) // the same notes always land in the same places
        }
    }

    @Test fun starsFillTheRowsInOrderNewestFirst() {
        val sky = skyLayout(seeds(9, 1), 389f)
        val cols = sky.stars.indices.first { it > 0 && sky.stars[it].x < sky.stars[it - 1].x }
        assertTrue("several columns on a phone", cols >= 3)
        assertTrue("the next row starts below the first", sky.stars[cols].y > sky.stars[0].y + 40f)
    }

    @Test fun mainBrainNoteSpotsAreRingsAboveEverythingElseAndCapped() {
        val core = floatArrayOf(-36f, -36f, 36f, 36f)
        for (n in listOf(0, 1, 3, 12, 40)) {
            val spots = noteSpots(List(n) { "uid$it" })
            assertEquals(n, spots.size)
            for (s in spots) {
                assertTrue("n=$n: $s is on the main brain", s.getDistance() > 48f)
                assertTrue("n=$n: $s dips below the main brain's sides", s.y < 14f)
                assertTrue("n=$n: $s reaches the heart, learn or tasks brains", s.y + 4f < SUN_Y - HEART_MID - 30f)
            }
            for (i in spots.indices) for (j in i + 1 until spots.size) assertTrue("n=$n: $i and $j sit on each other", (spots[i] - spots[j]).getDistance() > 4f)
            assertTrue(core.isNotEmpty())
        }
        val l = buildLayout(emptyList(), List(200) { app.vesper.data.Idea(id = it.toLong(), lobeId = 1, title = "n$it", body = "", uid = "u$it") })
        assertEquals(NOTE_CAP, l.notes.size)
        assertEquals(NOTE_CAP, l.noteAt.size)
    }
}
