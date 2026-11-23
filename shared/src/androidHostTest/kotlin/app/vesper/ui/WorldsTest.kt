package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import app.vesper.data.Idea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 2.7.8: the learn world (goal moons round the learn brain, whole names) and the tasks world (a lantern per task). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class WorldsTest {
    private val moon = Size(48f, 48f)
    private fun noneOverlap(boxes: List<FloatArray>, ctx: String) {
        for (a in boxes.indices) for (b in a + 1 until boxes.size) assertFalse("$ctx: $a and $b overlap", boxesHit(boxes[a], boxes[b], 0f))
    }

    @Test fun goalMoonsSitRoundTheBrainApartAndInsideTheWidth() {
        for (n in 0..14) for (w in listOf(320f, 390f, 900f)) {
            val labels = List(n) { Size(40f + (it * 37 % 79), 17f * (1 + it % 4)) }
            val l = learnWorldLayout(labels, w, 640f, moon, 62f)
            val brain = floatArrayOf(l.brain.x - 62f, l.brain.y - 62f, l.brain.x + 62f, l.brain.y + 62f)
            noneOverlap(l.boxes, "n=$n w=$w")
            l.boxes.forEachIndexed { i, b ->
                assertTrue("n=$n w=$w: $i off the sides", b[0] >= 0f && b[2] <= w)
                assertTrue("n=$n w=$w: $i above the top or below the world", b[1] >= 0f && b[3] <= l.h)
                assertFalse("n=$n w=$w: $i on the brain", boxesHit(b, brain, 0f))
                assertTrue("n=$n w=$w: $i box holds the moon and its whole name", b[3] - b[1] >= moon.height + labels[i].height && b[2] - b[0] >= labels[i].width)
            }
        }
    }

    /** A 60-letter goal name wraps under its moon with nothing cut: measured with no line limit at the label width, and the box is that tall. */
    @Test fun aLongGoalNameIsShownWhole() {
        val m = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(2.75f), LayoutDirection.Ltr)
        val name = "Learn how my computer works from the transistor to the screen".take(60)
        val tl = m.measure(name, TextStyle(fontSize = 17.sp, lineHeight = 17.sp), constraints = Constraints(maxWidth = (GOAL_LABEL_W * 2.75f).toInt()))
        assertFalse(tl.hasVisualOverflow)
        assertTrue("it wraps", tl.lineCount > 1)
        assertEquals(name.length, tl.getLineEnd(tl.lineCount - 1))
        val size = Size(tl.size.width / 2.75f, tl.size.height / 2.75f)
        val b = learnWorldLayout(listOf(size), 390f, 640f, moon, 62f).boxes.single()
        assertTrue(b[3] - b[1] >= moon.height + size.height)
    }

    private val lantern = Size(98f, 72f)

    @Test fun lanternsNeverOverlapStayInsideAndClearOfTheBrain() {
        for (set in 0 until 12) for (n in listOf(1, 3, 8, 15, 30, 60)) {
            val seeds = (1..n).map { "s$set-$it".hashCode() }
            val l = lanternLayout(seeds, 390f, 700f, lantern)
            noneOverlap(l.boxes, "set $set n=$n")
            for (b in l.boxes) {
                assertTrue("set $set n=$n: off the sky", b[0] >= 0f && b[2] <= 390f && b[1] >= 0f)
                assertTrue("set $set n=$n: in the brain's room", b[3] <= l.h - LANTERN_BRAIN_ZONE + 0.01f)
            }
            assertTrue("the brain sits low", l.brain.y > l.h - LANTERN_BRAIN_ZONE)
            if (n <= 15) assertEquals("set $set: a few lanterns fit one phone screen", 700f, l.h)
        }
    }

    @Test fun aLanternKeepsItsSpotByUidWhenNewOnesCome() {
        val seeds = (1..12).map { "task-$it".hashCode() }
        val a = lanternLayout(seeds, 390f, 700f, lantern)
        val b = lanternLayout(seeds + "task-13".hashCode(), 390f, 700f, lantern)
        for (i in seeds.indices) assertTrue("lantern $i moved", a.boxes[i].contentEquals(b.boxes[i]))
        assertFalse("other uids, other spots", lanternLayout(listOf(1), 390f, 700f, lantern).boxes[0].contentEquals(lanternLayout(listOf(2), 390f, 700f, lantern).boxes[0]))
    }

    @Test fun lanternsSpreadOverTheWholeSky() {
        val l = lanternLayout((1..15).map { "t$it".hashCode() }, 390f, 700f, lantern)
        val ys = l.boxes.map { (it[1] + it[3]) / 2f }
        val xs = l.boxes.map { (it[0] + it[2]) / 2f }
        assertTrue("top to bottom", ys.min() < 140f && ys.max() > 420f)
        assertTrue("side to side", xs.min() < 120f && xs.max() > 270f)
    }

    /** Every lantern looks the same: a done task's name and lantern are a not-done one's. */
    @Test fun noDoneState() {
        val t = Idea(id = 1, lobeId = 2, title = "Singing learner App with a long name", body = "", uid = "u")
        assertEquals(lanternName(t), lanternName(t.copy(done = true)))
        assertEquals("Singing learner App…", lanternName(t))
        assertEquals("Taxes", lanternName(t.copy(title = "Taxes")))
    }

    @Test fun theLanternIsPurpleAndWhiteNeverPink() {
        val used = LANTERN_ROWS.joinToString("").filter { it != '.' }.toSet()
        for (ch in used) {
            val rgb = lanternRgb(ch)
            val r = rgb shr 16 and 0xFF; val b = rgb and 0xFF
            assertTrue("$ch is drawn", rgb >= 0)
            assertTrue("$ch leans red (pink)", r <= b)
        }
        assertEquals(LANTERN_H, LANTERN_ROWS.size)
        assertTrue(LANTERN_ROWS.all { it.length == LANTERN_W })
    }

    /** A tap on a lantern (where it is drawn, drifting or not) or its name finds that lantern; a tap on the empty sky finds none. */
    @Test fun aTapFindsItsLantern() {
        val l = lanternLayout((1..15).map { "t$it".hashCode() }, 390f, 700f, lantern)
        val art = Size(24f, 30f)
        l.boxes.forEachIndexed { i, b ->
            for (t in listOf(0f, 3.1f, 17f)) assertEquals(i, boxAt(l.boxes, lanternAt(b, art, t, i * 77)))
            assertEquals(i, boxAt(l.boxes, Offset((b[0] + b[2]) / 2f, b[3] - 4f)))
        }
        assertEquals(-1, boxAt(l.boxes, l.brain))
    }
}
