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
import app.vesper.res.brain_lobe
import app.vesper.res.brain_main
import app.vesper.res.vault_skull
import app.vesper.res.vault_skull_dead
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
 * recentSpark is new: magenta recent cores and the open pulse.
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
        BrainRenderer(img(Res.drawable.brain_main), img(Res.drawable.brain_lobe), img(Res.drawable.vault_skull), img(Res.drawable.vault_skull_dead))
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
        // t = 2.3: beatPh just past 1, so the skull's eyes flare
        r.draw(2.3f, 4f, -6f, 0.85f, layout, -1, 0f, false)
        check("flareFresh", pixels(r), E["flareFresh"])
        // stale vault selected: dead skull, flare suppressed
        r.draw(2.3f, 0f, 60f, 1.4f, layout, -1, 1f, true)
        check("staleSun", pixels(r), E["staleSun"])
        // half-stale, lobe 0 selected and zoomed past 2 (flipped sprites, 2px specks/drips)
        r.draw(7.1f, 30f, -70f, 2.4f, layout, 0, 0.5f, false)
        check("selectedZoom", pixels(r), E["selectedZoom"])
        r.draw(11.9f, -40f, 20f, 0.6f, layout, 3, 0.25f, false)
        check("selectedFar", pixels(r), E["selectedFar"])
        // deep in a group: nodes stopped growing, neurons ringed by how much is written (the empty ones have a dim core, no rings)
        val g = placeIdeas(lobes, branches, ideas).branches.first { it.branch.uid == "read" }.pos
        r.draw(5.2f, g.x, g.y, 6f, layout, 1, 0f, false)
        check("groupDeep", pixels(r), E["groupDeep"])
        // now = 5100 makes the lobe-2 ideas "touched this week": magenta cores, and a pulse half way along the veins to the first of them
        val recent = buildLayout(lobes, placeIdeas(lobes, branches, ideas), now = 5100L)
        r.draw(5.2f, g.x, g.y, 6f, recent, 1, 0f, false, spark = recent.istart[1], sparkP = 0.5f)
        check("recentSpark", pixels(r), E["recentSpark"])
        // idea positions handed to the label pass
        assertEquals(layout.nI, (0 until layout.nI).count { r.ipx[it] != 0f || r.ipy[it] != 0f })
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
        val c = Color(0xFFD45AAB)
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
            "overview" to 1418645582, "flareFresh" to 212345404, "staleSun" to -300477141,
            "selectedZoom" to -507051428, "selectedFar" to 1771203545, "small" to -165067672,
            "blit3" to 117785384, "recentSpark" to 1686787863, "glowLabel" to -66391980, "groupDeep" to 828068503,
        )
    }
}
