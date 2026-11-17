package app.vesper.ui

import androidx.compose.ui.geometry.Offset

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
import app.vesper.res.brain_lobe
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
import app.vesper.data.Branch
import app.vesper.data.Idea
import app.vesper.data.Lobe
import org.junit.After
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
 * Learn brain: learnBrain is new (the small learn brain with three moons beside the core). Every other frame is untouched: the
 * renderer draws it only when [BrainRenderer.learn] is set.
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
    private fun newRenderer(core: DrawableResource = Res.drawable.brain_main): BrainRenderer = runBlocking {
        setResourceReaderAndroidContext(RuntimeEnvironment.getApplication()) // Robolectric doesn't start CMP's context provider
        val env = getSystemResourceEnvironment()
        suspend fun img(r: DrawableResource) = getDrawableResourceBytes(env, r).decodeToImageBitmap()
        BrainRenderer(img(core), img(Res.drawable.vault_heart), img(Res.drawable.vault_heart_dead)).also { it.arcTopDp = 60f }
    }
    private fun pixels(r: BrainRenderer): IntArray = IntArray(r.W * r.H).also { r.pix.bmp.readPixels(it) }
    private fun blitPixels(r: BrainRenderer, px: Int): IntArray {
        val target = ImageBitmap(r.W * px + 7, r.H * px + 5)
        r.blit(Canvas(target), px)
        return IntArray(target.width * target.height).also { target.readPixels(it) }
    }
    // ----

    private val lobes = listOf(
        Lobe(id = 1, name = "Hollow", colorIndex = 0, slot = 0, uid = "lobe-1"),
        Lobe(id = 2, name = "Ash", colorIndex = 1, slot = 1, uid = "lobe-2"),
        Lobe(id = 5, name = "", colorIndex = 4, slot = 2, uid = "lobe-5"),
        Lobe(id = 9, name = "Marrow", colorIndex = 3, slot = 7, uid = "lobe-9"),
    )
    private val branches = listOf(Branch(id = 1, lobeId = 2, name = "Read", uid = "read"), Branch(id = 2, lobeId = 2, name = "Empty", uid = "empty"))
    private val ideas = buildList {
        for (k in 1..12) add(Idea(id = k.toLong(), lobeId = 1, title = "idea $k", body = "", updatedAt = 1000L + k * 37 % 11, uid = "idea-$k"))
        for (k in 20..22) add(Idea(id = k.toLong(), lobeId = 2, title = "", body = "b$k", updatedAt = 5000L - k, uid = "idea-$k", branchId = 1, done = k == 21))
        add(Idea(id = 40, lobeId = 9, title = "lone", body = "", updatedAt = 1L, uid = "idea-40"))
    }
    private val layout = buildLayout(lobes, placeIdeas(lobes, branches, ideas))

    @Test fun frames() {
        val r = newRenderer()
        r.resize(180, 390)
        // calm overview, fresh vault, no pulse flare
        r.draw(0.5f, 0f, 0f, 0.85f, layout, -1, 0f, false)
        check("overview", pixels(r), E["overview"])
        // t = 2.3: beatPh just past 1, so the heart is mid swell with its glow up
        r.draw(2.3f, 4f, -6f, 0.85f, layout, -1, 0f, false)
        check("beatFresh", pixels(r), E["beatFresh"])
        // stale vault selected: dead grey heart, no beat
        r.draw(2.3f, 0f, 60f, 1.4f, layout, -1, 1f, true)
        check("staleHeart", pixels(r), E["staleHeart"])
        // half-stale, lobe 0 selected and zoomed past 2 (flipped sprites, 2px specks/drips)
        r.draw(7.1f, 30f, -70f, 2.4f, layout, 0, 0.5f, false)
        check("selectedZoom", pixels(r), E["selectedZoom"])
        r.draw(11.9f, -40f, 20f, 0.6f, layout, 3, 0.25f, false)
        check("selectedFar", pixels(r), E["selectedFar"])
        // show-all on: each portal's stars are its groups and notes
        r.contents = intArrayOf(0, 12, 2, 3, 0, 0, 0, 1)
        r.draw(5.2f, 0f, 0f, 0.85f, layout, -1, 0f, false)
        check("showAll", pixels(r), E["showAll"])
        r.contents = null
        // half way through flying into portal 1: it grows past the zoom cap
        r.flyIdx = 1; r.flyP = 0.5f
        r.draw(5.2f, layout.lx[1], layout.ly[1], 5f, layout, -1, 0f, false)
        check("flyIn", pixels(r), E["flyIn"])
        r.flyIdx = -1
        // lobe 2's world: a group's constellation of three stars inside a ring of its shade
        val dim = buildLayout(lobes, placeIdeas(lobes, branches, ideas, focus = 2))
        r.draw(5.2f, 0f, 40f, 1f, dim, 1, 0f, false)
        check("dimension", pixels(r), E["dimension"])
    }

    @Test fun focusedMapShowsNothingOfTheCoreOrOtherLobes() {
        val a = newRenderer(); a.resize(180, 390)
        a.draw(2.3f, 0f, -20f, 1f, layout, 0, 0.5f, false)
        val base = pixels(a)
        // the same frame with the core sprite swapped and every other lobe moved: nothing may change
        val moved = buildLayout(lobes, placeIdeas(lobes, branches, ideas))
        for (i in 1 until moved.nL) moved.lx[i] += 9f
        val b = newRenderer(core = Res.drawable.brain_lobe); b.resize(180, 390)
        b.draw(2.3f, 0f, -20f, 1f, moved, 0, 0.5f, false)
        assertTrue("a focused map still draws the core or another lobe", base.contentEquals(pixels(b)))
    }

    /** Learn (new): the small learn brain with three moons beside the core; without it the overview stays exactly as it was. */
    @Test fun learnBrain() {
        val r = newRenderer(); r.resize(180, 390)
        val spot = learnSpot((0 until layout.nL).map { Offset(layout.lx[it], layout.ly[it]) })
        r.learn = floatArrayOf(spot.x, spot.y, 3f)
        r.draw(0.5f, 0f, 0f, 0.85f, layout, -1, 0f, false)
        check("learnBrain", pixels(r), E["learnBrain"])
        r.learn = null
        r.draw(0.5f, 0f, 0f, 0.85f, layout, -1, 0f, false)
        check("overview", pixels(r), E["overview"])
    }

    @Test fun resizeAndBlit() {
        val r = newRenderer()
        r.resize(60, 90)
        r.draw(3.3f, 0f, 0f, 0.7f, layout, 1, 0f, false)
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
        val E = mapOf(
            "beatFresh" to -1355989786, "blit3" to -417458193, "dimension" to -1725325892, "flyIn" to -1287240568, "glowLabel" to 1509847742, "learnBrain" to 1539578251, "overview" to 1714630548, "selectedFar" to -1088837572, "selectedZoom" to -1813238531, "showAll" to 1955788307, "small" to -1724423207, "staleHeart" to -1729973114,
        )
    }
}
