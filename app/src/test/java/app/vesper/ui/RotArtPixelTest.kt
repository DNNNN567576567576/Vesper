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
        val expected = mapOf(1L to -1897059245, 42L to -300440831, 123456789L to 1472406853, -7L to 1367318044)
        for ((id, h) in expected) {
            val pix = Pix(120, 80)
            renderPaper(pix, id)
            check("paper$id", pixels(pix), h)
        }
        val dlg = Pix(140, 60)
        renderPaper(dlg, 99L, tone = 0, shadowRgb = 0x07030B)
        check("paperDialog", pixels(dlg), -998422109)
    }

    @Test fun notesWall() {
        val r = NotesWallRenderer()
        r.draw(180, 390, 0f, 0f)
        check("notesWall0", pixels(r.pix), -1829229524)
        r.draw(180, 390, 3.7f, 25f)
        check("notesWall1", pixels(r.pix), 2021503077)
    }

    @Test fun candleWall() {
        val r = CandleWallRenderer()
        r.draw(180, 390, 5.2f, 0f)
        check("candleWall", pixels(r.pix), 1970553070)
    }

    @Test fun candles() {
        val c = CandleRenderer()
        c.begin(40, 130)
        c.row(5L, 30, 40, 1.3f, -1f)
        c.row(9L, 70, 40, 1.3f, 0.8f)
        c.stub(11L, 100, 40, 1.3f, 0.2f)
        c.stub(12L, 120, 40, 2.1f, -1f)
        check("candles", pixels(c.pix), 1011685068)
    }

    @Test fun pin() {
        val p = Pix(PIN_W, PIN_H)
        drawPin(p, 0.9f, 2)
        check("pin", pixels(p), 1802293463)
    }

    @Test fun blit() {
        val p = Pix(30, 20)
        renderPaper(p, 5L)
        check("blit", blitPixels(p, 2, 1, 3, 100, 70), -1056191160)
    }
}
