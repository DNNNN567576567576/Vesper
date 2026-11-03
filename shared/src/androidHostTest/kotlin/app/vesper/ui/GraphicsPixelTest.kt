package app.vesper.ui

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Golden hashes for the pixel renderers in Moons, Screens (sigil/match) and Shrine (burned paper).
 * Expected values were captured from the pre-port android.graphics code (M2 part 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphicsPixelTest {

    private val mismatches = mutableListOf<String>()
    @After fun noMismatches() = assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())

    private fun px(b: ImageBitmap): IntArray = IntArray(b.width * b.height).also { b.readPixels(it) }
    private fun px(pix: Pix) = px(pix.bmp)

    private fun check(name: String, px: IntArray, expected: Int?) {
        val distinct = px.toSet().size
        println("GraphicsPixelTest $name hash=${px.contentHashCode()} distinct=$distinct")
        assertTrue("$name drew almost nothing ($distinct colours); native graphics off?", distinct > 1)
        if (px.contentHashCode() != expected) mismatches += "$name: expected $expected, got ${px.contentHashCode()}"
    }

    @Test fun moons() {
        val d1 = LocalDate.of(2026, 11, 3)
        val d2 = LocalDate.of(2026, 11, 20)
        val d3 = LocalDate.of(2025, 1, 1)
        for ((i, d) in listOf(d1, d2, d3).withIndex()) {
            for (n in listOf(11, 12, 56)) check("moon$i/$n", px(dayMoon(d, n)), E["moon$i/$n"])
        }
        check("moonPink", px(moonBitmap(d1, 12, 0xFF9AD8, 0xFFD0F0, 0xD45AAB)), E["moonPink"])
        check("cloud1", px(cloudBitmap(44, 14, 1)), E["cloud1"])
        check("cloud2", px(cloudBitmap(60, 18, 2)), E["cloud2"])
        check("cloud3", px(cloudBitmap(50, 15, 3)), E["cloud3"])
    }

    @Test fun moonsBackdrop() {
        val moon = dayMoon(LocalDate.of(2026, 11, 3), 56)
        val clouds = arrayOf(cloudBitmap(44, 14, 1), cloudBitmap(60, 18, 2), cloudBitmap(50, 15, 3))
        val pix = Pix(1, 1)
        drawMoonsBackdrop(pix, 0f, 90, 160, moon, clouds)
        check("backdrop0", px(pix), E["backdrop0"])
        drawMoonsBackdrop(pix, 7.3f, 90, 160, moon, clouds)
        check("backdrop1", px(pix), E["backdrop1"])
    }

    @Test fun sigilAndMatch() {
        val p = Pix(41, 41)
        drawSigil(p, 1.7f, 0f, -10f, back = false, quill = false); check("sigilPlus", px(p), E["sigilPlus"])
        drawSigil(p, 2.4f, 1f, 2.3f, back = false, quill = false); check("sigilPressed", px(p), E["sigilPressed"])
        drawSigil(p, 0.6f, 0f, -10f, back = true, quill = false); check("sigilBack", px(p), E["sigilBack"])
        drawSigil(p, 3.1f, 0.3f, -10f, back = false, quill = true); check("sigilQuill", px(p), E["sigilQuill"])
        drawMatch(p, 1.7f, 0f); check("match", px(p), E["match"])
        drawMatch(p, 2.9f, 1f); check("matchPressed", px(p), E["matchPressed"])
    }

    /** Sigil blitted to a size that is not a multiple of 41, as on a phone with density 2.625. */
    @Test fun sigilScaledBlit() {
        val p = Pix(41, 41)
        drawSigil(p, 1.7f, 0f, -10f, back = false, quill = false)
        check("sigilBlit215", blitScaled(p, 215), E["sigilBlit215"])
    }

    @Test fun burnedPaper() {
        for (seed in listOf(3, 77, 1234)) {
            val pp = BurnedPaper.render(seed, 60, 36)
            check("paper$seed", px(pp.bmp), E["paper$seed"])
            check("scorch$seed", px(pp.scorch), E["scorch$seed"])
            check("mask$seed", px(pp.mask), E["mask$seed"])
            check("embers$seed", pp.ex + pp.ey + pp.eh2.map { it.toBits() }, E["embers$seed"])
        }
    }

    @Test fun postCard() {
        for (seed in listOf(3, 77)) {
            val pp = BurnedPaper.render(seed, 60, 36)
            val paints = PaperPaints()
            val behind = card(200, 120) { paints.drawBehind(it, pp, 60, 36, 3, 5f) }
            check("cardBehind$seed", behind, E["cardBehind$seed"])
            for ((j, t) in listOf(1.7f, 4.1f).withIndex()) {
                val front = card(200, 120, fill = 0xFF336699.toInt()) { paints.drawFront(it, pp, 60, 36, 3, t) }
                check("cardFront$seed/$j", front, E["cardFront$seed/$j"])
            }
        }
    }

    // ---- target-surface helpers: the only part that differs between the android.graphics and Compose versions ----

    private fun card(w: Int, h: Int, fill: Int = 0, draw: (Canvas) -> Unit): IntArray {
        val img = ImageBitmap(w, h)
        val cv = Canvas(img)
        if (fill != 0) cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color(fill); blendMode = BlendMode.Src })
        draw(cv)
        return px(img)
    }

    private fun blitScaled(p: Pix, size: Int): IntArray {
        val img = ImageBitmap(size, size)
        p.blitTo(Canvas(img), 0, 0, size, size)
        return px(img)
    }

    private operator fun IntArray.plus(f: List<Int>) = this.toList().plus(f).toIntArray()

    companion object {
        // Recorded on the old android.graphics code.
        val E: Map<String, Int> = mapOf(
            "backdrop0" to -74743178,
            "backdrop1" to -43940180,
            "cardBehind3" to -1263291550,
            "cardBehind77" to 1369065320,
            "cardFront3/0" to -1323597290,
            "cardFront3/1" to 1459581486,
            "cardFront77/0" to -1755686277,
            "cardFront77/1" to -2100281103,
            "cloud1" to 1931935413,
            "cloud2" to 1416066433,
            "cloud3" to 1847094619,
            "embers1234" to 2081287220,
            "embers3" to 782741617,
            "embers77" to 1897071284,
            "mask1234" to -950500830,
            "mask3" to 534253699,
            "mask77" to 813130004,
            "match" to -851864434,
            "matchPressed" to -1176749081,
            "moon0/11" to 1720026238,
            "moon0/12" to -720933297,
            "moon0/56" to -1651511682,
            "moon1/11" to -626039604,
            "moon1/12" to -1653674958,
            "moon1/56" to 112537335,
            "moon2/11" to 1808052492,
            "moon2/12" to 1061382977,
            "moon2/56" to -1898309417,
            "moonPink" to -1502372867,
            "paper1234" to 1553940161,
            "paper3" to 1383095980,
            "paper77" to 1645454144,
            "scorch1234" to -1164893181,
            "scorch3" to 949875924,
            "scorch77" to -1088830581,
            "sigilBack" to -816877388,
            "sigilBlit215" to 1791923250,
            "sigilPlus" to 289087112,
            "sigilPressed" to 2080739057,
            "sigilQuill" to 737037646,
        )
    }
}
