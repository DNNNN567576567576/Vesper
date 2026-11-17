package app.vesper.ui

import androidx.collection.MutableIntObjectMap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.withFrameNanos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round

// Shared low-res pixel art helpers (paper, glow, dither) for the Brain, Learn, Moons, Shrine and button art.

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

private val GLOW_SPRITE: ImageBitmap by lazy {
    imageBitmapOf(
        IntArray(64 * 64) { i ->
            val dx = (i % 64 - 31.5f) / 32f
            val dy = (i / 64 - 31.5f) / 32f
            val a = (1f - hypot(dx, dy)).coerceIn(0f, 1f)
            ((a * 255f).toInt() shl 24) or 0xFFFFFF
        },
        64, 64,
    )
}

private val DITHER: ImageBitmap by lazy { imageBitmapOf(intArrayOf(0xFF000000.toInt(), 0, 0, 0xFF000000.toInt()), 2, 2) }

internal fun pixPaint(fq: FilterQuality = FilterQuality.None) = hardPaint().apply { filterQuality = fq }

/** A low-res ARGB bitmap with integer-pixel drawing helpers. Reused every frame; nothing here allocates per call. */
internal class Pix(w: Int = 1, h: Int = 1) {
    var bmp: ImageBitmap = ImageBitmap(max(1, w), max(1, h))
        private set
    private var cv = Canvas(bmp)
    val canvas: Canvas get() = cv
    val w: Int get() = bmp.width
    val h: Int get() = bmp.height

    /** Master alpha multiplier for everything drawn afterwards. */
    var ma = 1f

    private val px = pixPaint()
    private val clr = pixPaint().apply { blendMode = BlendMode.Clear }
    private val src = pixPaint().apply { blendMode = BlendMode.Src }
    private val blitPaint = pixPaint()
    private val glowAdd = pixPaint(FilterQuality.Low).apply { blendMode = BlendMode.Plus }
    private val glowOver = pixPaint(FilterQuality.Low)
    private val filters = MutableIntObjectMap<ColorFilter>()

    /** Returns true if the bitmap was recreated (contents lost). */
    fun resize(nw: Int, nh: Int): Boolean {
        val ww = max(1, nw)
        val hh = max(1, nh)
        if (ww == bmp.width && hh == bmp.height) return false
        bmp = ImageBitmap(ww, hh)
        cv = Canvas(bmp)
        return true
    }

    fun clear() = cv.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), clr)
    fun fill(rgb: Int) {
        src.color = Color(0xFF000000.toInt() or rgb)
        cv.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), src)
    }

    fun dot(x: Int, y: Int, ww: Int, hh: Int, rgb: Int, a: Float = 1f) {
        if (x + ww <= 0 || y + hh <= 0 || x >= bmp.width || y >= bmp.height) return
        val al = (a * ma * 255f).toInt().coerceIn(0, 255)
        if (al == 0) return
        px.color = Color((al shl 24) or (rgb and 0xFFFFFF))
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

    private fun filterFor(rgb: Int): ColorFilter =
        filters.getOrPut(rgb) { ColorFilter.tint(Color(0xFF000000.toInt() or rgb), BlendMode.SrcIn) }

    /** Radial glow; additive by default, normal blend when [add] is false. */
    fun glow(cx: Float, cy: Float, r: Float, rgb: Int, a: Float, add: Boolean = true) {
        val al = (a * ma * 255f).toInt().coerceIn(0, 255)
        if (al == 0 || r < 0.5f) return
        if (cx + r < 0 || cy + r < 0 || cx - r > bmp.width || cy - r > bmp.height) return
        val p = if (add) glowAdd else glowOver
        p.colorFilter = filterFor(rgb)
        p.alpha = al / 255f
        // Compose has no float-rect drawImageRect: map the 64px sprite onto (l,t)-(cx+r,cy+r) with the same matrix.
        val l = cx - r
        val t = cy - r
        cv.save()
        cv.translate(l, t)
        cv.scale((cx + r - l) / 64f, (cy + r - t) / 64f)
        cv.drawImage(GLOW_SPRITE, Offset.Zero, p)
        cv.restore()
    }

    /** Scales up to [pxSize] device px per pixel with no filtering, top-left at ([left],[top]). */
    fun blit(target: Canvas, left: Int, top: Int, pxSize: Int) = blitTo(target, left, top, bmp.width * pxSize, bmp.height * pxSize)

    /** Nearest-neighbour stretch to [dw] x [dh] device px, top-left at ([left],[top]); the size need not be a whole multiple. */
    fun blitTo(target: Canvas, left: Int, top: Int, dw: Int, dh: Int) {
        target.drawImageRect(bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height), IntOffset(left, top), IntSize(dw, dh), blitPaint)
    }
}

internal fun makeDitherPaint(alpha: Float) = pixPaint().apply {
    shader = ImageShader(DITHER, TileMode.Repeated, TileMode.Repeated)
    this.alpha = (alpha * 255f).toInt() / 255f // same truncated 8-bit alpha the android Paint got
}

