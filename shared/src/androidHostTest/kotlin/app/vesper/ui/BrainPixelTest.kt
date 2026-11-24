package app.vesper.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import app.vesper.res.Res
import app.vesper.res.brain_main
import app.vesper.res.vault_heart
import app.vesper.res.vault_heart_dead
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.robolectric.RuntimeEnvironment
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import app.vesper.data.Goal
import app.vesper.data.Idea
import org.junit.After
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden hashes of the Brain map renderer and the cached label glow.
 * Expected values were captured from the pre-port android.graphics BrainRenderer (M2 part 3).
 * Alt version (Brain as the whole app): every map frame was re-captured on purpose, because neurons are now a core dot with
 * rings by length of text (no done ember any more), and rot holes are pushed clear of nodes and labels. glowLabel is untouched.
 * Vault heart replaces the skull: overview, beatFresh (was flareFresh), staleHeart (was staleSun) and selectedFar re-captured.
 * recentSpark is new: violet recent cores and the open pulse.
 * 2.5.1: overview, beatFresh, staleHeart, small and blit3 changed because rot holes now clear ring-sized areas around neurons
 * (item keep-out boxes 5.5 -> 8 wide); selectedZoom and selectedFar changed for that and because a focused sub brain dims the
 * rest of the map to 0.2 instead of 0.45, and hides the heart, its glow and its vein completely. groupDeep, recentSpark and glowLabel are untouched.
 * 2.5.2: selectedZoom, selectedFar, small and blit3 changed on purpose: with a lobe selected the core fades to 0.2 and no veins
 * or vein pulses are drawn (they crossed the focused tree). overview, beatFresh, staleHeart, groupDeep, recentSpark, glowLabel are untouched.
 * 2.5.3: every frame with a lobe selected changed on purpose (selectedZoom, selectedFar, groupDeep, recentSpark, small, blit3): the core, its
 * aura and big rings, and every other lobe (sprites, groups, items, threads, glows) are no longer drawn at all, where 2.5.2 left them at 0.2.
 * overview, beatFresh, staleHeart and glowLabel are untouched.
 * 2.5.5 (portal page): every frame with a lobe selected changed on purpose again (selectedZoom, selectedFar, groupDeep, recentSpark, small, blit3):
 * the selected lobe is drawn as a big core in its own colour (glow, two rings, bigger sprite), and no rot holes or drips of other owners are drawn.
 * overview, beatFresh, staleHeart and glowLabel are untouched.
 * 2.5.6 (purple dimensions): every map frame changed on purpose (overview, beatFresh, staleHeart, selectedZoom, selectedFar, groupDeep,
 * small, blit3): no pink anywhere (purple shades per sub brain, tinted sprites, purple heart), the neuron veins and threads in purple, traveling sparks,
 * notes as plain dots, groups as dots on the main map and small brains in a dimension. recentSpark is gone (no recent glow or open pulse);
 * dimension is new. glowLabel is untouched.
 * 2.5.8 (clean map, readable dimensions): overview, beatFresh and staleHeart changed on purpose (no rot holes or their drips; the vault
 * heart is the original art again, untinted, as the user asked); selectedZoom, selectedFar, groupDeep and dimension changed on purpose (a
 * dimension's dots barely wobble, group brains have a smaller, softer glow; dimension also gets the new column layout, its hub knot and
 * straight lines). small, blit3 and glowLabel are untouched.
 * 2.5.9 (one brain, many worlds): every map frame changed on purpose (overview, beatFresh, staleHeart, selectedZoom, selectedFar, small,
 * blit3, dimension): sub brains are drawn as portals, the main map places no groups or notes, and a selected lobe draws its world of
 * constellations inside an edge ring. groupDeep is gone; showAll (stars in portals) and flyIn (a portal grown past the zoom cap) are new.
 * glowLabel is untouched.
 * 2.5.10 (polish): overview, beatFresh, staleHeart, showAll and flyIn changed on purpose: portals sit evenly on one ring, the heart
 * moved below it, the thick pixel veins left the low-res frame (thin veins are drawn at full resolution now), the core pulses as a
 * spark leaves, and the heart fades out during a fly-through. dimension changed on purpose: the empty group gets a ghost star and the
 * done note a dim grey one. selectedZoom, selectedFar, small, blit3 and glowLabel are untouched.
 * 2.5.11 (portal above): every focused-world frame changed on purpose (selectedZoom, selectedFar, dimension, small, blit3): the edge ring is gone,
 * the portal arc and its rays hang at the top of the page. 2.5.12-13: arc reshaped (smooth full-resolution ring); the main map gets the organic
 * nerves back (overview, beatFresh, staleHeart, showAll changed on purpose). flyIn and glowLabel are untouched.
 * 2.5.14: every focused-world frame changed on purpose (selectedZoom, selectedFar, dimension, small, blit3): the portal mouth is a darker
 * hole with an inner glow along the arc and brighter stars. flyIn changed on purpose: the other portals fade out early in a fly-through and
 * the flown portal drops its dashes and dots by mid-flight. overview, beatFresh, staleHeart, showAll and glowLabel are untouched.
 * 2.5.18: overview, beatFresh and showAll changed on purpose (the vault heart is purple again); selectedZoom, selectedFar, dimension, small and blit3
 * changed on purpose (the portal arc is pinned at the top of the screen, drawn over the world, so the page scrolls under it; the test renderer
 * now sets arcTopDp = 60). staleHeart, flyIn and glowLabel are untouched.
 * Learn brain: learnBrain is new (the small learn brain with three moons, beside the vault heart since 2.6.1). Every other frame is untouched: the
 * renderer draws it only when [BrainRenderer.learn] is set.
 * 2.7.0 (no portals): every map frame changed on purpose. The main map lost its portal ring, worlds and fly-throughs; it now draws the
 * main brain, the vault heart, the learn brain and a tasks brain with a node on a vein for every task. overview, beatFresh, staleHeart,
 * learnBrain, small and blit3 were re-captured; noTasks is new; selectedZoom, selectedFar, showAll, flyIn and dimension are gone.
 * glowLabel is untouched.
 * 2.7.0 (compact map): beatFresh, blit3, learnBrain, noTasks, overview, small and staleHeart changed on purpose (heart and the small brains
 * moved up under the main brain, tasks hang under the tasks brain). glowLabel is untouched.
 * 2.7.2: beatFresh, blit3, overview, small and staleHeart changed on purpose (the main-brain note strands are gone, tasks are
 * small pixel diamonds scattered round the tasks brain on their own strands, the learn brain's orbiting dots are gone). learnBrain changed
 * on purpose too and now draws three goal moons (the new purple pixel moon) round the learn brain. noTasks and glowLabel are untouched.
 * 2.7.4/2.7.5: every map frame except glowLabel changed on purpose: the pinned-notes corkboard (chunky frame, cork) hangs centred under everything,
 * empty here; the tasks and goals are placed exactly as in 2.7.3.
 * 2.7.6: every map frame except glowLabel changed on purpose (beatFresh, blit3, learnBrain, noTasks, overview, small, staleHeart): the
 * small brains sit fixed on a symmetric ring round the main brain, no strands run to tasks or goals, the notes pack tighter and the board
 * hangs closer under them.
 * 2.7.7: beatFresh, blit3, learnBrain, overview, small and staleHeart changed on purpose: the tasks and goals are decorative flowers of
 * diamonds and moons on rings round their brains (no names), and the board hangs under the flowers. noTasks and glowLabel are untouched.
 * 2.7.8: dailyLit and dailyDim are new (the daily brain on the top-left ring spot with its nerve, lit and dimmed). Every other frame is
 * untouched: the renderer draws the daily brain only when [BrainRenderer.daily] is set.
 * 2.7.9: dailyBible is new (the bible brain on the top-right ring spot with its nerve, beside a lit daily brain). Every other frame is
 * untouched: the renderer draws the bible brain only when [BrainRenderer.bible] is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrainPixelTest {

    private val mismatches = mutableListOf<String>()
    @After fun noMismatches() = assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())

    private fun check(name: String, px: IntArray, expected: Int?) {
        val distinct = px.toSet().size
        println("BrainPixelTest $name hash=${px.contentHashCode()} distinct=$distinct")
        assertTrue("$name drew almost nothing ($distinct colours); native graphics off?", distinct > 3)
        if (px.contentHashCode() != expected) mismatches += "$name: expected $expected, got ${px.contentHashCode()}"
    }

    // ---- the only version-specific part (pre-port: android Bitmap + BitmapFactory) ----
    // Sprites come from :shared's Compose resources through the same reader and decoder imageResource() uses
    // (unqualified = mdpi, never upscaled), so this also proves they are packaged and not density-scaled.
    @OptIn(ExperimentalResourceApi::class)
    private fun newRenderer(): BrainRenderer = runBlocking {
        setResourceReaderAndroidContext(RuntimeEnvironment.getApplication()) // Robolectric doesn't start CMP's context provider
        val env = getSystemResourceEnvironment()
        suspend fun img(r: DrawableResource) = getDrawableResourceBytes(env, r).decodeToImageBitmap()
        BrainRenderer(img(Res.drawable.brain_main), img(Res.drawable.vault_heart), img(Res.drawable.vault_heart_dead))
    }
    private fun pixels(r: BrainRenderer): IntArray = IntArray(r.W * r.H).also { r.pix.bmp.readPixels(it) }
    private fun blitPixels(r: BrainRenderer, px: Int): IntArray {
        val target = ImageBitmap(r.W * px + 7, r.H * px + 5)
        r.blit(Canvas(target), px)
        return IntArray(target.width * target.height).also { target.readPixels(it) }
    }
    // ----

    private val tasks = listOf(
        Idea(id = 1, lobeId = 6, title = "call the tailor", body = "", uid = "task-1"),
        Idea(id = 2, lobeId = 6, title = "", body = "fix the bike", uid = "task-2", done = true),
        Idea(id = 3, lobeId = 6, title = "taxes", body = "", uid = "task-3"),
    )
    private val layout = buildLayout(tasks)
    private val goals = listOf(
        Goal(id = 1, name = "Panduri", uid = "g1", updatedAt = 0L), Goal(id = 2, name = "Spanish", uid = "g2", updatedAt = 0L), Goal(id = 3, name = "Run 5k", uid = "g3", updatedAt = 0L),
    )

    @Test fun frames() {
        val r = newRenderer()
        r.resize(180, 390)
        // calm overview, fresh vault, no pulse flare
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        check("overview", pixels(r), E["overview"])
        // t = 2.3: beatPh just past 1, so the heart is mid swell with its glow up
        r.draw(2.3f, 4f, -6f, 0.85f, layout, 0f, false)
        check("beatFresh", pixels(r), E["beatFresh"])
        // stale vault selected: dead grey heart, no beat, the map dimmed; zoomed past 2 (2px specks and drips)
        r.draw(2.3f, -40f, 120f, 2.4f, layout, 1f, true)
        check("staleHeart", pixels(r), E["staleHeart"])
        // no tasks at all: the tasks brain alone
        r.draw(0.5f, 0f, 0f, 0.85f, buildLayout(emptyList()), 0f, false)
        check("noTasks", pixels(r), E["noTasks"])
    }

    /** Main-brain notes draw without trouble and change the picture (no golden hash: they are not a pinned look). */
    @Test fun notesDraw() {
        val r = newRenderer(); r.resize(180, 390)
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        val base = pixels(r).contentHashCode()
        val notes = (1..12).map { Idea(id = 100L + it, lobeId = 1, title = "n$it", body = "", uid = "note-$it") }
        r.draw(0.5f, 0f, 0f, 0.85f, buildLayout(tasks, notes), 0f, false)
        val withNotes = pixels(r).contentHashCode()
        assertTrue("the notes show", withNotes != base)

    }

    /** Pinned papers (and a dragged one) draw on the board and change the picture; no golden hash, the board's look is checked by eye. */
    @Test fun pinnedPapersDraw() {
        val r = newRenderer(); r.resize(180, 390)
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        val base = pixels(r).contentHashCode()
        val pins = (1..5).map { Idea(id = 200L + it, lobeId = 8, title = "pin $it", body = "", uid = "pin-$it") }
        r.draw(0.5f, 0f, 0f, 0.85f, buildLayout(tasks, pinned = pins), 0f, false)
        val withPins = pixels(r).contentHashCode()
        assertTrue("the papers show", withPins != base)
        NoteMoves.move("pin-2", 12f, -9f)
        try {
            r.draw(0.5f, 0f, 0f, 0.85f, buildLayout(tasks, pinned = pins), 0f, false)
            assertTrue("a dragged paper shows", pixels(r).contentHashCode() != withPins)
        } finally { NoteMoves.d.clear() }
    }

    /**
     * No strand runs from the tasks brain to a task or from the learn brain to a goal: against a map with none, the frame changes only
     * round their nodes (and round the board, which hangs lower under the flowers).
     */
    @Test fun noStrandsToTasksOrGoals() {
        val r = newRenderer(); r.resize(180, 390); r.learnMoons = 3
        val ts = (1..12).map { Idea(id = it.toLong(), lobeId = 6, title = "task $it", body = "", uid = "t$it") }
        val l = buildLayout(ts, goals = goals)
        val bare = buildLayout(emptyList())
        r.draw(0.5f, 0f, 30f, 0.85f, l, 0f, false)
        val home = pixels(r)
        r.draw(0.5f, 0f, 30f, 0.85f, bare, 0f, false)
        val gone = pixels(r)
        fun sx(x: Float) = x * 0.85f + 90f
        fun sy(y: Float) = (y - 30f) * 0.85f + 195f
        val spots = l.tasks.indices.map { Offset(l.tx[it], l.ty[it]) } + l.goalAt
        fun nearANode(x: Int, y: Int) = spots.any { hypot(x - sx(it.x), y - sy(it.y)) <= 14f }
        // the board sits lower under a bigger flower: its frame, glow and shadow may change too
        fun nearABoard(x: Int, y: Int) = listOf(l.boardAt, bare.boardAt).any { b -> boardBox(b).let { x >= sx(it[0]) - 70f && x <= sx(it[2]) + 70f && y >= sy(it[1]) - 70f && y <= sy(it[3]) + 70f } }
        var changed = 0
        for (y in 0 until 390) for (x in 0 until 180) if (home[y * 180 + x] != gone[y * 180 + x]) {
            changed++
            assertTrue("a pixel changed away from every node at ($x, $y): a strand?", nearANode(x, y) || nearABoard(x, y))
        }
        assertTrue("the nodes themselves show", changed > 20)
    }

    /** The small learn brain with three goal moons beside the heart; without it the overview stays exactly as it was. */
    @Test fun learnBrain() {
        val r = newRenderer(); r.resize(180, 390)
        r.learnMoons = 3
        r.draw(0.5f, 0f, 0f, 0.85f, buildLayout(tasks, goals = goals), 0f, false)
        check("learnBrain", pixels(r), E["learnBrain"])
        r.learnMoons = -1
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        check("overview", pixels(r), E["overview"])
    }

    /** The daily brain on the top-left ring, fully lit and dimmed; without it the overview stays exactly as it was. */
    @Test fun dailyBrain() {
        val r = newRenderer(); r.resize(180, 390)
        r.daily = 1f
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        val lit = pixels(r)
        check("dailyLit", lit, E["dailyLit"])
        r.daily = DAILY_DIM
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        val dim = pixels(r)
        check("dailyDim", dim, E["dailyDim"])
        fun lum(px: IntArray) = px.sumOf { (it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF) }
        assertTrue("the dimmed brain is darker", lum(dim) < lum(lit))
        r.daily = -1f
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        check("overview", pixels(r), E["overview"])
    }

    /** The bible brain on the top-right ring with its nerve: drawn, purple and white only (never pink), and gone when off. */
    @Test fun bibleBrain() {
        val r = newRenderer(); r.resize(180, 390)
        r.daily = 1f
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        val without = pixels(r)
        check("dailyLit", without, E["dailyLit"])
        r.bible = true
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        val with = pixels(r)
        check("dailyBible", with, E["dailyBible"])
        val changed = with.indices.filter { with[it] != without[it] }
        assertTrue("the bible brain shows (${changed.size} px)", changed.size > 200)
        // it sits top right: every changed pixel is right of the middle or on its nerve from the core, above the core
        val (bx, by) = (BIBLE_AT.x * 0.85f + 90f) to (BIBLE_AT.y * 0.85f + 195f)
        assertTrue("changed pixels lie toward the top-right spot", changed.all { i -> (i % 180) >= 80 && (i / 180) <= 200 })
        assertTrue("its middle is lit", changed.any { i -> hypot(i % 180 - bx, i / 180 - by) < 6f })
        for (i in changed) assertTrue("pink pixel %06x at (%d, %d)".format(with[i] and 0xFFFFFF, i % 180, i / 180), !isPink(with[i]))
        r.bible = false
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        check("dailyLit", pixels(r), E["dailyLit"])
    }

    /** 2.8.1: tiny candles round the daily brain and tiny cards round the bible brain; they draw, stay purple and white, and only while their brain is on. */
    @Test fun dailyAndBibleFlowers() {
        val r = newRenderer(); r.resize(180, 390)
        r.daily = 1f; r.bible = true
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        val plain = pixels(r)
        val items = (1..8).map { Idea(id = it.toLong(), lobeId = 4, title = "i$it", body = "", uid = "d$it", done = it % 2 == 0) }
        val cards = (1..8).map { app.vesper.data.BibleCard(name = "c$it", x = 0f, y = 0f, kind = if (it % 2 == 0) app.vesper.data.BIBLE_THING else "person", uid = "b$it") }
        val full = buildLayout(tasks, daily = items, bible = cards)
        r.draw(0.5f, 0f, 0f, 0.85f, full, 0f, false)
        val with = pixels(r)
        val changed = with.indices.filter { with[it] != plain[it] }
        assertTrue("the flowers show (${changed.size} px)", changed.size > 200)
        for (i in changed) assertTrue("pink pixel %06x at (%d, %d)".format(with[i] and 0xFFFFFF, i % 180, i / 180), !isPink(with[i]))
        r.daily = -1f; r.bible = false
        r.draw(0.5f, 0f, 0f, 0.85f, full, 0f, false)
        r.draw(0.5f, 0f, 0f, 0.85f, layout, 0f, false)
        val off = pixels(r)
        r.draw(0.5f, 0f, 0f, 0.85f, full, 0f, false)
        assertEquals("no brain, no flowers", off.contentHashCode(), pixels(r).contentHashCode())
    }

    @Test fun resizeAndBlit() {
        val r = newRenderer()
        r.resize(60, 90)
        r.draw(3.3f, -60f, 120f, 0.7f, layout, 0f, false)
        check("small", pixels(r), E["small"])
        check("blit3", blitPixels(r, 3), E["blit3"])
    }

    @Test fun glowLabel() {
        val ctx = RuntimeEnvironment.getApplication()
        val density = Density(2.625f)
        val measurer = TextMeasurer(createFontFamilyResolver(ctx), density, LayoutDirection.Ltr)
        val c = Color(0xFF935AD4)
        val style = TextStyle(fontSize = 20.sp, color = c, shadow = Shadow(c.copy(alpha = 0.7f), blurRadius = 12f * 2.625f))
        fun render(): IntArray {
            val g = GlowText(measurer.measure("Marrow", style, maxLines = 1))
            val target = ImageBitmap(260, 120)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(target), Size(260f, 120f)) {
                g.draw(this, 20.4f, 30.6f, 0.45f)
                g.draw(this, 80f, 50f, 1f) // second draw reuses the cached image
            }
            return IntArray(260 * 120).also { target.readPixels(it) }
        }
        val a = render()
        assertTrue("label glow not deterministic", a.contentEquals(render()))
        check("glowLabel", a, E["glowLabel"])
    }

    private companion object {
        /** Red over blue: pink, magenta or warm. The app's purples and whites always have blue at least as strong as red. */
        fun isPink(px: Int) = (px shr 16 and 0xFF) > (px and 0xFF) + 8

        val E = mapOf(
            "beatFresh" to 333955742, "dailyDim" to -1543158383, "dailyLit" to -1515874354, "dailyBible" to -625859451, "blit3" to 509315180, "glowLabel" to 1509847742, "learnBrain" to -1943259253, "noTasks" to -1085405908, "overview" to 229685976, "small" to 1948659030, "staleHeart" to -1708098666,
        )
    }
}
