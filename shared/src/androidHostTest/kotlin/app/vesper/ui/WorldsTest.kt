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
import kotlin.math.max
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

    private val brainR = 66f

    /** 2.8.0: the tasks brain sits in the middle with the lanterns all round it, none overlapping and all clear of the brain. */
    @Test fun lanternsCircleTheBrainApartAndClearOfIt() {
        val brain = floatArrayOf(-brainR, -brainR, brainR, brainR)
        for (set in 0 until 12) for (n in listOf(1, 3, 8, 16, 30, 60)) {
            val boxes = lanternCloud((1..n).map { "s$set-$it".hashCode() }, lantern, brainR)
            assertEquals(n, boxes.size)
            noneOverlap(boxes, "set $set n=$n")
            for (b in boxes) assertFalse("set $set n=$n: on the brain", boxesHit(b, brain, 0f))
            if (n >= 8) {
                val cx = boxes.map { (it[0] + it[2]) / 2f }; val cy = boxes.map { (it[1] + it[3]) / 2f }
                assertTrue("set $set n=$n: on both sides", cx.min() < -40f && cx.max() > 40f)
                assertTrue("set $set n=$n: above and below", cy.min() < -40f && cy.max() > 40f)
                assertTrue("set $set n=$n: the brain is the middle", kotlin.math.abs(cx.average()) < 90f && kotlin.math.abs(cy.average()) < 110f)
            }
        }
    }

    @Test fun aLanternKeepsItsSpotByUidWhenNewOnesCome() {
        val seeds = (1..12).map { "task-$it".hashCode() }
        val a = lanternCloud(seeds, lantern, brainR)
        val b = lanternCloud(seeds + "task-13".hashCode(), lantern, brainR)
        for (i in seeds.indices) assertTrue("lantern $i moved", a[i].contentEquals(b[i]))
        assertFalse("other uids, other spots", lanternCloud(listOf(1), lantern, brainR)[0].contentEquals(lanternCloud(listOf(2), lantern, brainR)[0]))
    }

    /** 16 lanterns (the real count) fit a 390 dp phone at the start zoom, which is never above 1; pan and zoom stay in soft bounds. */
    @Test fun theCloudFitsAndPansALittle() {
        val boxes = lanternCloud((1..16).map { "t$it".hashCode() }, lantern, brainR)
        val bounds = cloudBounds(boxes, brainR)
        val z = cloudZoom(bounds, 390f, 700f)
        assertTrue("start zoom $z", z in CLOUD_MIN_Z..1f)
        assertEquals(1f, cloudZoom(cloudBounds(emptyList(), brainR), 390f, 700f))
        val pan = cloudPan(bounds, 390f, 700f, CLOUD_MAX_Z)
        assertTrue("zoomed in, every edge can be reached", pan.x > 0f && pan.y > 0f)
        assertTrue("never far past the cloud", pan.x <= max(-bounds[0], bounds[2]) && pan.y <= max(-bounds[1], bounds[3]))
        assertEquals("nothing to pan when it all fits", Offset.Zero, cloudPan(cloudBounds(emptyList(), brainR), 390f, 700f, 1f))
        assertEquals("names follow a smaller zoom", 0.8f, lanternNameScale(0.8f), 1e-6f)
        assertTrue("and grow a bit past 1", lanternNameScale(1.8f) in 1.1f..1.8f)
        assertEquals(2, artScale(1.6f)); assertEquals(1, artScale(1f))
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

    /** A lantern on screen sits where the camera puts its box: the brain's spot in the middle when the camera is home, its drift only a few dp. */
    @Test fun aLanternOnScreen() {
        val boxes = lanternCloud((1..5).map { "t$it".hashCode() }, lantern, brainR)
        val art = Size(24f, 30f)
        boxes.forEachIndexed { i, b ->
            val home = lanternScreen(b, art, 0f, 0, Offset.Zero, 1f, 390f, 700f)
            assertEquals((b[0] + b[2]) / 2f + 195f, home.x, 0.01f)
            for (t in listOf(0f, 3.1f, 17f)) assertTrue((lanternScreen(b, art, t, i * 77, Offset.Zero, 1f, 390f, 700f) - home).getDistance() <= 4.5f)
            val zoomed = lanternScreen(b, art, 0f, 0, Offset.Zero, 1.5f, 390f, 700f)
            assertEquals((home.x - 195f) * 1.5f, zoomed.x - 195f, 0.01f)
        }
    }

    /** 2.8.0: goal moons float a few dp, never more than the room their boxes keep, so they never touch. */
    @Test fun moonsFloatInsideTheirRoom() {
        for (seed in listOf(0, 7, -99, 123456)) for (t in listOf(0f, 1f, 9.5f, 300f)) {
            val d = moonDrift(t, seed)
            assertTrue(kotlin.math.abs(d.x) <= MOON_DRIFT && kotlin.math.abs(d.y) <= MOON_DRIFT)
        }
        assertFalse("it moves", moonDrift(0f, 5) == moonDrift(4f, 5))
    }
}