// ---------------------------------------------------------------------------------------------
// Rotting paper page (static; rendered once per page size, seeded by note id)
// ---------------------------------------------------------------------------------------------

/** Empty margin (low-res px) kept right/below the paper: room for the aura or the dialog's hard shadow. */
internal const val PAPER_SHADOW_PX = 3

// Base strands per tone: violet, ash lavender, dusky violet, deep violet. They stay light enough for the #140A1C ink.
private val PAPER_TONES = intArrayOf(0x8E7CA2, 0x8A8296, 0x7B6A8E, 0x866EAE)

internal fun paperTone(id: Long): Int = ((id * 0x9E3779B1L) ushr 16).toInt() and 3

/** [shadowRgb] >= 0 bakes a hard offset shadow (dialogs); list cards use a live aura instead. */
internal fun renderPaper(pix: Pix, id: Long, tone: Int = paperTone(id), shadowRgb: Int = -1) {
    val pw = pix.w
    val ph = pix.h
    val r = RotRng((id * 7919L + 13L).toInt())
    val x1 = pw - PAPER_SHADOW_PX
    val y1 = ph - PAPER_SHADOW_PX
    if (x1 < 8 || y1 < 8) return
    pix.clear()
    if (shadowRgb >= 0) pix.dot(PAPER_SHADOW_PX, PAPER_SHADOW_PX, x1, y1, shadowRgb)
    val base = PAPER_TONES[tone and 3]

    val tops = IntArray(x1)
    val bots = IntArray(x1)
    for (x in 0 until x1) {
        val top = (r.next() * 3f).toInt()
        val bot = y1 - (r.next() * 3f).toInt()
        tops[x] = top; bots[x] = bot
        pix.dot(x, top, 1, bot - top, base)
        if (r.next() < 0.25f) pix.dot(x, top, 1, 1, 0x5E4E72)
        if (r.next() < 0.25f) pix.dot(x, bot - 1, 1, 1, 0x5E4E72)
    }
    for (y in 0 until y1) {
        if (r.next() < 0.3f) pix.dot(0, y, 1, 1, 0x06020A)
        if (r.next() < 0.3f) pix.dot(x1 - 1, y, 1, 1, 0x06020A)
    }

    // Violet tint, top-left to bottom-right.
    for (x in 1 until x1 - 1) {
        for (y in tops[x] until bots[x]) {
            val m = (x.toFloat() / x1 + y.toFloat() / y1) / 2f
            val rr = (60 + (20 - 60) * m).toInt()
            val gg = (20 + (6 - 20) * m).toInt()
            val bb = (90 + (30 - 90) * m).toInt()
            pix.dot(x, y, 1, 1, (rr shl 16) or (gg shl 8) or bb, (0.28f + (0.18f - 0.28f) * m) * (if (tone == 3) 0.6f else 1f))
        }
    }

    // Mould creeping in from one corner.
    val corner = (r.next() * 4f).toInt().coerceAtMost(3)
    val ccx = if (corner % 2 == 0) 0 else x1 - 1
    val ccy = if (corner < 2) 0 else y1 - 1
    val rad = 24
    for (py in ccy - rad..ccy + rad) {
        for (px in ccx - rad..ccx + rad) {
            if (px < 1 || px >= x1 - 1 || py < 0 || py >= y1) continue
            if (py < tops[px] || py >= bots[px]) continue
            val d = hypot((px - ccx).toFloat(), (py - ccy).toFloat()) / rad
            if (d >= 1f) continue
            if (d < 0.6f) {
                val m = d / 0.6f
                val rr = (30 + 30 * m).toInt(); val gg = (8 + 12 * m).toInt(); val bb = (30 + 30 * m).toInt()
                pix.dot(px, py, 1, 1, (rr shl 16) or (gg shl 8) or bb, 0.85f - 0.55f * m)
            } else {
                val m = (d - 0.6f) / 0.4f
                pix.dot(px, py, 1, 1, (60 shl 16) or (20 shl 8) or 60, 0.3f * (1f - m))
            }
        }
    }
    for (i in 0 until 14) {
        val sx = ccx + ((r.next() - 0.5f) * 50f).toInt()
        val sy = ccy + ((r.next() - 0.5f) * 50f).toInt()
        val col = if (i % 2 == 0) 0x2A3A1A else 0x2A1A14
        if (sx in 1 until x1 - 1 && sy in 0 until y1 && sy >= tops[sx] && sy < bots[sx]) pix.dot(sx, sy, 1, 1, col)
    }

    // Rot holes near the top and bottom edges.
    for (i in 0 until 3) {
        val hx = 4f + r.next() * (x1 - 8)
        val hy = if (r.next() < 0.5f) 2f + r.next() * 5f else y1 - 2f - r.next() * 5f
        val hr = 1f + r.next() * 2.2f
        pix.disc(hx, hy, hr + 1f, 0x3A2440)
        pix.disc(hx, hy, hr, 0, erase = true)
    }
}
