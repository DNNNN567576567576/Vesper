package app.vesper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.Note
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Palette + layout
// ---------------------------------------------------------------------------------------------

private val LobeColors = listOf(
    Color(0xFFB69AFF),
    Color(0xFFD45AAB),
    Color(0xFF8F6BFF),
    Color(0xFFE08AD0),
    Color(0xFF9A7BD8),
    Color(0xFFC27AE0),
)

internal fun lobeColor(l: Lobe) = LobeColors[l.colorIndex.mod(LobeColors.size)]

/** How rotten the map is (0..1). Locked by the approved mockup. */
private const val ROT = 0.64f
private const val TAU = 6.2831855f
private const val GOLDEN = 137.508

/** Mulberry32, same generator as the mockup. */
private class Rng(seed: Int) {
    private var s = seed
    fun next(): Float {
        s += 0x6D2B79F5
        var t = (s xor (s ushr 15)) * (1 or s)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble().div(4294967296.0).toFloat()
    }
}

/** World units are low-res pixels at zoom 1. The core sits at (0, 0). */
private fun lobeWorldPos(slot: Int): Offset {
    val a = Math.toRadians(-83.0 + slot * GOLDEN)
    val k = if (slot >= 6) 1.0 + 0.35 * (slot / 6) else 1.0
    return Offset((cos(a) * 60.0 * k).toFloat(), (sin(a) * 90.0 * k).toFloat())
}

private class Placed(
    val idea: Idea, val lobe: Lobe, val pos: Offset,
    val ph: Float, val bend: Float, val j: Int, val newest: Boolean,
)

private fun placeIdeas(lobes: List<Lobe>, ideas: List<Idea>): List<Placed> {
    val byLobe = ideas.groupBy { it.lobeId }
    return lobes.flatMap { lobe ->
        val c = lobeWorldPos(lobe.slot)
        val ba = atan2(c.y, c.x)
        val list = byLobe[lobe.id].orEmpty().sortedBy { it.id }
        val m = list.size
        val newestId = list.maxByOrNull { it.updatedAt }?.id
        list.mapIndexed { k, idea ->
            val f = k / 9
            val jj = k % 9
            val mf = min(9, m - f * 9)
            val r = Rng((idea.id * 7919L + 13L).toInt())
            val aa = ba + (jj - (mf - 1) / 2f) * 0.44f + (r.next() - 0.5f) * 0.15f + (if (f % 2 == 1) 0.22f else 0f)
            val dd = 19f + r.next() * 13f + (k % 2) * 7f + 16f * f
            val ph = r.next() * TAU
            val bend = (r.next() - 0.5f) * 10f
            Placed(idea, lobe, Offset(c.x + cos(aa) * dd, c.y + sin(aa) * dd), ph, bend, k, idea.id == newestId)
        }
    }
}

private fun ideaLabel(i: Idea) = i.title.ifBlank { i.body.lineSequence().firstOrNull().orEmpty() }

private fun noteName(n: Note) = n.title.ifBlank { n.content.lineSequence().firstOrNull().orEmpty().ifBlank { "Untitled" } }

/** Pan/zoom state, kept above the tab switcher so the map is where you left it. [x]/[y] is the world point at screen centre. */
@Stable
class BrainCamera {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var scale by mutableFloatStateOf(0.85f)
}

/** Everything the renderer needs, derived from the real lobes/ideas plus the seeded rot decoration. */
private class Layout(
    val nL: Int, val lobeIds: LongArray, val lx: FloatArray, val ly: FloatArray, val lbend: FloatArray,
    val lcol: IntArray, val lslot: IntArray, val istart: IntArray,
    val nI: Int, val ix: FloatArray, val iy: FloatArray, val iph: FloatArray, val ibend: FloatArray,
    val ij: IntArray, val inew: BooleanArray,
    val dust: FloatArray,      // x, y, ph, b
    val blots: FloatArray,     // x, y, r, ph
    val stains: FloatArray,    // x, y, r
    val cracks: Array<FloatArray>, // x0, y0, x1, y1, ...
    val specks: FloatArray,    // x, y, colour index
    val spores: FloatArray,    // x, y, ph, sp
    val drips: FloatArray,     // x, y, n, len, owner lobe (-1 none)
)

