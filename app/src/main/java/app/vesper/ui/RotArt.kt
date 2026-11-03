package app.vesper.ui

import androidx.collection.MutableIntObjectMap
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
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

private fun pixPaint(fq: FilterQuality = FilterQuality.None) = hardPaint().apply { filterQuality = fq }

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

/** Scroll offset of the Notes list in device px, written by NotesScreen and read by the Notes wall for parallax. */
internal object NotesWallScroll {
    val offsetPx = mutableFloatStateOf(0f)
}

internal interface WallPainter {
    val pix: Pix
    fun draw(w: Int, h: Int, t: Float, scroll: Float)
}

@Composable
private fun WallLayer(notes: Boolean) {
    val pxSize = rememberRotPxSize()
    val time = rememberRotTime()
    val renderer = remember(notes) { if (notes) NotesWallRenderer() else CandleWallRenderer() }
    Canvas(Modifier.fillMaxSize()) {
        val t = time.floatValue
        val w = ceil(size.width / pxSize).toInt()
        val h = ceil(size.height / pxSize).toInt()
        if (w <= 0 || h <= 0) return@Canvas
        val scroll = if (notes) NotesWallScroll.offsetPx.floatValue / pxSize else 0f
        renderer.draw(w, h, t, scroll)
        drawIntoCanvas { renderer.pix.blit(it, 0, 0, pxSize.toInt()) }
    }
}

private fun wrapF(v: Float, m: Float): Float = v - floor(v / m) * m

private fun makeDitherPaint(alpha: Float) = pixPaint().apply {
    shader = ImageShader(DITHER, TileMode.Repeated, TileMode.Repeated)
    this.alpha = (alpha * 255f).toInt() / 255f // same truncated 8-bit alpha the android Paint got
}

/** Living notes wall: drifting fog, breathing stains, pulsing veins, motes, parallax with the list scroll. */
internal class NotesWallRenderer : WallPainter {
    override val pix = Pix()

    private val stX = IntArray(8)
    private val stY = IntArray(8)
    private val stR = FloatArray(8)
    private val stPh = FloatArray(8)

    private val veinSteps = 14
    private val veinCount = 16
    private val vnX = IntArray(veinCount * veinSteps * 5)
    private val vnY = IntArray(veinCount * veinSteps * 5)
    private val vCol = IntArray(veinCount)
    private val vSp = FloatArray(veinCount)
    private val vPh = FloatArray(veinCount)

    private val spN = 300
    private val spX = IntArray(spN)
    private val spY = IntArray(spN)
    private val spC = IntArray(spN)

    private val moteN = 40
    private val mX = FloatArray(moteN)
    private val mY = FloatArray(moteN)
    private val mSp = FloatArray(moteN)
    private val mDr = FloatArray(moteN)
    private val mPh = FloatArray(moteN)

    private val ditherPaint = makeDitherPaint(0.22f)
    private val vignette = pixPaint()
    private val vigColors = listOf(Color(0x00030006), Color(0x00030006), Color(((0.82f * 255f).toInt() shl 24) or 0x030006))

    private fun build(w: Int, h: Int) {
        val r = RotRng(4242)
        for (i in 0 until 8) {
            stX[i] = (r.next() * w).toInt(); stY[i] = (r.next() * h).toInt()
            stR[i] = 20f + r.next() * 30f; stPh[i] = r.next() * 6.2832f
        }
        var n = 0
        for (v in 0 until veinCount) {
            var x = r.next() * w
            var y = r.next() * h
            var a = r.next() * 2f * PI.toFloat()
            vCol[v] = if (v % 2 == 0) 0x160A2A else 0x1F0E38
            vSp[v] = 0.15f + r.next() * 0.2f
            vPh[v] = r.next() * 3f
            for (s in 0 until veinSteps) {
                a += (r.next() - 0.5f) * 1.0f
                for (k in 0 until 5) {
                    x += cos(a); y += sin(a)
                    vnX[n] = rotRnd(x); vnY[n] = rotRnd(y)
                    n++
                }
            }
        }
        val spk = intArrayOf(0x1A0820, 0x3A1440, 0x5A2050)
        for (i in 0 until spN) {
            spX[i] = (r.next() * w).toInt(); spY[i] = (r.next() * h).toInt()
            spC[i] = spk[(r.next() * 3f).toInt().coerceAtMost(2)]
        }
        for (i in 0 until moteN) {
            mX[i] = r.next() * w; mY[i] = r.next() * h
            mSp[i] = 1.5f + r.next() * 3f
            mDr[i] = (r.next() - 0.5f) * 1.2f
            mPh[i] = r.next() * 6.2832f
        }
    }

