package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import app.vesper.data.Goal
import app.vesper.data.Idea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** 2.8.0: taps on the Brain tab fire at once (no double-tap anywhere on the map or in the small brains' worlds), and the zoom names of the flowers. */
class MapTapTest {
    private fun src(name: String) = listOf(File("src/commonMain/kotlin/app/vesper/ui/$name"), File("shared/src/commonMain/kotlin/app/vesper/ui/$name")).first { it.exists() }.readText()

    /**
     * A tap detector with onDoubleTap holds every single tap back for the double-tap timeout (300 ms): that was the Brain tab's slowness.
     * The map, the brain card, the Daily card and the learn and tasks worlds must use single-tap detection only.
     */
    @Test fun noDoubleTapDetectorOnTheBrainTab() {
        for (f in listOf("Brain.kt", "BrainCard.kt", "DailyCard.kt", "Worlds.kt", "BrainLabels.kt")) {
            val code = src(f)
            assertFalse("$f still waits for a double-tap", code.contains("onDoubleTap") || code.contains("tapOrDoubleTap"))
        }
    }

    /** Every brain a single tap can reach is reached by mapHit alone, with no second tap to wait for. */
    @Test fun aSingleTapReachesEveryBrain() {
        val l = buildLayout(emptyList(), pinned = listOf(Idea(id = 9, lobeId = 3, title = "p", body = "", uid = "p")))
        val u = 2.28f * 2.75f * 0.6f; val dp = 2.75f
        fun tap(w: Offset) = l.mapHit(w, u, nodeScale(0.6f), dp, daily = true, bible = true).first
        assertEquals(MapHit.Brain, tap(Offset.Zero))
        assertEquals(MapHit.Tasks, tap(TASKS_AT))
        assertEquals(MapHit.Learn, tap(LEARN_AT))
        assertEquals(MapHit.Daily, tap(DAILY_AT))
        assertEquals(MapHit.Bible, tap(BIBLE_AT))
        assertEquals(MapHit.Heart, tap(HEART_AT))
        assertTrue(tap(l.boardAt) == MapHit.Board || tap(l.boardAt) == MapHit.Note)
        assertEquals(MapHit.Empty, tap(Offset(0f, -2000f)))
    }

    /** The diamonds' and moons' names come in with the sparks' names: none at the home zoom, fading in past it, full a little further. */
    @Test fun flowerNamesShowOnlyWhenZoomedIn() {
        val home = 0.6f
        assertEquals(0f, sparkNameAlpha(home, home))
        assertEquals(0f, sparkNameAlpha(home * 1.15f, home))
        assertTrue(sparkNameAlpha(home * 1.3f, home) in 0.01f..0.99f)
        assertEquals(1f, sparkNameAlpha(home * 1.5f, home))
        assertEquals(1f, sparkNameAlpha(home * 4f, home))
    }

    /** One small name per task diamond and goal moon, 14 letters then "…", reading outward: left of a node left of its brain. */
    @Test fun flowerNames() {
        val tasks = (1..6).map { Idea(id = it.toLong(), lobeId = 2, title = if (it == 1) "Renew the passport at the office" else "task $it", body = "", uid = "t$it") }
        val goals = (1..4).map { Goal(id = it.toLong(), name = if (it == 1) "Spanish verbs and more" else "goal $it", position = it, createdAt = 0, updatedAt = 0, uid = "g$it") }
        val l = buildLayout(tasks, goals = goals)
        val f = l.flowerLabels()
        assertEquals(10, f.size)
        assertEquals("Renew the pass…", f.first { it.text.startsWith("Renew") }.text)
        assertEquals("Spanish verbs…", f.first { it.text.startsWith("Spanish") }.text)
        assertEquals("task 2", flowerName("task 2"))
        assertEquals("...", flowerName("  "))
        assertTrue(f.all { it.text.length <= 15 })
        for (x in f.take(6)) assertEquals(x.at.x < TASKS_AT.x, x.leftOf)
        for (x in f.drop(6)) assertEquals(x.at.x < LEARN_AT.x, x.leftOf)
        assertEquals(l.tasks.indices.map { Offset(l.tx[it], l.ty[it]) }, f.take(6).map { it.at })
        assertEquals(l.goalAt, f.drop(6).map { it.at })
    }

    /** 2.8.1: tiny candles round the daily brain and tiny cards round the bible brain: round, apart, mirrored, stable, never a tap, named. */
    @Test fun dailyAndBibleFlowers() {
        fun item(n: Int) = Idea(id = n.toLong(), lobeId = 4, title = "item $n", body = "", uid = "d$n", done = n % 3 == 0)
        fun card(n: Int) = app.vesper.data.BibleCard(id = n.toLong(), name = "card $n", x = 0f, y = 0f, kind = if (n % 2 == 0) app.vesper.data.BIBLE_THING else "person", uid = "b$n")
        fun apart(a: FloatArray, b: FloatArray) = a[2] <= b[0] || b[2] <= a[0] || a[3] <= b[1] || b[3] <= a[1]
        val l = buildLayout(emptyList(), daily = (1..20).map(::item), bible = (1..20).map(::card))
        assertEquals(20, l.dailyAt.size); assertEquals(20, l.bibleAt.size)
        // mirrored: the bible flower is the daily flower flipped about x = 0
        for ((d, b) in l.dailyAt.zip(l.bibleAt)) assertTrue("$d mirrors $b", abs(-d.x - b.x) < 0.01f && abs(d.y - b.y) < 0.01f) // float trig, not exact
        assertTrue(l.dailyAt.all { it.x < 0f } && l.bibleAt.all { it.x > 0f })
        // compact (round): inside a few rings of the brain, and apart from each other and from everything fixed on the map
        for ((at, brain) in listOf(l.dailyAt to DAILY_AT, l.bibleAt to BIBLE_AT)) {
            assertTrue(at.all { (it - brain).getDistance() <= PETAL_R0 + 4 * PETAL_STEP })
            val boxes = at.map(::decoBox)
            for (i in boxes.indices) {
                for (j in i + 1 until boxes.size) assertTrue("flowers $i and $j apart", apart(boxes[i], boxes[j]))
                for (f in fixedBoxes(l.noteAt) + topBoxes()) assertTrue("flower $i clear of the map", apart(boxes[i], f))
            }
        }
        // stable: more items never move the earlier ones; the same uids give the same spots
        val more = buildLayout(emptyList(), daily = (1..24).map(::item), bible = (1..24).map(::card))
        assertEquals(l.dailyAt, more.dailyAt.take(20)); assertEquals(l.bibleAt, more.bibleAt.take(20))
        assertEquals(DECO_CAP, buildLayout(emptyList(), daily = (1..40).map(::item)).dailyAt.size)
        // decoration: a tap on a flower hits nothing
        val u = 2.28f * 2.75f * 0.6f
        for (p in l.dailyAt + l.bibleAt) assertEquals(MapHit.Empty, l.mapHit(p, u, nodeScale(0.6f), 2.75f, daily = true, bible = true).first)
        // their names come after the tasks' and goals', in the same style
        val f = l.flowerLabels()
        assertEquals(40, f.size)
        assertEquals(listOf("item 1", "item 2"), f.take(2).map { it.text })
        assertEquals("card 1", f[20].text)
        for (x in f.take(20)) assertEquals(x.at.x < DAILY_AT.x, x.leftOf)
        for (x in f.drop(20)) assertEquals(x.at.x < BIBLE_AT.x, x.leftOf)
        // with the brains off nothing is hung round them
        assertEquals(0, buildLayout(emptyList()).dailyAt.size)
    }
}