private fun buildLayout(lobes: List<Lobe>, placedIn: List<Placed>): Layout {
    val nL = lobes.size
    val idxOf = HashMap<Long, Int>()
    lobes.forEachIndexed { i, l -> idxOf[l.id] = i }
    val placed = placedIn.filter { idxOf.containsKey(it.lobe.id) }.sortedBy { idxOf[it.lobe.id]!! }

    val lx = FloatArray(nL); val ly = FloatArray(nL); val lbend = FloatArray(nL)
    val lcol = IntArray(nL); val lslot = IntArray(nL); val ids = LongArray(nL)
    val istart = IntArray(nL + 1)
    for (i in 0 until nL) {
        val l = lobes[i]
        val p = lobeWorldPos(l.slot)
        lx[i] = p.x; ly[i] = p.y
        lbend[i] = (Rng(l.id.toInt() * 31 + 7).next() - 0.5f) * 30f
        lcol[i] = lobeColor(l).toArgb() and 0xFFFFFF
        lslot[i] = l.slot
        ids[i] = l.id
    }
    placed.forEach { istart[idxOf[it.lobe.id]!! + 1]++ }
    for (i in 0 until nL) istart[i + 1] += istart[i]
    val nI = placed.size
    val ix = FloatArray(nI) { placed[it].pos.x }
    val iy = FloatArray(nI) { placed[it].pos.y }
    val iph = FloatArray(nI) { placed[it].ph }
    val ibend = FloatArray(nI) { placed[it].bend }
    val ij = IntArray(nI) { placed[it].j }
    val inew = BooleanArray(nI) { placed[it].newest }

    // dust
    val rd = Rng(101)
    val dust = FloatArray(220 * 4)
    for (i in 0 until 220) {
        dust[i * 4] = (rd.next() - 0.5f) * 420f
        dust[i * 4 + 1] = (rd.next() - 0.5f) * 620f
        dust[i * 4 + 2] = rd.next() * TAU
        dust[i * 4 + 3] = rd.next()
    }

    // blots (rot spots): each kept with probability min(1, ROT * 1.2), decided by its own roll so it is stable
    val keep = min(1f, ROT * 1.2f)
    val bAll = ArrayList<FloatArray>()
    fun blot(x: Float, y: Float, r: Float, ph: Float, u: Float) {
        if (u < keep) bAll.add(floatArrayOf(x, y, r, ph))
    }
    val rc = Rng(202)
    repeat(12) {
        val a = rc.next() * TAU; val d = 22f + rc.next() * 24f
        val r = 2f + rc.next() * 5f; val ph = rc.next() * TAU; val u = rc.next()
        blot(cos(a) * d, sin(a) * d * 1.2f, r, ph, u)
    }
    for (i in 0 until nL) {
        val rl = Rng(lobes[i].id.toInt() * 17 + 303)
        repeat(5) {
            val a = rl.next() * TAU; val d = 10f + rl.next() * 26f
            val r = 1.5f + rl.next() * 4f; val ph = rl.next() * TAU; val u = rl.next()
            blot(lx[i] + cos(a) * d, ly[i] + sin(a) * d, r, ph, u)
        }
        repeat(3) {
            val s = 0.2f + rl.next() * 0.6f
            val jx = (rl.next() - 0.5f) * 14f; val jy = (rl.next() - 0.5f) * 14f
            val r = 1.5f + rl.next() * 3.5f; val ph = rl.next() * TAU; val u = rl.next()
            blot(lx[i] * s + jx, ly[i] * s + jy, r, ph, u)
        }
    }
    val rf = Rng(505)
    repeat(14) {
        val a = rf.next() * TAU; val d = 40f + rf.next() * 120f
        val r = 2f + rf.next() * 6f; val ph = rf.next() * TAU; val u = rf.next()
        blot(cos(a) * d * 0.8f, sin(a) * d * 1.3f, r, ph, u)
    }
    val blots = FloatArray(bAll.size * 4)
    bAll.forEachIndexed { i, b -> System.arraycopy(b, 0, blots, i * 4, 4) }

    // stains
    val rs = Rng(606)
    val stains = FloatArray(10 * 3)
    for (i in 0 until 10) {
        stains[i * 3] = (rs.next() - 0.5f) * 200f
        stains[i * 3 + 1] = (rs.next() - 0.5f) * 320f
        stains[i * 3 + 2] = 20f + rs.next() * 35f
    }

    // cracks: only the first round(22 * ROT) are ever drawn
    val rk = Rng(707)
    val nCracks = round(22 * ROT).toInt()
    val cracks = Array(nCracks) {
        var x = (rk.next() - 0.5f) * 220f
        var y = (rk.next() - 0.5f) * 340f
        var a = rk.next() * TAU
        val steps = 4 + (rk.next() * 5f).toInt()
        val pts = FloatArray(2 + steps * 2)
        pts[0] = x; pts[1] = y
        for (s in 0 until steps) {
            a += (rk.next() - 0.5f) * 1.4f
            val st = 3f + rk.next() * 5f
            x += cos(a) * st; y += sin(a) * st
            pts[2 + s * 2] = x; pts[3 + s * 2] = y
        }
        pts
    }

    // mold specks: first round(520 * ROT); each one has its own generator so it stays put
    val nSpecks = round(520 * ROT).toInt()
    val specks = FloatArray(nSpecks * 3)
    for (i in 0 until nSpecks) {
        val r = Rng(808 + i * 131)
        val kind = r.next()
        val x: Float
        val y: Float
        if (kind < 0.45f && nL > 0) {
            val l = min(nL - 1, (r.next() * nL).toInt()); val s = r.next()
            x = lx[l] * s + (r.next() - 0.5f) * 12f; y = ly[l] * s + (r.next() - 0.5f) * 12f
        } else if (kind < 0.75f && nL > 0) {
            val l = min(nL - 1, (r.next() * nL).toInt()); val a = r.next() * TAU; val d = r.next() * 40f
            x = lx[l] + cos(a) * d; y = ly[l] + sin(a) * d
        } else {
            x = (r.next() - 0.5f) * 220f; y = (r.next() - 0.5f) * 340f
        }
        val c = r.next()
        specks[i * 3] = x; specks[i * 3 + 1] = y
        specks[i * 3 + 2] = if (c < 0.7f) 0f else if (r.next() < 0.6f) 1f else 2f
    }

    // spores: first round(50 * ROT)
    val rp = Rng(909)
    val nSpores = round(50 * ROT).toInt()
    val spores = FloatArray(nSpores * 4)
    for (i in 0 until nSpores) {
        spores[i * 4] = (rp.next() - 0.5f) * 240f
        spores[i * 4 + 1] = (rp.next() - 0.5f) * 360f
        spores[i * 4 + 2] = rp.next() * TAU
        spores[i * 4 + 3] = 2f + rp.next() * 4f
    }

    // drips
    val dl = ArrayList<Float>()
    fun drip(x: Float, y: Float, n: Int, len: Float, owner: Int) {
        dl.add(x); dl.add(y); dl.add(n.toFloat()); dl.add(len); dl.add(owner.toFloat())
    }
    drip(0f, 18f, 14, 55f, -1)
    for (i in 0 until nL) drip(lx[i], ly[i] + 8f, 5, 30f, i)
    for (i in 0 until bAll.size) if (i % 4 == 0) drip(bAll[i][0], bAll[i][1] + bAll[i][2], 2, 16f, -1)

    return Layout(
        nL, ids, lx, ly, lbend, lcol, lslot, istart, nI, ix, iy, iph, ibend, ij, inew,
        dust, blots, stains, cracks, specks, spores, dl.toFloatArray(),
    )
}

