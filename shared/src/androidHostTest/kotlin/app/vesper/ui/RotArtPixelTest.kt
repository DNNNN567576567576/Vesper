package app.vesper.ui

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden hashes of RotArt output. The expected values were captured from the pre-port
 * android.graphics implementation, so a match means the Compose port draws the same pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RotArtPixelTest {

    private fun pixels(pix: Pix): IntArray {
        val out = IntArray(pix.w * pix.h)
        pix.bmp.readPixels(out)
        return out
    }

    private fun blitPixels(pix: Pix, left: Int, top: Int, px: Int, tw: Int, th: Int): IntArray {
        val target = ImageBitmap(tw, th)
        pix.blit(Canvas(target), left, top, px)
        val out = IntArray(tw * th)
        target.readPixels(out)
        return out
    }

    private fun check(name: String, px: IntArray, expected: Int) {
        val distinct = px.toSet().size
        println("RotArtPixelTest $name hash=${px.contentHashCode()} distinct=$distinct")
        assertTrue("$name drew almost nothing ($distinct colours); native graphics off?", distinct > 3)
        if (px.contentHashCode() != expected) mismatches += "$name: expected $expected, got ${px.contentHashCode()}"
    }

    private val mismatches = mutableListOf<String>()

    @After fun noMismatches() = assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())

    @Test fun paper() {
        val expected = mapOf(1L to -1897059245, 42L to -300440831, 123456789L to 1602755514, -7L to 1367318044)
        for ((id, h) in expected) {
            val pix = Pix(120, 80)
            renderPaper(pix, id)
            check("paper$id", pixels(pix), h)
        }
        val dlg = Pix(140, 60)
        renderPaper(dlg, 99L, tone = 0, shadowRgb = 0x07030B)
        check("paperDialog", pixels(dlg), -998422109)
    }

    @Test fun blit() {
        val p = Pix(30, 20)
        renderPaper(p, 5L)
        check("blit", blitPixels(p, 2, 1, 3, 100, 70), -1056191160)
    }
}