    override fun draw(w: Int, h: Int, t: Float, scroll: Float) {
        if (pix.resize(w, h) || stR[0] == 0f) build(w, h)
        pix.fill(0x06020A)
        val sxk = w / 180f
        val syk = h / 390f
        for (i in 0 until 6) {
            pix.glow(
                w / 2f + sin(t * 0.06f + i * 1.3f) * 80f * sxk,
                h / 2f + cos(t * 0.05f + i * 1.7f) * 150f * syk,
                85f * sxk.coerceAtLeast(0.8f), if (i % 2 == 0) 0x3E1048 else 0x24103E,
                0.32f + 0.08f * sin(t * 0.4f + i),
            )
        }
        val hf0 = h.toFloat()
        for (i in 0 until 8) {
            val y = wrapF(stY[i] - scroll * 0.2f, hf0)
            pix.glow(stX[i].toFloat(), y, stR[i] * (1f + 0.06f * sin(t * 0.3f + stPh[i])), 0x020004, 0.6f, add = false)
        }
        val vOff = rotRnd(scroll * 0.3f)
        for (v in 0 until veinCount) {
            val head = ((t * vSp[v] + vPh[v]) % 3f) / 3f * veinSteps * 1.6f
            for (j in 0 until veinSteps) {
                val d = (j - head) / 1.6f
                val hot = exp(-d * d)
                val col = if (hot > 0.15f) {
                    ((50 + (80 * hot).toInt()) shl 16) or ((18 + (30 * hot).toInt()) shl 8) or (90 + (150 * hot).toInt())
                } else vCol[v]
                val base = (v * veinSteps + j) * 5
                for (k in 0 until 5) {
                    val yy = Math.floorMod(vnY[base + k] - vOff, h)
                    pix.dot(vnX[base + k], yy, 1, 1, col)
                }
                if (hot > 0.6f) {
                    val yy = Math.floorMod(vnY[base + 2] - vOff, h)
                    pix.glow(vnX[base + 2].toFloat(), yy.toFloat(), 4f, 0x7A3AC8, 0.25f * hot)
                }
            }
        }
        val sOff = rotRnd(scroll * 0.15f)
        for (i in 0 until spN) pix.dot(spX[i], Math.floorMod(spY[i] - sOff, h), 1, 1, spC[i])
        val wf = w.toFloat()
        for (i in 0 until moteN) {
            val x = wrapF(mX[i] + sin(t * 0.3f + mPh[i]) * 8f + t * mDr[i], wf)
            val y = wrapF(mY[i] - t * mSp[i] - scroll * 0.4f, hf0)
            val a = 0.2f + 0.4f * max(0f, sin(t * 1.4f + mPh[i]))
            pix.dot(x.toInt(), y.toInt(), 1, 1, if (i % 4 == 0) 0x9A6BE0 else 0x8A5AC8, a)
        }
        val cv = pix.canvas
        cv.drawRect(0f, 0f, wf, hf0, ditherPaint)
        val inner = (0.2f + 0.02f * sin(t * 0.5f)) / 0.66f
        vignette.shader = RadialGradientShader(Offset(wf / 2f, hf0 * 0.45f), hf0 * 0.66f, vigColors, listOf(0f, inner, 1f), TileMode.Clamp)
        cv.drawRect(0f, 0f, wf, hf0, vignette)
    }
}

internal class CandleWallRenderer : WallPainter {
    override val pix = Pix()

    private val stX = FloatArray(7)
    private val stY = FloatArray(7)
    private val stR = FloatArray(7)
    private val spX = IntArray(260)
    private val spY = IntArray(260)
    private val spC = IntArray(260)

    private val ditherPaint = makeDitherPaint(0.18f)
    private val vignette = pixPaint()

    private fun build(w: Int, h: Int) {
        val r = RotRng(777)
        for (i in 0 until 7) {
            stX[i] = r.next() * w; stY[i] = r.next() * h; stR[i] = 20f + r.next() * 30f
        }
        val spk = intArrayOf(0x1E0A24, 0x4A1650, 0x7A2A6A)
        for (i in 0 until 260) {
            spX[i] = (r.next() * w).toInt(); spY[i] = (r.next() * h).toInt(); spC[i] = spk[(r.next() * 3f).toInt().coerceAtMost(2)]
        }
        val c0 = Color(0x00040108)
        val c1 = Color(((0.8f * 255f).toInt() shl 24) or 0x040108)
        vignette.shader = RadialGradientShader(
            Offset(w / 2f, h * 0.45f), h * 0.66f, listOf(c0, c0, c1), listOf(0f, 0.2f / 0.66f, 1f), TileMode.Clamp,
        )
    }

    override fun draw(w: Int, h: Int, t: Float, scroll: Float) {
        if (pix.resize(w, h) || stR[0] == 0f) build(w, h)
        pix.fill(0x050208)
        val sxk = w / 180f
        val syk = h / 390f
        for (i in 0 until 5) {
            pix.glow(
                w / 2f + sin(t * 0.05f + i * 1.3f) * 70f * sxk,
                h / 2f + cos(t * 0.04f + i * 1.7f) * 140f * syk,
                75f * sxk.coerceAtLeast(0.8f), if (i % 2 == 0) 0x2A1040 else 0x1A0A28, 0.2f,
            )
        }
        for (i in 0 until 7) pix.glow(stX[i], stY[i], stR[i], 0x030105, 0.45f, add = false)
        var i = 0
        while (i < 260) {
            pix.dot(spX[i], spY[i], 1, 1, spC[i])
            i += 3
        }
        val cv = pix.canvas
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ditherPaint)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignette)
    }
}

