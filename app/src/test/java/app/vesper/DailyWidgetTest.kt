package app.vesper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The widget's layout rules, and previews of the whole widget (header + grid, drawn the way the widget draws them) for a look. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DailyWidgetTest {
    private val big = 360f to 240f   // about 5 x 3 cells
    private val real = 320f to 216f // the phone's placed widget
    private val small = 180f to 120f // about 3 x 2 cells

    private val sizes = listOf(real, big)

    @Test fun balancedRowsAreAsEvenAsPossible() {
        assertEquals(listOf(4, 3), balancedRows(7, 4))
        assertEquals(listOf(3, 2), balancedRows(5, 4))
        assertEquals(listOf(5, 5), balancedRows(10, 5))
        assertEquals(listOf(4, 3, 3), balancedRows(10, 4))
        assertEquals(listOf(4), balancedRows(4, 4))
        assertEquals(emptyList<Int>(), balancedRows(0, 4))
    }

    @Test fun everyRowIsWithinOneOfTheOthersAndEverythingFitsWithoutScrolling() {
        for (size in sizes) for (n in listOf(1, 3, 4, 5, 7, 10, 12, 20, 40)) {
            val g = widgetLayout(n, size.first, size.second)
            val (bw, bh) = widgetBody(size.first, size.second)
            assertTrue("n=$n $size rows ${g.rows}", g.rows.max() - g.rows.min() <= 1)
            assertEquals("n=$n $size", n, g.rows.sum() - (if (g.more > 0) 1 else 0) + g.more)
            assertTrue("n=$n $size width", g.rows.max() * g.cellW <= bw + 0.01f)
            assertTrue("n=$n $size height", g.rows.size * g.cellH <= bh + 0.01f)
            assertTrue("n=$n $size scale ${g.scale}", g.scale >= 1.5f && g.cellW >= 48f - 0.01f)
        }
    }

    @Test fun sevenIsFourOverThree() {
        for (size in sizes) assertEquals(listOf(4, 3), widgetLayout(7, size.first, size.second).rows)
    }

    @Test fun fewCandlesGetBigOnes() {
        for (n in 1..3) assertTrue("n=$n", widgetLayout(n, big.first, big.second).scale >= 5f)
    }

    @Test fun plusNOnlyWhenItCannotFit() {
        for (size in sizes) for (n in listOf(1, 3, 4, 5, 7, 10, 12, 20, 40, 100)) {
            val g = widgetLayout(n, size.first, size.second)
            if (g.more == 0) { assertEquals(n, g.shown); continue }
            assertEquals(n, g.shown + g.more)
            val one = widgetLayout(g.shown + 2, size.first, size.second) // one more shown item (plus the +N cell) must not fit
            assertTrue("n=$n $size", one.more > 0 || g.shown + 2 > n)
        }
        assertTrue(widgetLayout(40, real.first, real.second).more > 0)
        assertEquals(0, widgetLayout(12, real.first, real.second).more)
    }

    @Test fun smallWidgetKeepsEveryCellReadable() {
        for (n in listOf(1, 3, 4, 7, 12, 20)) {
            val g = widgetLayout(n, small.first, small.second)
            assertTrue("n=$n scale ${g.scale}", g.scale >= 1.5f && g.cellW >= 48f - 0.01f)
        }
    }

    @Test fun zeroItemsHasNoRows() {
        assertEquals(emptyList<Int>(), widgetLayout(0, big.first, big.second).rows)
    }

    @Test fun bodyPictureStaysUnderTheMemoryCap() {
        val (bw, bh) = widgetBody(real.first, real.second)
        val d = widgetBodyDensity(bw, bh, 3.75f)
        assertTrue(d < 3.75f); assertTrue(bw * d * bh * d * 4 <= 2_500_000f * 1.01f)
        assertEquals(2f, widgetBodyDensity(bw, bh, 2f), 0f)
    }

    @Test fun frameHasSteppedCornersAndTwoToneRing() {
        // 10 x 10 cells: corner cells cut, then edge, then the dark line, then panel
        assertEquals(listOf(0, 0, 1), (0..2).map { WidgetArt.frameDepth(it, 0, 10, 10) })
        assertEquals(listOf(0, 1, 2), (0..2).map { WidgetArt.frameDepth(it, 1, 10, 10) })
        assertEquals(listOf(1, 2, 3), (0..2).map { WidgetArt.frameDepth(it, 5, 10, 10) })
        assertEquals(3, WidgetArt.frameDepth(5, 5, 10, 10))
        val b = WidgetArt.frame(100f, 60f, 2f)
        assertEquals(0, b.getPixel(0, 0)) // the inset margin stays transparent
        assertEquals(0xFF9A6CFF.toInt(), b.getPixel(b.width / 2, 6))
        assertEquals(0xFF0B0614.toInt(), b.getPixel(b.width / 2, b.height / 2))
    }

    private val names = listOf("Vitamin D & Zinc", "Brush teeth ☀", "Brush teeth ☾", "Water", "Stretch", "Read ten pages of a book", "Walk", "Meditate", "Journal", "Pills", "Floss", "Call mom")

    /** The Android 12+ frame, laid out the way WidgetArt.layoutFrame sizes the views: inset, panel, strips, corners. */
    private fun drawFrame(c: Canvas, w: Int, h: Int, d: Float) {
        val u = WidgetArt.unitPx(d); val i = WidgetArt.insetPx(u, 22f * d); val x1 = w - i; val y1 = h - i
        val p = Paint()
        fun r(l: Int, t: Int, rr: Int, b: Int, col: Int) { p.color = col; c.drawRect(l.toFloat(), t.toFloat(), rr.toFloat(), b.toFloat(), p) }
        val e = 0xFF9A6CFF.toInt(); val ln = 0xFF3A2066.toInt()
        r(i + u, i + u, x1 - u, y1 - u, 0xFF0B0614.toInt())
        r(i + 3 * u, i, x1 - 3 * u, i + u, e); r(i + 3 * u, i + u, x1 - 3 * u, i + 2 * u, ln)
        r(i + 3 * u, y1 - u, x1 - 3 * u, y1, e); r(i + 3 * u, y1 - 2 * u, x1 - 3 * u, y1 - u, ln)
        r(i, i + 3 * u, i + u, y1 - 3 * u, e); r(i + u, i + 3 * u, i + 2 * u, y1 - 3 * u, ln)
        r(x1 - u, i + 3 * u, x1, y1 - 3 * u, e); r(x1 - 2 * u, i + 3 * u, x1 - u, y1 - 3 * u, ln)
        c.drawBitmap(WidgetArt.corner(u, false, false), i.toFloat(), i.toFloat(), null); c.drawBitmap(WidgetArt.corner(u, true, false), (x1 - 3 * u).toFloat(), i.toFloat(), null)
        c.drawBitmap(WidgetArt.corner(u, false, true), i.toFloat(), (y1 - 3 * u).toFloat(), null); c.drawBitmap(WidgetArt.corner(u, true, true), (x1 - 3 * u).toFloat(), (y1 - 3 * u).toFloat(), null)
    }

    @Test fun cornersAreHardEdgedWholeUnitBlocks() {
        val u = WidgetArt.unitPx(3.75f); assertEquals(11, u)
        val tl = WidgetArt.corner(u, false, false)
        assertEquals(0, tl.getPixel(0, 0)); assertEquals(0, tl.getPixel(2 * u - 1, u / 2))
        assertEquals(0xFF9A6CFF.toInt(), tl.getPixel(2 * u, 0)); assertEquals(0xFF9A6CFF.toInt(), tl.getPixel(u + 1, u + 1))
        assertEquals(0xFF3A2066.toInt(), tl.getPixel(2 * u + 3, u + 3)); assertEquals(0xFF0B0614.toInt(), tl.getPixel(3 * u - 1, 3 * u - 1))
        assertEquals(0, WidgetArt.corner(u, true, true).getPixel(3 * u - 1, 3 * u - 1))
        for (y in 0 until 3 * u) for (x in 0 until 3 * u) assertTrue(tl.getPixel(x, y) in setOf(0, 0xFF9A6CFF.toInt(), 0xFF3A2066.toInt(), 0xFF0B0614.toInt()))
    }

    /** Draws a whole widget: panel, header, then the body picture, as the launcher shows them. */
    private fun preview(n: Int, wDp: Float, hDp: Float, d: Float): Bitmap {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val items = (0 until n).map { names[it % names.size] to (it % 3 == 0 || it == 1) }
        val bmp = Bitmap.createBitmap((wDp * d).toInt(), (hDp * d).toInt(), Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        drawFrame(c, bmp.width, bmp.height, d)
        c.drawBitmap(WidgetArt.header(ctx, wDp - 2 * WIDGET_PAD, items.count { it.second }, n, d), WIDGET_PAD * d, WIDGET_PAD * d, null)
        val (bw, bh) = widgetBody(wDp, hDp)
        val top = (WIDGET_PAD + WIDGET_HEADER) * d
        val body = if (n == 0) WidgetArt.empty(ctx, bw, bh, widgetBodyDensity(bw, bh, d))
            else WidgetArt.body(ctx, items, widgetLayout(n, wDp, hDp), bw, bh, widgetBodyDensity(bw, bh, d))
        c.drawBitmap(body, null, RectF(WIDGET_PAD * d, top, (WIDGET_PAD + bw) * d, top + bh * d), null)
        return bmp
    }

    private fun save(bmp: Bitmap, file: File) { file.parentFile!!.mkdirs(); file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    @Test fun renderPreviews() {
        val dir = System.getenv("WIDGET_PREVIEW_DIR") ?: return // set by the caller; no files written otherwise
        for (n in listOf(1, 3, 5, 7, 12, 20)) save(preview(n, 320f, 216f, 3.75f), File(dir, "count-$n.png")) // the phone's placed size
        for (n in listOf(1, 3, 5, 7, 12)) save(preview(n, big.first, big.second, 3f), File(dir, "count-$n-5x3.png"))
        save(preview(7, 320f, 216f, 2f), File(dir, "widget_preview.png"))
    }
}