// ---------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------

private fun decode(context: Context, id: Int): Bitmap {
    val opts = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeResource(context.resources, id, opts)
}

private fun rnd(v: Float): Int = floor(v + 0.5f).toInt()

private fun mixRgb(a: Int, b: Int, m: Float): Int {
    val k = m.coerceIn(0f, 1f)
    val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * k).toInt()
    val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * k).toInt()
    val bl = ((a and 255) + ((b and 255) - (a and 255)) * k).toInt()
    return (r shl 16) or (g shl 8) or bl
}

private val BEADS = floatArrayOf(0.25f, 0.45f, 0.62f, 0.8f)
private val SPECK_COLS = intArrayOf(0x1E0A24, 0x4A1650, 0x7A2A6A)

/**
 * Renders the map into a low-res bitmap (one bitmap pixel = one "world unit" at zoom 1). All pixel
 * work is integer-coordinate drawRect with a shared Paint; glows reuse one pre-rendered bitmap.
 */
private class BrainRenderer(context: Context) {
    var bmp: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private set
    private var cv = android.graphics.Canvas(bmp)
    var W = 1
        private set
    var H = 1
        private set

    /** Animated idea positions (world) for the current frame, read by the label pass. */
    var ipx = FloatArray(0)
    var ipy = FloatArray(0)

    private val coreSprite = decode(context, app.vesper.R.drawable.brain_main)
    private val lobeSprite = decode(context, app.vesper.R.drawable.brain_lobe)

