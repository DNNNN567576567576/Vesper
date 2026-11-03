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
import androidx.compose.ui.res.imageResource
import app.vesper.R
import app.vesper.data.Idea
import app.vesper.data.Lobe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden hashes of the Brain map renderer and the cached label glow.
 * Expected values were captured from the pre-port android.graphics BrainRenderer (M2 part 3).
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
    private fun newRenderer(): BrainRenderer {
        val res = RuntimeEnvironment.getApplication().resources
        fun img(id: Int) = ImageBitmap.imageResource(res, id)
        return BrainRenderer(img(R.drawable.brain_main), img(R.drawable.brain_lobe), img(R.drawable.vault_skull), img(R.drawable.vault_skull_dead))
    }
    private fun pixels(r: BrainRenderer): IntArray = IntArray(r.W * r.H).also { r.pix.bmp.readPixels(it) }
    private fun blitPixels(r: BrainRenderer, px: Int): IntArray {
        val target = ImageBitmap(r.W * px + 7, r.H * px + 5)
        r.blit(Canvas(target), px)
        return IntArray(target.width * target.height).also { target.readPixels(it) }
    }
    // ----

    private val lobes = listOf(
        Lobe(id = 1, name = "Hollow", colorIndex = 0, slot = 0),
        Lobe(id = 2, name = "Ash", colorIndex = 1, slot = 1),
        Lobe(id = 5, name = "", colorIndex = 4, slot = 2),
        Lobe(id = 9, name = "Marrow", colorIndex = 3, slot = 7),
    )
    private val ideas = buildList {
        for (k in 1..12) add(Idea(id = k.toLong(), lobeId = 1, title = "idea $k", body = "", updatedAt = 1000L + k * 37 % 11))
        for (k in 20..22) add(Idea(id = k.toLong(), lobeId = 2, title = "", body = "b$k", updatedAt = 5000L - k))
        add(Idea(id = 40, lobeId = 9, title = "lone", body = "", updatedAt = 1L))
    }
    private val layout = buildLayout(lobes, placeIdeas(lobes, ideas))

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
            "overview" to 1157042727, "flareFresh" to -1817413383, "staleSun" to 561859433,
            "selectedZoom" to 990412822, "selectedFar" to 580163640, "small" to 1302377833,
            "blit3" to 2073209247, "glowLabel" to -66391980,
        )
    }
}
