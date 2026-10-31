package app.vesper.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.SparseArray
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

// Shared low-res pixel art for the Notes ("rotting pages") and Tasks ("purple candles") screens.

/** Device pixels per low-res pixel; matches the Brain map. */
@Composable
internal fun rememberRotPxSize(): Float {
    val density = LocalDensity.current.density
    return remember(density) { round(2.28f * density).coerceAtLeast(1f) }
}

/** Seconds since this composable entered the composition, updated every frame. Read it in draw lambdas only. */
@Composable
internal fun rememberRotTime(): FloatState {
    val s = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var t0 = 0L
        while (true) {
            withFrameNanos { n ->
                if (t0 == 0L) t0 = n
                s.floatValue = (n - t0) / 1_000_000_000f
            }
        }
    }
    return s
}

internal fun rotRnd(v: Float): Int = floor(v + 0.5f).toInt()

/** Stateless hash to [0,1); lets per-item art be re-derived every frame without allocating an RNG. */
internal fun hf(a: Int, b: Int): Float {
    var x = a * 374761393 + b * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x ushr 8) / 16777216f
}

/** mulberry32, same as the Brain map. */
internal class RotRng(seed: Int) {
    private var s = seed
    fun next(): Float {
        s += 0x6D2B79F5
        var t = s
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return (((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0).toFloat()
    }
}

private val GLOW_SPRITE: Bitmap by lazy {
    Bitmap.createBitmap(
        IntArray(64 * 64) { i ->
            val dx = (i % 64 - 31.5f) / 32f
            val dy = (i / 64 - 31.5f) / 32f
            val a = (1f - hypot(dx, dy)).coerceIn(0f, 1f)
            ((a * 255f).toInt() shl 24) or 0xFFFFFF
        },
        64, 64, Bitmap.Config.ARGB_8888,
    )
}

/** A low-res ARGB bitmap with integer-pixel drawing helpers. Reused every frame; nothing here allocates per call. */
internal class Pix(w: Int = 1, h: Int = 1) {
    var bmp: Bitmap = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        private set
    private var cv = android.graphics.Canvas(bmp)
    val canvas: android.graphics.Canvas get() = cv
    val w: Int get() = bmp.width
    val h: Int get() = bmp.height

    /** Master alpha multiplier for everything drawn afterwards. */
    var ma = 1f

    private val px = Paint().apply { isAntiAlias = false; isFilterBitmap = false; isDither = false }
    private val clr = Paint().apply {
        isAntiAlias = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val blitPaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val glowAdd = Paint().apply {
        isAntiAlias = false; isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val glowOver = Paint().apply { isAntiAlias = false; isFilterBitmap = true }
    private val filters = SparseArray<PorterDuffColorFilter>()
    private val rectF = RectF()
    private val dst = Rect()

    /** Returns true if the bitmap was recreated (contents lost). */
    fun resize(nw: Int, nh: Int): Boolean {
        val ww = max(1, nw)
        val hh = max(1, nh)
        if (ww == bmp.width && hh == bmp.height) return false
        bmp = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        cv = android.graphics.Canvas(bmp)
        return true
    }

    fun clear() = bmp.eraseColor(0)
    fun fill(rgb: Int) = bmp.eraseColor(0xFF000000.toInt() or rgb)

    fun dot(x: Int, y: Int, ww: Int, hh: Int, rgb: Int, a: Float = 1f) {
        if (x + ww <= 0 || y + hh <= 0 || x >= bmp.width || y >= bmp.height) return
        val al = (a * ma * 255f).toInt().coerceIn(0, 255)
        if (al == 0) return
        px.color = (al shl 24) or (rgb and 0xFFFFFF)
        cv.drawRect(x.toFloat(), y.toFloat(), (x + ww).toFloat(), (y + hh).toFloat(), px)
    }

    fun erase(x: Int, y: Int, ww: Int, hh: Int) {
        if (x + ww <= 0 || y + hh <= 0 || x >= bmp.width || y >= bmp.height) return
        cv.drawRect(x.toFloat(), y.toFloat(), (x + ww).toFloat(), (y + hh).toFloat(), clr)
    }

    /** Pixel "circle": two overlapping rects (plus shape), as in the Brain map. */
    fun disc(x: Float, y: Float, r: Float, rgb: Int, a: Float = 1f, erase: Boolean = false) {
        if (r < 0.8f) {
            if (erase) erase(rotRnd(x), rotRnd(y), 1, 1) else dot(rotRnd(x), rotRnd(y), 1, 1, rgb, a)
            return
        }
        val d = max(1, rotRnd(r * 2f))
        val e = max(1, rotRnd(r * 1.3f))
        val o = rotRnd((d - e) / 2f)
        val ax = rotRnd(x - r)
        val by = rotRnd(y - r)
        if (erase) {
            erase(ax, by + o, d, e); erase(ax + o, by, e, d)
        } else {
            dot(ax, by + o, d, e, rgb, a); dot(ax + o, by, e, d, rgb, a)
        }
    }

    private fun filterFor(rgb: Int): PorterDuffColorFilter {
        var f = filters.get(rgb)
        if (f == null) {
            f = PorterDuffColorFilter(0xFF000000.toInt() or rgb, PorterDuff.Mode.SRC_IN)
            filters.put(rgb, f)
        }
        return f
    }

    /** Radial glow; additive by default, normal blend when [add] is false. */
    fun glow(cx: Float, cy: Float, r: Float, rgb: Int, a: Float, add: Boolean = true) {
        val al = (a * ma * 255f).toInt().coerceIn(0, 255)
        if (al == 0 || r < 0.5f) return
        if (cx + r < 0 || cy + r < 0 || cx - r > bmp.width || cy - r > bmp.height) return
        val p = if (add) glowAdd else glowOver
        p.colorFilter = filterFor(rgb)
        p.alpha = al
        rectF.set(cx - r, cy - r, cx + r, cy + r)
        cv.drawBitmap(GLOW_SPRITE, null, rectF, p)
    }

    /** Scales up to [pxSize] device px per pixel with no filtering, top-left at ([left],[top]). */
    fun blit(target: android.graphics.Canvas, left: Int, top: Int, pxSize: Int) {
        dst.set(left, top, left + bmp.width * pxSize, top + bmp.height * pxSize)
        target.drawBitmap(bmp, null, dst, blitPaint)
    }
}

// ---------------------------------------------------------------------------------------------
// Wall backdrop (fog, stains, veins, specks, dither, vignette)
// ---------------------------------------------------------------------------------------------

enum class WallKind { Notes, Candle, None }

/** Per-screen backdrop, placed behind everything in the root. Brain draws its own opaque map. */
@Composable
fun WallBackdrop(kind: WallKind) {
    Crossfade(targetState = kind, animationSpec = tween(170), label = "wall") { k ->
        when (k) {
            WallKind.Notes -> WallLayer(true)
            WallKind.Candle -> WallLayer(false)
            WallKind.None -> Unit
        }
    }
}

@Composable
private fun WallLayer(notes: Boolean) {
    val pxSize = rememberRotPxSize()
    val time = rememberRotTime()
    val renderer = remember(notes) { WallRenderer(notes) }
    Canvas(Modifier.fillMaxSize()) {
        val t = time.floatValue
        val w = ceil(size.width / pxSize).toInt()
        val h = ceil(size.height / pxSize).toInt()
        if (w <= 0 || h <= 0) return@Canvas
        renderer.draw(w, h, t)
        drawIntoCanvas { renderer.pix.blit(it.nativeCanvas, 0, 0, pxSize.toInt()) }
    }
}

private class WallRenderer(private val notes: Boolean) {
    val pix = Pix()

    private val bg = if (notes) 0x0C0409 else 0x050208
    private val fogA = if (notes) 0.3f else 0.2f
    private val fogC0 = if (notes) 0x4A1440 else 0x2A1040
    private val fogC1 = if (notes) 0x2A0C30 else 0x1A0A28
    private val speckEvery = if (notes) 2 else 3
    private val vigA = if (notes) 0.75f else 0.8f

    private val stX = FloatArray(7)
    private val stY = FloatArray(7)
    private val stR = FloatArray(7)
    private val spX = IntArray(260)
    private val spY = IntArray(260)
    private val spC = IntArray(260)
    private val veinN = 14 * 10 * 5
    private val vnX = IntArray(veinN)
    private val vnY = IntArray(veinN)
    private val vnC = IntArray(veinN)

    private val ditherPaint = Paint().apply {
        isAntiAlias = false; isFilterBitmap = false
        shader = BitmapShader(
            Bitmap.createBitmap(intArrayOf(0xFF000000.toInt(), 0, 0, 0xFF000000.toInt()), 2, 2, Bitmap.Config.ARGB_8888),
            Shader.TileMode.REPEAT, Shader.TileMode.REPEAT,
        )
        alpha = (0.18f * 255f).toInt()
    }
    private val vignette = Paint().apply { isAntiAlias = false }

    private fun build(w: Int, h: Int) {
        val r = RotRng(if (notes) 4242 else 777)
        for (i in 0 until 7) {
            stX[i] = r.next() * w; stY[i] = r.next() * h; stR[i] = 20f + r.next() * 30f
        }
        if (notes) {
            var n = 0
            for (v in 0 until 14) {
                var x = r.next() * w
                var y = r.next() * h
                var a = r.next() * 2f * PI.toFloat()
                val col = if (v % 2 == 0) 0x2A0C26 else 0x3A1030
                for (s in 0 until 10) {
                    a += (r.next() - 0.5f) * 1.1f
                    for (k in 0 until 5) {
                        x += cos(a); y += sin(a)
                        vnX[n] = rotRnd(x); vnY[n] = rotRnd(y); vnC[n] = col
                        n++
                    }
                }
            }
        }
        val spk = intArrayOf(0x1E0A24, 0x4A1650, 0x7A2A6A)
        for (i in 0 until 260) {
            spX[i] = (r.next() * w).toInt(); spY[i] = (r.next() * h).toInt(); spC[i] = spk[(r.next() * 3f).toInt().coerceAtMost(2)]
        }
        val c0 = android.graphics.Color.argb(0, 4, 1, 8)
        val c1 = android.graphics.Color.argb((vigA * 255f).toInt(), 4, 1, 8)
        vignette.shader = RadialGradient(
            w / 2f, h * 0.45f, h * 0.66f, intArrayOf(c0, c0, c1), floatArrayOf(0f, 0.2f / 0.66f, 1f), Shader.TileMode.CLAMP,
        )
    }

    fun draw(w: Int, h: Int, t: Float) {
        if (pix.resize(w, h) || stR[0] == 0f) build(w, h)
        pix.fill(bg)
        val sxk = w / 180f
        val syk = h / 390f
        for (i in 0 until 5) {
            pix.glow(
                w / 2f + sin(t * 0.05f + i * 1.3f) * 70f * sxk,
                h / 2f + cos(t * 0.04f + i * 1.7f) * 140f * syk,
                75f * sxk.coerceAtLeast(0.8f), if (i % 2 == 0) fogC0 else fogC1, fogA,
            )
        }
        for (i in 0 until 7) pix.glow(stX[i], stY[i], stR[i], 0x030105, 0.45f, add = false)
        if (notes) for (i in 0 until veinN) pix.dot(vnX[i], vnY[i], 1, 1, vnC[i])
        var i = 0
        while (i < 260) {
            pix.dot(spX[i], spY[i], 1, 1, spC[i])
            i += speckEvery
        }
        val cv = pix.canvas
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ditherPaint)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignette)
    }
}

// ---------------------------------------------------------------------------------------------
// Rotting paper page (static; rendered once per page size, seeded by note id)
// ---------------------------------------------------------------------------------------------

internal const val PAPER_SHADOW_PX = 3

internal fun renderPaper(pix: Pix, id: Long) {
    val pw = pix.w
    val ph = pix.h
    val r = RotRng((id * 7919L + 13L).toInt())
    val x1 = pw - PAPER_SHADOW_PX
    val y1 = ph - PAPER_SHADOW_PX
    if (x1 < 8 || y1 < 8) return
    pix.clear()
    pix.dot(PAPER_SHADOW_PX, PAPER_SHADOW_PX, x1, y1, 0x07030B)

    val tops = IntArray(x1)
    val bots = IntArray(x1)
    for (x in 0 until x1) {
        val top = (r.next() * 3f).toInt()
        val bot = y1 - (r.next() * 3f).toInt()
        tops[x] = top; bots[x] = bot
        pix.dot(x, top, 1, bot - top, 0xBBA8C8)
        if (r.next() < 0.25f) pix.dot(x, top, 1, 1, 0x8A7898)
        if (r.next() < 0.25f) pix.dot(x, bot - 1, 1, 1, 0x8A7898)
    }
    for (y in 0 until y1) {
        if (r.next() < 0.3f) pix.erase(0, y, 1, 1)
        if (r.next() < 0.3f) pix.erase(x1 - 1, y, 1, 1)
    }

    // Mould creeping in from one corner.
    val corner = (r.next() * 4f).toInt().coerceAtMost(3)
    val ccx = if (corner % 2 == 0) 0 else x1 - 1
    val ccy = if (corner < 2) 0 else y1 - 1
    val rad = 22
    for (py in ccy - rad..ccy + rad) {
        for (px in ccx - rad..ccx + rad) {
            if (px < 1 || px >= x1 - 1 || py < 0 || py >= y1) continue
            if (py < tops[px] || py >= bots[px]) continue
            val d = hypot((px - ccx).toFloat(), (py - ccy).toFloat()) / rad
            if (d >= 1f) continue
            if (d < 0.6f) {
                val m = d / 0.6f
                val rr = (60 + 30 * m).toInt(); val gg = (20 + 20 * m).toInt(); val bb = (50 + 20 * m).toInt()
                pix.dot(px, py, 1, 1, (rr shl 16) or (gg shl 8) or bb, 0.75f - 0.5f * m)
            } else {
                val m = (d - 0.6f) / 0.4f
                pix.dot(px, py, 1, 1, (90 shl 16) or (40 shl 8) or 70, 0.25f * (1f - m))
            }
        }
    }
    for (i in 0 until 14) {
        val sx = ccx + ((r.next() - 0.5f) * 50f).toInt()
        val sy = ccy + ((r.next() - 0.5f) * 50f).toInt()
        val col = if (i % 2 == 0) 0x4A5A2A else 0x3A2A1A
        if (sx in 1 until x1 - 1 && sy in 0 until y1 && sy >= tops[sx] && sy < bots[sx]) pix.dot(sx, sy, 1, 1, col)
    }

    // Rot holes near the top and bottom edges.
    for (i in 0 until 3) {
        val hx = 4f + r.next() * (x1 - 8)
        val hy = if (r.next() < 0.5f) 2f + r.next() * 5f else y1 - 2f - r.next() * 5f
        val hr = 1f + r.next() * 2.2f
        pix.disc(hx, hy, hr + 1f, 0x5A3A3A)
        pix.disc(hx, hy, hr, 0, erase = true)
    }
}

// ---------------------------------------------------------------------------------------------
// Pin
// ---------------------------------------------------------------------------------------------

/** Pin sprite bitmap size and the head centre inside it (low-res px). */
internal const val PIN_W = 20
internal const val PIN_H = 24
internal const val PIN_CX = 10
internal const val PIN_CY = 10

internal fun drawPin(pix: Pix, t: Float, i: Int) {
    pix.clear()
    pix.dot(PIN_CX - 1, PIN_CY + 2, 2, 3, 0x2A1020)
    pix.glow(PIN_CX.toFloat(), PIN_CY.toFloat(), 8f, 0xD45AAB, 0.6f + 0.3f * sin(t * 2f + i))
    pix.disc(PIN_CX.toFloat(), PIN_CY.toFloat(), 2.2f, 0x8A2A6A)
    pix.disc(PIN_CX.toFloat(), PIN_CY.toFloat(), 1.6f, 0xD45AAB)
    pix.dot(PIN_CX - 1, PIN_CY - 1, 1, 1, 0xFFD0F0)
}

// ---------------------------------------------------------------------------------------------
// Candles
// ---------------------------------------------------------------------------------------------

/** Fade of a completing row: 1 until 0.7 s, then 0 at 1.2 s. */
internal fun rotRowFade(p: Float): Float = if (p < 0f) 1f else 1f - ((p - 0.7f) / 0.5f).coerceIn(0f, 1f)

internal class CandleRenderer {
    val pix = Pix()

    fun begin(w: Int, h: Int) {
        pix.resize(w, h)
        pix.clear()
        pix.ma = 1f
    }

    /**
     * One task: [ly] is the ledge's top row in low-res px, [p] seconds since completion was tapped (negative = alive).
     * Everything derives from [id] so the candle never changes.
     */
    fun row(id: Long, ly: Int, w: Int, t: Float, p: Float) {
        val s = id.toInt() * 31 + 7
        val fi = (id % 1000L).toFloat()
        val ch = 12 + (hf(s, 1) * 7f).toInt()
        val cx = 17
        val top = ly - ch
        val flame = if (p < 0f) 1f else (1f - p / 0.4f).coerceIn(0f, 1f)
        pix.ma = rotRowFade(p)
        if (pix.ma <= 0f) return

        if (flame > 0f) {
            pix.glow(
                cx.toFloat(), (ly - ch - 4).toFloat(), 48f, 0x7A3AA8,
                (0.32f + 0.08f * sin(t * 9f + fi * 2f) + 0.05f * sin(t * 23f + fi)) * flame,
            )
            pix.glow(cx.toFloat(), (ly - ch - 4).toFloat(), 18f, 0xD45AAB, 0.2f * flame)
        }

        // Ledge with a few mould specks.
        pix.dot(7, ly, w - 14, 1, 0x3A2030)
        pix.dot(7, ly + 1, w - 14, 3, 0x1E0E18)
        for (k in 0 until 8) {
            val sx = 7 + (hf(s, 20 + k) * (w - 14)).toInt()
            val sy = ly + 1 + (hf(s, 40 + k) * 3f).toInt()
            pix.dot(sx, sy, 1, 1, if (k % 2 == 0) 0x4A5A2A else 0x3A2A1A)
        }

        // Candle body: 6 wide, x from cx-3 to cx+2.
        val bx = cx - 3
        pix.dot(bx, top, 6, ch - 2, 0xC9B0FF)
        pix.dot(bx + 4, top, 2, ch - 2, 0x8F6BD0)
        pix.dot(bx, top, 1, ch - 2, 0xE6DCFF)
        pix.dot(bx, top, 6, 1, 0xF0E8FF)
        for (k in 0 until 3) {
            val dx = bx + 1 + (hf(s, 60 + k) * 4f).toInt()
            val len = 2 + (hf(s, 70 + k) * 4f).toInt()
            pix.dot(dx, top + 1, 1, len, 0xE6DCFF)
            pix.dot(dx, top + 1 + len, 1, 1, 0xB69AFF)
        }
        pix.dot(cx - 6, ly - 1, 13, 1, 0x8F6BD0)
        pix.dot(cx - 4, ly - 2, 9, 1, 0xB69AFF)

        // Wick and flame.
        val xc = cx - 1
        pix.dot(xc, top - 2, 1, 2, 0x1A0A14)
        if (flame > 0f) {
            val sway = rotRnd(sin(t * 7f + fi) * 0.7f + sin(t * 13f + fi * 2f) * 0.4f)
            val fx = xc + sway
            pix.glow(fx.toFloat() + 0.5f, (top - 5).toFloat(), 10f, 0xD45AAB, 0.7f * flame)
            pix.dot(fx - 1, top - 3, 3, 1, 0x7A3AA8, flame)
            pix.dot(fx - 1, top - 6, 3, 3, 0xD45AAB, flame)
            pix.dot(fx, top - 8, 1, 2, 0xD45AAB, flame)
            pix.dot(fx, top - 5, 1, 2, 0xFFE8FF, flame)
        }

        // Smoke after the flame is snuffed.
        if (p >= 0.4f) {
            val k = p - 0.4f
            for (q in 0 until 10) {
                val yy = top - 3 - q * 2.2f - k * 10f
                val xx = cx + sin(q * 0.9f + k * 3f) * 2f * (q / 4f)
                val a = (1f - k / 1.4f) * (1f - q / 12f)
                pix.dot(rotRnd(xx), rotRnd(yy), 1, 1, if (q % 2 == 0) 0x8A7A9A else 0x6A5A7A, a)
            }
        }
        pix.ma = 1f
    }
}