    private val px = Paint().apply { isAntiAlias = false; isFilterBitmap = false; isDither = false }
    private val spritePaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val blitPaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val glowBmp: Bitmap = Bitmap.createBitmap(
        IntArray(64 * 64) { i ->
            val dx = (i % 64 - 31.5f) / 32f
            val dy = (i / 64 - 31.5f) / 32f
            val a = (1f - hypot(dx, dy)).coerceIn(0f, 1f)
            ((a * 255f).toInt() shl 24) or 0xFFFFFF
        },
        64, 64, Bitmap.Config.ARGB_8888,
    )
    private val glowAdd = Paint().apply { isAntiAlias = false; isFilterBitmap = true; xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val glowOver = Paint().apply { isAntiAlias = false; isFilterBitmap = true }
    private val filters = HashMap<Int, PorterDuffColorFilter>()
    private val ditherPaint = Paint().apply {
        isAntiAlias = false; isFilterBitmap = false
        shader = BitmapShader(
            Bitmap.createBitmap(intArrayOf(0xFF000000.toInt(), 0, 0, 0xFF000000.toInt()), 2, 2, Bitmap.Config.ARGB_8888),
            Shader.TileMode.REPEAT, Shader.TileMode.REPEAT,
        )
        alpha = (0.26f * ROT * 255f).toInt()
    }
    private val vignette = Paint().apply { isAntiAlias = false }
    private val rectF = RectF()
    private val rectI = Rect()
    private val dst = Rect()

    // per-frame state
    private var t = 0f
    private var camX = 0f
    private var camY = 0f
    private var z = 1f
    private var beatPh = 0f
    private val lo = (rnd(110 - 40 * ROT) shl 16) or (rnd(80 - 48 * ROT) shl 8) or rnd(170 - 84 * ROT)
    private val hi = (245 shl 16) or (150 shl 8) or 225

    // ctrl / bezier scratch
    private var ccx = 0f; private var ccy = 0f; private var cnx = 0f; private var cny = 0f; private var clen = 0f
    private var bzx = 0f; private var bzy = 0f

    fun resize(w: Int, h: Int) {
        if (w == W && h == H && bmp.width == w) return
        W = w; H = h
        bmp.recycle()
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cv = android.graphics.Canvas(bmp)
        val c0 = android.graphics.Color.argb(0, 4, 1, 8)
        val c1 = android.graphics.Color.argb(((0.55f + 0.35f * ROT) * 255f).toInt(), 4, 1, 8)
        vignette.shader = RadialGradient(
            w / 2f, h / 2f, h * 0.62f, intArrayOf(c0, c0, c1), floatArrayOf(0f, 0.22f / 0.62f, 1f), Shader.TileMode.CLAMP,
        )
    }

    /** Scales the low-res frame up to [pxSize] device px per pixel with no filtering. */
    fun blit(target: android.graphics.Canvas, pxSize: Int) {
        dst.set(0, 0, W * pxSize, H * pxSize)
        target.drawBitmap(bmp, null, dst, blitPaint)
    }

    // ---- helpers ----
    private fun sx(wx: Float) = (wx - camX) * z + W * 0.5f
    private fun sy(wy: Float) = (wy - camY) * z + H * 0.5f

    private fun beat(s: Float): Float { val d = (s - beatPh) * 5f; return exp(-d * d) }

    private fun dot(x: Int, y: Int, w: Int, h: Int, rgb: Int, a: Float) {
        if (x + w <= 0 || y + h <= 0 || x >= W || y >= H) return
        val al = (a * 255f).toInt().coerceIn(0, 255)
        if (al == 0) return
        px.color = (al shl 24) or (rgb and 0xFFFFFF)
        cv.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), px)
    }

    /** Pixel "circle": two overlapping rects (plus shape). [x], [y], [r] are already low-res. */
    private fun disc(x: Float, y: Float, r: Float, rgb: Int, a: Float) {
        if (r < 0.8f) { dot(rnd(x), rnd(y), 1, 1, rgb, a); return }
        val d = max(1, rnd(r * 2f))
        val e = max(1, rnd(r * 1.3f))
        val o = rnd((d - e) / 2f)
        val ax = rnd(x - r)
        val by = rnd(y - r)
        dot(ax, by + o, d, e, rgb, a)
        dot(ax + o, by, e, d, rgb, a)
    }

    private fun filterFor(rgb: Int): PorterDuffColorFilter =
        filters.getOrPut(rgb) { PorterDuffColorFilter(0xFF000000.toInt() or rgb, PorterDuff.Mode.SRC_IN) }

    private fun glowAt(wx: Float, wy: Float, r: Float, rgb: Int, a: Float, over: Boolean = false) {
        val rr = r * z
        if (rr < 0.5f) return
        val al = (a * 255f).toInt().coerceIn(0, 255)
        if (al == 0) return
        val cx = sx(wx); val cy = sy(wy)
        if (cx + rr < 0 || cy + rr < 0 || cx - rr > W || cy - rr > H) return
        val p = if (over) glowOver else glowAdd
        p.colorFilter = filterFor(rgb)
        p.alpha = al
        rectF.set(cx - rr, cy - rr, cx + rr, cy + rr)
        cv.drawBitmap(glowBmp, null, rectF, p)
    }

    private fun ctrl(x1: Float, y1: Float, x2: Float, y2: Float, bend: Float) {
        val dx = x2 - x1; val dy = y2 - y1
        var len = hypot(dx, dy)
        if (len == 0f) len = 1f
        clen = len; cnx = -dy / len; cny = dx / len
        ccx = (x1 + x2) / 2f - dy / len * bend
        ccy = (y1 + y2) / 2f + dx / len * bend
    }

    private fun bz(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float, s: Float) {
        val u = 1f - s
        bzx = u * u * x1 + 2f * u * s * cx + s * s * x2
        bzy = u * u * y1 + 2f * u * s * cy + s * s * y2
    }

    /** Dotted circle: 3 px per segment, every 4th segment skipped (shifted by floor(rot)). */
    private fun ring(wx: Float, wy: Float, r: Float, segs: Int, rot: Float, rgb: Int, a: Float) {
        val rr = r * z
        val ox = sx(wx); val oy = sy(wy)
        if (ox + rr < 0 || oy + rr < 0 || ox - rr > W || oy - rr > H) return
        val fl = floor(rot).toInt()
        val step = TAU / segs
        for (i in 0 until segs) {
            if (((i + fl) % 4 + 4) % 4 == 0) continue
            val a0 = i * step + rot * 0.05f
            for (k in 0..2) {
                val an = a0 + step * (k / 3f)
                dot(rnd(ox + cos(an) * rr), rnd(oy + sin(an) * rr), 1, 1, rgb, a)
            }
        }
    }

    private fun sprite(bm: Bitmap, wx: Float, wy: Float, scale: Float, alpha: Float, flip: Boolean) {
        val w = bm.width * scale * z
        val h = bm.height * scale * z
        val cx = sx(wx); val cy = sy(wy)
        if (cx + w < 0 || cy + h < 0 || cx - w > W || cy - h > H) return
        rectI.set(rnd(cx - w / 2f), rnd(cy - h / 2f), rnd(cx + w / 2f), rnd(cy + h / 2f))
        spritePaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        if (flip) {
            cv.save(); cv.scale(-1f, 1f, cx, cy)
            cv.drawBitmap(bm, null, rectI, spritePaint)
            cv.restore()
        } else cv.drawBitmap(bm, null, rectI, spritePaint)
    }

    private fun vein(x2: Float, y2: Float, bend: Float, strands: Int, seed: Float, alpha: Float) {
        for (st in 0 until strands) {
            ctrl(0f, 0f, x2, y2, bend + (st - (strands - 1) / 2f) * (1f + 2.5f * ROT))
            val cx = ccx; val cy = ccy; val nx = cnx; val ny = cny
            val n = ceil(clen * z * 1.2f).toInt().coerceIn(1, 3000)
            for (i in 0..n) {
                val s = i / n.toFloat()
                bz(0f, 0f, cx, cy, x2, y2, s)
                val wob = sin(s * 11f + st * 2.1f + seed + t * 0.6f) * 1.6f * ROT * (1f - abs(s - 0.5f))
                val dead = sin(s * 37f + seed * 3f + st) > 0.93f
                val rgb = if (dead) 0x14061A else mixRgb(lo, hi, beat(s))
                dot(rnd(sx(bzx + nx * wob)), rnd(sy(bzy + ny * wob)), 1, 1, rgb, alpha)
            }
            if (st == 0) {
                for (k in 0..3) {
                    bz(0f, 0f, cx, cy, x2, y2, BEADS[k])
                    disc(sx(bzx), sy(bzy), max(1f, z * (0.6f + (0.4f * k) % 2f)), if (k % 2 == 1) 0xD45AAB else 0x5A1A5A, alpha)
                }
            }
        }
    }

    private fun thread(x1: Float, y1: Float, x2: Float, y2: Float, bend: Float, ph: Float, rgb: Int, dim: Float) {
        ctrl(x1, y1, x2, y2, bend)
        val cx = ccx; val cy = ccy; val nx = cnx; val ny = cny
        val n = ceil(clen * z * 1.2f).toInt().coerceIn(1, 1500)
        for (i in 0..n) {
            val s = i / n.toFloat()
            bz(x1, y1, cx, cy, x2, y2, s)
            val wob = sin(s * 9f + ph * 7f + t * 0.6f) * 0.9f * ROT * (1f - abs(s - 0.5f))
            val xx = rnd(sx(bzx + nx * wob)); val yy = rnd(sy(bzy + ny * wob))
            if (sin(s * 29f + ph * 5f) > 0.9f) dot(xx, yy, 1, 1, 0x14061A, dim)
            else dot(xx, yy, 1, 1, rgb, (0.35f + 0.5f * beat(1f + s * 0.5f)) * dim)
        }
    }

    /** One frame, in the mockup's draw order. [sel] is the selected lobe index or -1. */
    fun draw(time: Float, camX: Float, camY: Float, zoom: Float, L: Layout, sel: Int) {
        t = time; this.camX = camX; this.camY = camY; z = zoom
        beatPh = (t * 0.45f) % 1.7f
        if (ipx.size < L.nI) { ipx = FloatArray(L.nI); ipy = FloatArray(L.nI) }
        fun dimOf(i: Int) = if (sel < 0 || sel == i) 1f else 0.45f

        bmp.eraseColor(0xFF07030B.toInt())

        // fog
        for (i in 0 until 7) {
            glowAt(sin(t * 0.05f + i * 1.3f) * 80f, cos(t * 0.04f + i * 1.7f) * 150f, 80f, if (i % 2 == 1) 0x5A1A6A else 0x3A1A7A, 0.5f * ROT)
        }
        // dark stains
        for (i in 0 until L.stains.size / 3) glowAt(L.stains[i * 3], L.stains[i * 3 + 1], L.stains[i * 3 + 2], 0x030105, 0.55f * ROT, over = true)

        // dust, with parallax
        val f = 0.6f + z * 0.25f
        val spanX = 420f * f; val spanY = 620f * f
        for (i in 0 until L.dust.size / 4) {
            var ddx = (L.dust[i * 4] - camX * 0.5f) * f
            var ddy = (L.dust[i * 4 + 1] - camY * 0.5f) * f
            ddx = ((ddx + spanX / 2f) % spanX + spanX) % spanX - spanX / 2f
            ddy = ((ddy + spanY / 2f) % spanY + spanY) % spanY - spanY / 2f
            val a = (1f - ROT * 0.55f) * (0.3f + 0.7f * max(0f, sin(t * 1.3f + L.dust[i * 4 + 2]))) * L.dust[i * 4 + 3]
            dot(rnd(W / 2f + ddx), rnd(H / 2f + ddy), 1, 1, 0xCDB8FF, a)
        }

        // big rings + core aura
        floatArrayOf(60f, 105f, 150f).forEachIndexed { i, r -> ring(0f, 0f, r, 90, t * (if (i % 2 == 1) -1f else 1f), 0x39234C, 0.6f * (1f - ROT * 0.5f)) }
        glowAt(0f, 0f, 90f, 0x5A2FA0, 0.3f)

        // cracks
        for (i in L.cracks.indices) {
            val pts = L.cracks[i]
            val rgb = if (i % 3 != 0) 0x1A0820 else 0x2E0F36
            var k = 0
            while (k + 3 < pts.size) {
                val x0 = sx(pts[k]); val y0 = sy(pts[k + 1]); val x1 = sx(pts[k + 2]); val y1 = sy(pts[k + 3])
                val n = ceil(hypot(x1 - x0, y1 - y0)).toInt().coerceIn(1, 400)
                for (q in 0..n) {
                    val s = q / n.toFloat()
                    dot(rnd(x0 + (x1 - x0) * s), rnd(y0 + (y1 - y0) * s), 1, 1, rgb, 1f)
                }
                k += 2
            }
        }
        // mold specks
        val sk = if (z > 2f) 2 else 1
        for (i in 0 until L.specks.size / 3) {
            dot(rnd(sx(L.specks[i * 3])), rnd(sy(L.specks[i * 3 + 1])), sk, sk, SPECK_COLS[L.specks[i * 3 + 2].toInt()], 1f)
        }
        // rot spots
        for (i in 0 until L.blots.size / 4) {
            val bx = L.blots[i * 4]; val by = L.blots[i * 4 + 1]
            val rr = L.blots[i * 4 + 2] * (1f + 0.12f * sin(t * 0.8f + L.blots[i * 4 + 3]))
            val hot = 0.3f + 0.7f * beat(hypot(bx, by) / 70f)
            val cx = sx(bx); val cy = sy(by)
            disc(cx, cy, (rr + 1f) * z, mixRgb(0x3A1440, 0x962882, hot * 0.6f), 1f)
            disc(cx, cy, rr * z, 0x06020A, 1f)
            if (rr * z > 3f) dot(rnd(cx - 0.3f * rr * z), rnd(cy - 0.3f * rr * z), 1, 1, 0x2A0C30, 1f)
        }

        // veins + threads
        for (i in 0 until L.nL) {
            val d = dimOf(i)
            vein(L.lx[i], L.ly[i], L.lbend[i], 3, i * 5f, d)
            for (k in L.istart[i] until L.istart[i + 1]) {
                val nx = L.ix[k] + sin(t * 0.5f + L.iph[k]) * 1.5f
                val ny = L.iy[k] + cos(t * 0.4f + L.iph[k]) * 1.5f
                ipx[k] = nx; ipy[k] = ny
                thread(L.lx[i], L.ly[i], nx, ny, L.ibend[k], L.iph[k], L.lcol[i], d)
            }
        }
        // heartbeat pulse travelling out each vein
        val ps = (t * 0.45f) % 1.7f
        if (ps <= 1f) {
            for (i in 0 until L.nL) {
                val d = dimOf(i)
                ctrl(0f, 0f, L.lx[i], L.ly[i], L.lbend[i])
                bz(0f, 0f, ccx, ccy, L.lx[i], L.ly[i], ps)
                glowAt(bzx, bzy, 6f, 0xF0C8F0, 0.9f * d)
                val w = if (z > 2f) 2 else 1
                dot(rnd(sx(bzx)), rnd(sy(bzy)), w, w, 0xFFFFFF, d)
            }
        }

        // ideas
        for (i in 0 until L.nL) {
            val d = dimOf(i)
            val col = L.lcol[i]
            for (k in L.istart[i] until L.istart[i + 1]) {
                val ph = L.iph[k]
                val a = 0.55f + 0.25f * sin(t * 1.7f + ph) + 0.5f * beat(1.5f)
                glowAt(ipx[k], ipy[k], max(3f, 7f - L.ij[k] * 0.5f), col, a * d)
                if (L.inew[k]) glowAt(ipx[k], ipy[k], 11f, 0xD45AAB, 0.5f * d)
                val s = max(2, rnd(2f * z / 1.4f))
                val cx = sx(ipx[k]); val cy = sy(ipy[k])
                disc(cx, cy, s * 0.9f + 1f, 0x2A0C30, d)
                val ds = max(1, s shr 1)
                dot(rnd(cx + s * 0.6f), rnd(cy - s * 0.2f), ds, ds, 0x06020A, d)
                dot(rnd(cx - s / 2f), rnd(cy - s / 2f), s, s, 0xF6EEFF, d)
            }
        }

        // drips
        if (ROT > 0.2f) {
            val fade = min(1f, (ROT - 0.2f) * 2f)
            val wh = max(1, rnd(z * 1.5f))
            for (k in 0 until L.drips.size / 5) {
                val dx0 = L.drips[k * 5]; val dy0 = L.drips[k * 5 + 1]
                val n = L.drips[k * 5 + 2].toInt(); val len = L.drips[k * 5 + 3]
                val owner = L.drips[k * 5 + 4].toInt()
                val d = if (owner >= 0) dimOf(owner) else 1f
                for (i in 0 until n) {
                    val ph = i * 1.7f + k * 0.9f
                    val x = dx0 + sin(ph * 3f) * (if (n > 5) 14f else 6f)
                    val y = dy0 + (t * 7f + ph * 13f) % len
                    val a = (1f - (y - dy0) / len) * fade * d
                    dot(rnd(sx(x)), rnd(sy(y)), 1, wh, if (i % 3 != 0) 0x8A3A9A else 0xC050A8, a)
                }
            }
        }

        // lobes
        for (i in 0 until L.nL) {
            val d = dimOf(i)
            val isSel = i == sel
            val col = L.lcol[i]
            val ga = (0.3f + 0.5f * beat(1f)) * d
            glowAt(L.lx[i], L.ly[i], 26f, col, ga)
            if (isSel) glowAt(L.lx[i], L.ly[i], 26f, col, ga)
            ring(L.lx[i], L.ly[i], 15f, 32, t * 3f, if (isSel) 0xB69AFF else col, 0.8f * d)
            sprite(lobeSprite, L.lx[i], L.ly[i], 0.5f, d, L.lslot[i] % 2 == 1)
        }

        // core
        glowAt(0f, 0f, 48f, 0xB03AA0, 0.25f + 0.6f * beat(0.05f))
        glowAt(0f, 0f, 36f, 0xB69AFF, 0.3f)
        ring(0f, 0f, 30f, 48, t * 2f, 0xB69AFF, 0.85f)
        ring(0f, 0f, 35f, 56, -t * 1.5f, 0xD45AAB, 0.7f)
        sprite(coreSprite, 0f, 0f, 0.64f, 1f, false)

        // spores
        for (i in 0 until L.spores.size / 4) {
            val ph = L.spores[i * 4 + 2]; val sp = L.spores[i * 4 + 3]
            val x = L.spores[i * 4] + sin(t * 0.2f + ph) * 10f
            val y = L.spores[i * 4 + 1] - (t * sp + ph * 30f) % 60f + 30f
            val a = 0.4f + 0.6f * max(0f, sin(t * 2f + ph))
            dot(rnd(sx(x)), rnd(sy(y)), 1, 1, if (i % 3 != 0) 0xD45AAB else 0xB69AFF, a)
            if (i % 4 == 0) glowAt(x, y, 3f, 0xD45AAB, a * 0.5f)
        }

        // 2x2 dither + vignette
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), ditherPaint)
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), vignette)
    }
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