// ---------------------------------------------------------------------------------------------
// Rotting paper page (static; rendered once per page size, seeded by note id)
// ---------------------------------------------------------------------------------------------

/** Empty margin (low-res px) kept right/below the paper: room for the aura or the dialog's hard shadow. */
internal const val PAPER_SHADOW_PX = 3

// Base strands per tone: violet, ash lavender, dusky rose, deep plum. Plum stays light enough for the #140A1C ink.
private val PAPER_TONES = intArrayOf(0x8E7CA2, 0x8A8296, 0x8E6A7E, 0x866EAE)

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
    pix.dot(PIN_CX - 1, PIN_CY + 2, 2, 3, 0x1A0814)
    pix.glow(PIN_CX.toFloat(), PIN_CY.toFloat(), 8f, 0x8A5AC8, 0.45f + 0.25f * sin(t * 2f + i))
    pix.disc(PIN_CX.toFloat(), PIN_CY.toFloat(), 2.2f, 0x3A1A6A)
    pix.disc(PIN_CX.toFloat(), PIN_CY.toFloat(), 1.6f, 0x9A6BE0)
    pix.dot(PIN_CX - 1, PIN_CY - 1, 1, 1, 0xE6DCFF)
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
    /** Little holder under a puddle (13 wide): plate 2px wider each side with a lit rim, and a narrower foot. */
    private fun dish(cx: Int, ly: Int, plate: Int, rim: Int, foot: Int) {
        pix.dot(cx - 8, ly, 17, 1, rim)
        pix.dot(cx - 8, ly + 1, 17, 1, plate)
        pix.dot(cx - 4, ly + 2, 9, 2, foot)
    }

    fun row(id: Long, mid: Int, w: Int, t: Float, p: Float) {
        val s = id.toInt() * 31 + 7
        val fi = (id % 1000L).toFloat()
        val ch = 12 + (hf(s, 1) * 7f).toInt()
        val ly = mid + ch / 2 - 2 // body (ch) + dish (4) spans ly-ch..ly+4, so its middle sits on [mid]
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
        dish(cx, ly, 0x5A3E7A, 0xB69AFF, 0x3A2456)

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

    /**
     * A snuffed task: melted stub, wax puddle and one thin wisp of smoke on a dimmer ledge.
     * [p] is seconds since relighting was tapped (negative = still ash); the flame grows back over 0.6 s.
     */
    fun stub(id: Long, mid: Int, w: Int, t: Float, p: Float) {
        val fi = (id % 1000L).toFloat()
        val cx = 17
        val ly = mid // stub (5) + dish (4) spans ly-5..ly+4, middle ~ly
        val top = ly - 5
        pix.ma = 1f

        pix.dot(cx - 6, ly - 1, 13, 1, 0x5A4870)
        pix.dot(cx - 4, ly - 2, 9, 1, 0x6A5480)
        pix.dot(cx - 2, top, 5, 3, 0x7A6496)
        pix.dot(cx - 2, top, 5, 1, 0x301426)
        dish(cx, ly, 0x3A2A48, 0x5A4870, 0x241830)
        // Bent wick: up, then flopped over.
        pix.dot(cx - 1, top - 1, 1, 2, 0x0A0508)
        pix.dot(cx, top - 2, 1, 1, 0x0A0508)

        // One wisp, each puff a pixel; the cycle is staggered per row.
        val u = t * 0.25f + fi * 0.37f
        for (q in 0 until 6) {
            val f = (u + q / 6f) % 1f
            val xx = cx - 1 + sin(f * 6f + fi) * 1.6f
            pix.dot(rotRnd(xx), top - 2 - (f * 16f).toInt(), 1, 1, if (q % 2 == 0) 0x8A7A9A else 0x6A5A7A, (1f - f) * 0.6f)
        }

        if (p >= 0f) {
            val g = (p / 0.6f).coerceIn(0f, 1f)
            val fx = cx - 1 + rotRnd(sin(t * 7f + fi) * 0.7f)
            val by = top - 1
            pix.glow(cx.toFloat(), (by - 4).toFloat(), 30f * g, 0x7A3AA8, 0.3f * g)
            pix.dot(fx - 1, by - 2, 3, 1, 0x7A3AA8, g)
            pix.dot(fx - 1, by - 5, 3, 3, 0xD45AAB, g)
            pix.dot(fx, by - 7, 1, 2, 0xD45AAB, g)
            pix.dot(fx, by - 4, 1, 2, 0xFFE8FF, g)
        }
    }
}