@Composable
fun BrainScreen(
    lobes: List<Lobe>?,
    ideas: List<Idea>?,
    notes: List<Note>?,
    camera: BrainCamera,
    selectedLobe: Long,
    onSelectLobe: (Long) -> Unit,
    onAddLobe: (String) -> Unit,
    onDeleteLobe: (Long) -> Unit,
    onSaveIdea: (Idea) -> Unit,
    onDeleteIdea: (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
) {
    val lobeList = lobes.orEmpty()
    val ideaList = ideas.orEmpty()
    val placed = remember(lobeList, ideaList) { placeIdeas(lobeList, ideaList) }
    val selected = lobeList.firstOrNull { it.id == selectedLobe }

    var addingLobe by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // null = no dialog; an Idea with id 0 is a brand-new idea in its lobe.
    var editing by remember { mutableStateOf<Idea?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BrainCanvas(
                lobeList, placed, selected?.id ?: -1L, camera,
                onTapLobe = { id -> onSelectLobe(id); sheetOpen = false },
                onTapIdea = { editing = it },
                onTapEmpty = { onSelectLobe(-1L) },
            )
            ScreenTitle("Brain")
            SigilFab(
                "Grow a new lobe",
                onClick = { addingLobe = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            )
        }
        if (selected != null) {
            LobeSheet(
                lobe = selected,
                ideas = ideaList.filter { it.lobeId == selected.id },
                notes = notes.orEmpty(),
                open = sheetOpen,
                onToggle = { sheetOpen = !sheetOpen },
                onAddIdea = { editing = Idea(lobeId = selected.id, title = "", body = "") },
                onOpenIdea = { editing = it },
                onDelete = { confirmDelete = true },
            )
        }
    }

    if (addingLobe) {
        NameDialog("NEW LOBE", "Name it: Coding, Business...", onDismiss = { addingLobe = false }, onSubmit = onAddLobe)
    }
    if (confirmDelete && selected != null) {
        PixelDialog({ confirmDelete = false }) {
            Text("DELETE ${selected.name.uppercase()}?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text(
                "Its ideas go with it, forever. No undo.",
                style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { confirmDelete = false })
                PixelButton("DELETE", {
                    confirmDelete = false
                    onSelectLobe(-1L)
                    onDeleteLobe(selected.id)
                }, primary = true)
            }
        }
    }
    editing?.let { idea ->
        IdeaDialog(
            idea = idea,
            notes = notes.orEmpty(),
            onDismiss = { editing = null },
            onSave = { onSaveIdea(it); editing = null },
            onDelete = { onDeleteIdea(idea.id); editing = null },
            onOpenNote = { editing = null; onOpenNote(it) },
        )
    }
}


@Composable
private fun BrainCanvas(
    lobes: List<Lobe>,
    placed: List<Placed>,
    selectedId: Long,
    cam: BrainCamera,
    onTapLobe: (Long) -> Unit,
    onTapIdea: (Idea) -> Unit,
    onTapEmpty: () -> Unit,
) {
    val density = LocalDensity.current.density
    val pxSize = remember(density) { round(2.28f * density).coerceAtLeast(1f) }
    val context = LocalContext.current
    val renderer = remember { BrainRenderer(context) }
    val layout = remember(lobes, placed) { buildLayout(lobes, placed) }
    val selIdx = layout.lobeIds.indexOf(selectedId)
    val measurer = rememberTextMeasurer(cacheSize = 200)
    val lobeStyle = remember { TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Color(0xFFF0E8FA)) }
    val ideaStyle = remember { TextStyle(fontFamily = PixelFont, fontSize = 7.sp, color = Color(0xFFE6DBF5)) }
    val newestStyle = remember { TextStyle(fontFamily = PixelFont, fontSize = 7.sp, color = Color(0xFFFFC8EF)) }
    val hintStyle = remember { TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.TextDim) }

    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var t0 = 0L
        while (true) {
            withFrameNanos { n ->
                if (t0 == 0L) t0 = n
                time = (n - t0) / 1_000_000_000f
            }
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val ox = ceil(size.width / pxSize) * pxSize / 2f
                    val oy = ceil(size.height / pxSize) * pxSize / 2f
                    val old = cam.scale
                    val next = (old * zoom).coerceIn(0.6f, 4f)
                    val rx = centroid.x - ox
                    val ry = centroid.y - oy
                    // Keep the world point under the fingers fixed, then follow the pan.
                    val wx = cam.x + rx / (pxSize * old)
                    val wy = cam.y + ry / (pxSize * old)
                    cam.scale = next
                    cam.x = wx - (rx + pan.x) / (pxSize * next)
                    cam.y = wy - (ry + pan.y) / (pxSize * next)
                }
            }
            .pointerInput(lobes, placed) {
                detectTapGestures { tap ->
                    val u = pxSize * cam.scale
                    val ox = ceil(size.width / pxSize) * pxSize / 2f
                    val oy = ceil(size.height / pxSize) * pxSize / 2f
                    fun screen(w: Offset) = Offset((w.x - cam.x) * u + ox, (w.y - cam.y) * u + oy)
                    val idea = placed.minByOrNull { (screen(it.pos) - tap).getDistance() }
                    if (idea != null && (screen(idea.pos) - tap).getDistance() <= maxOf(26.dp.toPx(), 6f * u)) {
                        onTapIdea(idea.idea)
                        return@detectTapGestures
                    }
                    val lobe = lobes.minByOrNull { (screen(lobeWorldPos(it.slot)) - tap).getDistance() }
                    if (lobe != null && (screen(lobeWorldPos(lobe.slot)) - tap).getDistance() <= maxOf(36.dp.toPx(), 14f * u)) {
                        onTapLobe(lobe.id)
                        return@detectTapGestures
                    }
                    onTapEmpty()
                }
            },
    ) {
        val vw = size.width.toInt()
        val vh = size.height.toInt()
        if (vw <= 0 || vh <= 0) return@Canvas
        val px = pxSize.toInt()
        renderer.resize(ceil(vw / pxSize).toInt(), ceil(vh / pxSize).toInt())
        renderer.draw(time, cam.x, cam.y, cam.scale, layout, selIdx)
        drawIntoCanvas { renderer.blit(it.nativeCanvas, px) }

        // Labels at full resolution, same world -> device transform as the bitmap.
        val z = cam.scale
        val u = pxSize * z
        val ox = renderer.W * pxSize / 2f
        val oy = renderer.H * pxSize / 2f
        fun dx(wx: Float) = (wx - cam.x) * u + ox
        fun dy(wy: Float) = (wy - cam.y) * u + oy

        for (i in 0 until layout.nL) {
            val dim = if (selIdx < 0 || selIdx == i) 1f else 0.45f
            val name = lobes[i].name.uppercase()
            if (name.isBlank()) continue
            val tl = measurer.measure(
                name, lobeStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = (150f * density).toInt().coerceAtLeast(60)),
            )
            val pad = 3f * density
            val left = dx(layout.lx[i]) - tl.size.width / 2f - pad
            val top = dy(layout.ly[i] + 12f)
            val pw = tl.size.width + pad * 2f
            val ph = tl.size.height + pad * 2f
            if (left > size.width || left + pw < 0f || top > size.height || top + ph < 0f) continue
            drawRect(Color(16, 8, 23).copy(alpha = 0.85f * dim), Offset(left, top), Size(pw, ph))
            val bar = max(1f, density)
            drawRect(Color(layout.lcol[i] or 0xFF000000.toInt()).copy(alpha = dim), Offset(left, top + ph - bar), Size(pw, bar))
            drawText(tl, topLeft = Offset(left + pad, top + pad), alpha = dim)
        }

        val titleAlpha = ((z - 1.6f) / 0.5f).coerceIn(0f, 1f)
        if (titleAlpha > 0f && renderer.ipx.size >= layout.nI) {
            for (i in 0 until layout.nL) {
                val dim = if (selIdx < 0 || selIdx == i) 1f else 0.45f
                for (k in layout.istart[i] until layout.istart[i + 1]) {
                    val p = placed.getOrNull(k) ?: continue
                    val label = ideaLabel(p.idea)
                    if (label.isBlank()) continue
                    val tl = measurer.measure(
                        if (layout.inew[k]) "$label *" else label,
                        if (layout.inew[k]) newestStyle else ideaStyle,
                        overflow = TextOverflow.Ellipsis, maxLines = 1,
                        constraints = Constraints(maxWidth = (120f * density).toInt().coerceAtLeast(50)),
                    )
                    val left = dx(renderer.ipx[k] + 4f)
                    val top = dy(renderer.ipy[k] - 2f) - tl.size.height / 2f
                    if (left > size.width || left + tl.size.width < 0f || top > size.height || top + tl.size.height < 0f) continue
                    drawText(tl, color = Color(0xFF07030B), topLeft = Offset(left + density, top + density), alpha = titleAlpha * dim)
                    drawText(tl, topLeft = Offset(left, top), alpha = titleAlpha * dim)
                }
            }
        }

        if (layout.nL == 0) {
            val hint = measurer.measure("TAP + TO GROW A LOBE", hintStyle)
            drawText(hint, topLeft = Offset(dx(0f) - hint.size.width / 2f, dy(44f)))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Resume sheet
// ---------------------------------------------------------------------------------------------

@Composable
private fun LobeSheet(
    lobe: Lobe,
    ideas: List<Idea>,
    notes: List<Note>,
    open: Boolean,
    onToggle: () -> Unit,
    onAddIdea: () -> Unit,
    onOpenIdea: (Idea) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .pixelCard(fill = Px.Surface, border = lobeColor(lobe), shadow = false, notch = 4.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lobe.name.uppercase(),
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender),
            )
            Text(if (open) "v" else "^", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.TextDim))
        }
        if (!open) {
            Text(
                if (ideas.isEmpty()) "Nothing here yet. Tap to open." else "LAST: " + ideaLabel(ideas.first()),
                Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.TextDim),
            )
        } else {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelButton("+ IDEA", onAddIdea, primary = true, modifier = Modifier.weight(1f))
                PixelButton("DELETE LOBE", onDelete, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text("LAST THOUGHTS", style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.TextDim))
            Spacer(Modifier.height(8.dp))
            if (ideas.isEmpty()) {
                Text(
                    "Tap + IDEA to plant the first one.",
                    style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim.copy(alpha = 0.7f)),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 230.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ideas, key = { it.id }) { idea ->
                        val note = idea.noteId?.let { id -> notes.firstOrNull { it.id == id } }
                        Column(
                            Modifier.fillMaxWidth().pixelCard(fill = Px.Bg, shadow = false, notch = 2.dp)
                                .clickable { onOpenIdea(idea) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                ideaLabel(idea), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, color = Px.Text),
                            )
                            if (idea.title.isNotBlank() && idea.body.isNotBlank()) {
                                Text(
                                    idea.body.replace('\n', ' '), Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, lineHeight = 14.sp, color = Px.TextDim),
                                )
                            }
                            if (idea.noteId != null) {
                                Text(
                                    "> " + (note?.let { noteName(it) } ?: "(note deleted)"), Modifier.padding(top = 4.dp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.Magenta),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------

@Composable
private fun PixelField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = 0.dp,
    onDone: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(fontFamily = PixelFont, fontSize = 11.sp, lineHeight = 18.sp, color = Px.Text),
        cursorBrush = SolidColor(Px.Magenta),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = minHeight).pixelCard(fill = Px.Bg, shadow = false, notch = 2.dp)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty()) {
                    Text(hint, style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim.copy(alpha = 0.45f)))
                }
                inner()
            }
        },
    )
}

@Composable
private fun NameDialog(title: String, hint: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) onSubmit(text)
        onDismiss()
    }
    PixelDialog(onDismiss) {
        Text(title, style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(16.dp))
        PixelField(text, { text = it.take(24) }, hint, onDone = ::submit)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            PixelButton("CANCEL", onDismiss)
            PixelButton("GROW", ::submit, primary = true)
        }
    }
}

@Composable
private fun IdeaDialog(
    idea: Idea,
    notes: List<Note>,
    onDismiss: () -> Unit,
    onSave: (Idea) -> Unit,
    onDelete: () -> Unit,
    onOpenNote: (Long) -> Unit,
) {
    var title by remember { mutableStateOf(idea.title) }
    var body by remember { mutableStateOf(idea.body) }
    var noteId by remember { mutableStateOf(idea.noteId) }
    var picking by remember { mutableStateOf(false) }
    val linked = noteId?.let { id -> notes.firstOrNull { it.id == id } }

    PixelDialog(onDismiss) {
        Text(if (idea.id == 0L) "NEW IDEA" else "IDEA", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(14.dp))
        PixelField(title, { title = it }, "Title")
        Spacer(Modifier.height(10.dp))
        PixelField(body, { body = it }, "What are you thinking? What did you do? What next?", singleLine = false, minHeight = 110.dp)
        Spacer(Modifier.height(12.dp))
        if (noteId != null) {
            Text(
                "> " + (linked?.let { noteName(it) } ?: "(note deleted)"),
                Modifier.fillMaxWidth().let { m -> if (linked != null) m.clickable { onOpenNote(linked.id) } else m },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Magenta, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PixelButton(if (noteId == null) "LINK NOTE" else "CHANGE NOTE", { picking = true }, modifier = Modifier.weight(1f))
            if (noteId != null) PixelButton("UNLINK", { noteId = null }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (idea.id != 0L) PixelButton("DELETE", onDelete)
            PixelButton("CANCEL", onDismiss)
            PixelButton("SAVE", { onSave(idea.copy(title = title, body = body, noteId = noteId)) }, primary = true)
        }
    }
    if (picking) {
        PixelDialog({ picking = false }) {
            Text("LINK A NOTE", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
            Spacer(Modifier.height(12.dp))
            if (notes.isEmpty()) {
                Text("No notes yet.", style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.TextDim))
            } else {
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(notes, key = { it.id }) { n ->
                        Text(
                            noteName(n),
                            Modifier.fillMaxWidth().pixelCard(fill = Px.Bg, shadow = false, notch = 2.dp)
                                .clickable { noteId = n.id; picking = false }.padding(horizontal = 12.dp, vertical = 12.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, color = Px.Text),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PixelButton("CLOSE", { picking = false }) }
        }
    }
}
