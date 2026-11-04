package app.vesper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.coroutineScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.TileMode
import app.vesper.res.Res
import app.vesper.res.brain_lobe
import app.vesper.res.brain_main
import app.vesper.res.vault_skull
import app.vesper.sync.MIN_PASSPHRASE
import app.vesper.sync.SyncManager
import app.vesper.sync.SyncUi
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.vesper.res.vault_skull_dead
import org.jetbrains.compose.resources.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vesper.Vault
import app.vesper.data.BackupException
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.Note
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import kotlin.math.sqrt

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

internal class Placed(
    val idea: Idea, val lobe: Lobe, val pos: Offset,
    val ph: Float, val bend: Float, val j: Int, val newest: Boolean,
)

internal fun placeIdeas(lobes: List<Lobe>, ideas: List<Idea>): List<Placed> {
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

/** Camera zoom when a lobe is selected; the thought rings are laid out for this zoom. */
private const val ORBIT_ZOOM = 2f

private class SelRing(val tls: List<TextLayoutResult>, val target: List<Offset>) {
    val glows = tls.map(::GlowText)
}

/**
 * A label whose blurred Shadow glow is rasterized once into a bitmap and then just blitted.
 * Android re-blurs a text shadow on every draw, which is costly when done for many labels every frame.
 */
internal class GlowText(val tl: TextLayoutResult) {
    private var img: ImageBitmap? = null
    // the shadow reaches ~3 sigma ≈ 1.73 * radius past the glyphs
    private val pad = ceil(1.8f * (tl.layoutInput.style.shadow?.blurRadius ?: 0f)).toInt() + 2

    fun draw(scope: DrawScope, left: Float, top: Float, alpha: Float) {
        val im = img ?: ImageBitmap(tl.size.width + 2 * pad, tl.size.height + 2 * pad).also { bm ->
            CanvasDrawScope().draw(scope, scope.layoutDirection, androidx.compose.ui.graphics.Canvas(bm), Size(bm.width.toFloat(), bm.height.toFloat())) {
                drawText(tl, topLeft = Offset(pad.toFloat(), pad.toFloat()))
            }
            img = bm
        }
        scope.drawImage(im, Offset(round(left) - pad, round(top) - pad), alpha = alpha)
    }
}

/**
 * Ring positions (world units) around [c] for labels [tls]; [u] is device px per world unit at the target zoom.
 * Ellipses 4:3, evenly spaced angles, per-ring count limited by the longest label; overflow goes to wider rings.
 */
private fun ringPositions(c: Offset, tls: List<TextLayoutResult>, u: Float, density: Float): List<Offset> {
    if (tls.isEmpty()) return emptyList()
    val maxW = tls.maxOf { it.size.width.toFloat() }
    val h = tls.maxOf { it.size.height.toFloat() }
    val spacing = (maxW / u).coerceAtLeast(8f)
    // clear the lobe name under the lobe (12 world + ~28dp tall) and the lobe itself beside it
    val ry0 = (12f * u + 28f * density + h / 2f + 6f * density) / u
    var rx = max(max(32f, ry0 / 0.75f), maxW / (2f * u) + 16f).coerceAtMost(60f)
    val step = (h / u * 1.8f) / 0.75f
    val out = ArrayList<Offset>(tls.size)
    var ring = 0
    while (out.size < tls.size) {
        val a = rx; val b = rx * 0.75f
        val perim = Math.PI.toFloat() * (3f * (a + b) - sqrt((3f * a + b) * (a + 3f * b)))
        val n = floor(perim / spacing).toInt().coerceAtLeast(1).coerceAtMost(tls.size - out.size)
        val off = -TAU / 4f + (ring % 2) * (TAU / n / 2f)
        for (j in 0 until n) {
            val ang = off + j * TAU / n
            out.add(Offset(c.x + cos(ang) * a, c.y + sin(ang) * b))
        }
        rx += step
        ring++
    }
    return out
}

/** The vault sun: one fixed node (not a lobe), centered below the brain (x = 0), past the first lobe ring. */
private const val SUN_X = 0f
private const val SUN_Y = 125f
private const val SUN_BEND = 8f
private const val SKULL_SCALE = 1f // sprite is pre-sized: 22x26 world units (lobe: 24x19.5)
/** Eye centers in sprite pixels relative to the sprite center (from the bright pink pixels). */
private val SKULL_EYES = arrayOf(floatArrayOf(-1f, 0f), floatArrayOf(7f, 0f))

/** 0 = fresh, 1 = fully eclipsed: grace of 7 days, then 14 days to go dark. Never backed up = 1. */
private fun staleness(last: Long?): Float =
    if (last == null) 1f else (((System.currentTimeMillis() - last) / 86_400_000f - 7f) / 14f).coerceIn(0f, 1f)

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
internal class Layout(
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

internal fun buildLayout(lobes: List<Lobe>, placedIn: List<Placed>): Layout {
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
 * Renders the map into a low-res [Pix] (one pixel = one "world unit" at zoom 1). All pixel work is
 * integer-coordinate rects; glows reuse Pix's pre-rendered sprite. Sprites arrive decoded (drawable-nodpi, unscaled).
 */
internal class BrainRenderer(
    private val coreSprite: ImageBitmap,
    private val lobeSprite: ImageBitmap,
    private val skullSprite: ImageBitmap,
    private val skullDeadSprite: ImageBitmap,
) {
    val pix = Pix()
    val W get() = pix.w
    val H get() = pix.h

    /** Animated idea positions (world) for the current frame, read by the label pass. */
    var ipx = FloatArray(0)
    var ipy = FloatArray(0)

    private val cv get() = pix.canvas
    private val spritePaint = pixPaint()
    private val ditherPaint = makeDitherPaint(0.26f * ROT)
    private val vignette = pixPaint()

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
        if (!pix.resize(w, h) && vignette.shader != null) return
        val c0 = Color(0x00040108)
        val c1 = Color((((0.55f + 0.35f * ROT) * 255f).toInt() shl 24) or 0x040108)
        vignette.shader = RadialGradientShader(
            Offset(w / 2f, h / 2f), h * 0.62f, listOf(c0, c0, c1), listOf(0f, 0.22f / 0.62f, 1f), TileMode.Clamp,
        )
    }

    /** Scales the low-res frame up to [pxSize] device px per pixel with no filtering. */
    fun blit(target: androidx.compose.ui.graphics.Canvas, pxSize: Int) = pix.blit(target, 0, 0, pxSize)

    // ---- helpers ----
    private fun sx(wx: Float) = (wx - camX) * z + W * 0.5f
    private fun sy(wy: Float) = (wy - camY) * z + H * 0.5f

    private fun beat(s: Float): Float { val d = (s - beatPh) * 5f; return exp(-d * d) }

    private fun dot(x: Int, y: Int, w: Int, h: Int, rgb: Int, a: Float) = pix.dot(x, y, w, h, rgb, a)

    /** Pixel "circle": two overlapping rects (plus shape). [x], [y], [r] are already low-res. */
    private fun disc(x: Float, y: Float, r: Float, rgb: Int, a: Float) = pix.disc(x, y, r, rgb, a)

    private fun glowAt(wx: Float, wy: Float, r: Float, rgb: Int, a: Float, over: Boolean = false) =
        pix.glow(sx(wx), sy(wy), r * z, rgb, a, add = !over)

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

    private fun sprite(bm: ImageBitmap, wx: Float, wy: Float, scale: Float, alpha: Float, flip: Boolean) {
        val w = bm.width * scale * z
        val h = bm.height * scale * z
        val cx = sx(wx); val cy = sy(wy)
        if (cx + w < 0 || cy + h < 0 || cx - w > W || cy - h > H) return
        val l = rnd(cx - w / 2f); val t = rnd(cy - h / 2f)
        val dstSize = IntSize(rnd(cx + w / 2f) - l, rnd(cy + h / 2f) - t)
        spritePaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255) / 255f
        if (flip) { cv.save(); cv.translate(cx, cy); cv.scale(-1f, 1f); cv.translate(-cx, -cy) }
        cv.drawImageRect(bm, IntOffset.Zero, IntSize(bm.width, bm.height), IntOffset(l, t), dstSize, spritePaint)
        if (flip) cv.restore()
    }

    private fun vein(x2: Float, y2: Float, bend: Float, strands: Int, seed: Float, alpha: Float, hiRgb: Int = hi) {
        for (st in 0 until strands) {
            ctrl(0f, 0f, x2, y2, bend + (st - (strands - 1) / 2f) * (1f + 2.5f * ROT))
            val cx = ccx; val cy = ccy; val nx = cnx; val ny = cny
            val n = ceil(clen * z * 1.2f).toInt().coerceIn(1, 3000)
            for (i in 0..n) {
                val s = i / n.toFloat()
                bz(0f, 0f, cx, cy, x2, y2, s)
                val wob = sin(s * 11f + st * 2.1f + seed + t * 0.6f) * 1.6f * ROT * (1f - abs(s - 0.5f))
                val dead = sin(s * 37f + seed * 3f + st) > 0.93f
                val rgb = if (dead) 0x14061A else mixRgb(lo, hiRgb, beat(s))
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

    /** Skull at the vault node; [sv] 0 = alive, 1 = dead. Eyes flare when the pulse arrives. */
    private fun sun(sv: Float, selected: Boolean, d: Float) {
        val l = 1f - sv
        val ga = (0.35f + 0.3f * beat(1f)) * l * d
        glowAt(SUN_X, SUN_Y, 34f, 0xD45AAB, ga)
        if (selected) glowAt(SUN_X, SUN_Y, 34f, 0xD45AAB, ga)
    }

    /** Skull + eye flare, drawn after the dither pass so the checker doesn't show on the bone. */
    private fun skull(sv: Float, d: Float) {
        val l = 1f - sv
        sprite(skullDeadSprite, SUN_X, SUN_Y, SKULL_SCALE, d, false)
        sprite(skullSprite, SUN_X, SUN_Y, SKULL_SCALE, l * d, false)
        // eye flare: beatPh crosses 1 as the pulse lands, fades over ~0.6 s (0.27 of beatPh)
        val q = (beatPh - 1f) / 0.27f
        if (q < 0f || q >= 1f) return
        val f = (1f - q) * (1f - q) * l * d
        for (e in SKULL_EYES) {
            glowAt(SUN_X + e[0] * SKULL_SCALE, SUN_Y + e[1] * SKULL_SCALE, 7f, 0xF596E1, 0.9f * f)
            glowAt(SUN_X + e[0] * SKULL_SCALE, SUN_Y + e[1] * SKULL_SCALE, 12f, 0xD45AAB, 0.5f * f)
        }
    }

    /** One frame, in the mockup's draw order. [sel] is the selected lobe index or -1. */
    fun draw(time: Float, camX: Float, camY: Float, zoom: Float, L: Layout, sel: Int, sv: Float, sunSel: Boolean) {
        t = time; this.camX = camX; this.camY = camY; z = zoom
        beatPh = (t * 0.45f) % 1.7f
        if (ipx.size < L.nI) { ipx = FloatArray(L.nI); ipy = FloatArray(L.nI) }
        fun dimOf(i: Int) = if ((sel < 0 && !sunSel) || sel == i) 1f else 0.45f
        val sunDim = if (sel < 0) 1f else 0.45f

        pix.fill(0x07030B)

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
        vein(SUN_X, SUN_Y, SUN_BEND, 2, 9f, sunDim, 0xF5B0E8)
        // heartbeat pulse travelling out each vein
        val ps = (t * 0.45f) % 1.7f
        if (ps <= 1f) {
            ctrl(0f, 0f, SUN_X, SUN_Y, SUN_BEND)
            bz(0f, 0f, ccx, ccy, SUN_X, SUN_Y, ps)
            glowAt(bzx, bzy, 6f, 0xF0C8F0, 0.9f * sunDim)
            dot(rnd(sx(bzx)), rnd(sy(bzy)), if (z > 2f) 2 else 1, if (z > 2f) 2 else 1, 0xFFFFFF, sunDim)
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

        sun(sv, sunSel, sunDim)

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
        skull(sv, sunDim)
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
    vault: Vault,
    sync: SyncManager,
    appScope: CoroutineScope,
) {
    val lobeList = lobes.orEmpty()
    val ideaList = ideas.orEmpty()
    val placed = remember(lobeList, ideaList) { placeIdeas(lobeList, ideaList) }
    val selected = lobeList.firstOrNull { it.id == selectedLobe }

    var addingLobe by remember { mutableStateOf(false) }
    BackHandler(enabled = selected != null) { onSelectLobe(-1L) }
    var deleting by remember { mutableStateOf<Lobe?>(null) }
    var deletingIdea by remember { mutableStateOf<Idea?>(null) }
    var managing by remember { mutableStateOf(false) }
    // null = no dialog; an Idea with id 0 is a brand-new idea in its lobe.
    var editing by remember { mutableStateOf<Idea?>(null) }

    // Vault sun: backup / restore. Work runs on appScope so leaving the tab can't cancel a write.
    val last by vault.lastBackupAt.collectAsState()
    val sv = staleness(last)
    var sunSel by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<String?>(null) }
    var waking by remember { mutableStateOf(false) }
    val keepPicker = vault.rememberSavePicker { uri ->
        if (working != null) return@rememberSavePicker
        working = "keeping..."; message = null
        appScope.launch {
            try { vault.write(uri); message = "kept." } catch (e: BackupException) { message = e.message }
            working = null
            if (message == "kept.") { delay(2500); if (message == "kept.") message = null }
        }
    }
    val restorePicker = vault.rememberOpenPicker { uri -> restoreUri = uri }
    val syncUi by sync.ui.collectAsState()
    val signIn = sync.rememberSignIn()
    var askingPassphrase by remember { mutableStateOf(false) }
    fun restore(uri: String) {
        if (working != null) return
        working = "reading..."; message = null
        appScope.launch {
            try { vault.restore(uri); waking = true } catch (e: BackupException) { if (e.restartNeeded) waking = true else message = e.message }
            working = null
            if (waking) { delay(600); vault.restart() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BrainCanvas(
                lobeList, placed, selected?.id ?: -1L, camera, sv, sunSel,
                onTapSun = { onSelectLobe(-1L); sunSel = true },
                onTapLobe = { id -> sunSel = false; onSelectLobe(id) },
                onDoubleTapLobe = { id -> editing = Idea(lobeId = id, title = "", body = "") },
                onTapBrain = { managing = true },
                onTapIdea = { editing = it },
                onDoubleTapIdea = { deletingIdea = it },
                onTapEmpty = { sunSel = false; onSelectLobe(-1L) },
            )
            ScreenTitle("Brain")
        }
        if (sunSel && selected == null) {
            VaultSheet(
                status = working ?: message ?: when {
                    last == null -> "nothing kept yet. keep a copy."
                    sv == 0f -> "your memories are kept safe."
                    else -> "it has been a while. keep a new copy."
                },
                busy = working != null,
                onKeep = { keepPicker("vesper-backup-${java.time.LocalDate.now()}.zip") },
                onRestore = { restorePicker() },
                sync = syncUi,
                onSignIn = signIn,
                onPassphrase = { askingPassphrase = true; sync.checkVault() },
                onSyncNow = sync::syncNow,
            )
        }
    }

    val vaultExists = syncUi.vaultExists
    if (askingPassphrase && vaultExists != null && syncUi.working == null) {
        PassphraseDialog(create = !vaultExists, onDismiss = { askingPassphrase = false }, onSubmit = { askingPassphrase = false; sync.unlock(it) })
    }

    if (addingLobe) {
        NameDialog("NEW LOBE", "Name it: Coding, Business...", onDismiss = { addingLobe = false }, onSubmit = onAddLobe)
    }
    deleting?.let { target ->
        PixelDialog({ deleting = null }) {
            Text("DELETE ${target.name.uppercase()}?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text(
                "Its ideas go with it, forever. No undo.",
                style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { deleting = null })
                PixelButton("DELETE", {
                    deleting = null
                    onSelectLobe(-1L)
                    onDeleteLobe(target.id)
                }, primary = true)
            }
        }
    }
    if (managing) {
        PixelDialog({ managing = false }) {
            Text("SUB-BRAINS", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
            Spacer(Modifier.height(12.dp))
            if (lobeList.isEmpty()) {
                Text("No sub-brains yet.", style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.TextDim))
            } else {
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lobeList, key = { it.id }) { l ->
                        Row(
                            Modifier.fillMaxWidth().pixelCard(fill = Px.Bg, shadow = false, notch = 2.dp).padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                l.name.uppercase(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, color = Px.Text),
                            )
                            PixelButton("DELETE", { deleting = l })
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("CLOSE", { managing = false })
                PixelButton("+ ADD", { addingLobe = true }, primary = true)
            }
        }
    }
    deletingIdea?.let { di ->
        PixelDialog({ deletingIdea = null }) {
            Text("DELETE THIS THOUGHT?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text(
                ideaLabel(di),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { deletingIdea = null })
                PixelButton("DELETE", { deletingIdea = null; onDeleteIdea(di.id) }, primary = true)
            }
        }
    }
    restoreUri?.let { uri ->
        PixelDialog({ restoreUri = null }) {
            Text("RESTORE THIS COPY?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text(
                "everything in vesper now is replaced by this copy. a safety copy of today is kept.",
                style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { restoreUri = null })
                PixelButton("RESTORE", { restoreUri = null; restore(uri) }, primary = true)
            }
        }
    }
    if (waking) {
        // A dialog window covers the whole screen (tab bar included) and swallows touches and back.
        Dialog({}, DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF07030B)), contentAlignment = Alignment.Center) {
                Text("waking up...", style = TextStyle(fontFamily = PixelFont, fontSize = 16.sp, color = Px.Lavender))
            }
        }
    }
    editing?.let { idea ->
        IdeaDialog(
            idea = idea,
            onDismiss = { editing = null },
            onSave = { onSaveIdea(it); editing = null },
            onDelete = { onDeleteIdea(idea.id); editing = null },
        )
    }
}


@Composable
private fun BrainCanvas(
    lobes: List<Lobe>,
    placed: List<Placed>,
    selectedId: Long,
    cam: BrainCamera,
    sunSv: Float,
    sunSel: Boolean,
    onTapSun: () -> Unit,
    onTapLobe: (Long) -> Unit,
    onDoubleTapLobe: (Long) -> Unit,
    onTapBrain: () -> Unit,
    onTapIdea: (Idea) -> Unit,
    onDoubleTapIdea: (Idea) -> Unit,
    onTapEmpty: () -> Unit,
) {
    val density = LocalDensity.current.density
    val pxSize = remember(density) { round(2.28f * density).coerceAtLeast(1f) }
    val core = imageResource(Res.drawable.brain_main)
    val lobeImg = imageResource(Res.drawable.brain_lobe)
    val skull = imageResource(Res.drawable.vault_skull)
    val skullDead = imageResource(Res.drawable.vault_skull_dead)
    val renderer = remember { BrainRenderer(core, lobeImg, skull, skullDead) }
    val layout = remember(lobes, placed) { buildLayout(lobes, placed) }
    val selIdx = layout.lobeIds.indexOf(selectedId)
    val measurer = rememberTextMeasurer(cacheSize = 200)
    val pageFont = PageFont; val titleFont = TitleFont; val pixelFont = PixelFont // composable getters, read outside remember
    val ringStyle = remember(density) {
        TextStyle(fontFamily = pageFont, fontSize = 19.sp, color = Color(0xFFE6DCFF), shadow = Shadow(Color(0xFFB69AFF).copy(alpha = 0.85f), blurRadius = 14f * density))
    }
    // Selected lobe: its label layouts and ring positions, laid out for the fixed target zoom so pinching never reshuffles them.
    val selRing = remember(layout, selIdx, pxSize, density, ringStyle) {
        if (selIdx < 0) null else {
            val first = layout.istart[selIdx]
            val tls = (first until layout.istart[selIdx + 1]).map {
                measurer.measure(
                    ideaLabel(placed[it].idea), ringStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
                    constraints = Constraints(maxWidth = (170f * density).toInt().coerceAtLeast(50)),
                )
            }
            SelRing(tls, ringPositions(Offset(layout.lx[selIdx], layout.ly[selIdx]), tls, pxSize * ORBIT_ZOOM, density))
        }
    }
    val ringT = remember { Animatable(if (selectedId >= 0) 1f else 0f) }
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(selectedId) {
        val initial = firstRun
        firstRun = false
        val lobe = lobes.firstOrNull { it.id == selectedId }
        if (lobe == null) { ringT.snapTo(0f); return@LaunchedEffect }
        if (initial) { ringT.snapTo(1f); return@LaunchedEffect }
        ringT.snapTo(0f)
        val c = lobeWorldPos(lobe.slot)
        val x0 = cam.x; val y0 = cam.y; val s0 = cam.scale
        val s1 = ORBIT_ZOOM.coerceIn(0.6f, 4f)
        coroutineScope {
            launch { ringT.animateTo(1f, tween(300)) }
            launch {
                Animatable(0f).animateTo(1f, tween(400, easing = FastOutSlowInEasing)) {
                    cam.x = x0 + (c.x - x0) * value; cam.y = y0 + (c.y - y0) * value; cam.scale = s0 + (s1 - s0) * value
                }
            }
        }
    }
    val labelStyle = { c: Color, glow: Color ->
        TextStyle(fontFamily = titleFont, fontSize = 20.sp, color = c, shadow = Shadow(glow.copy(alpha = 0.7f), blurRadius = 12f * density))
    }
    val lobeLabels = remember(lobes, layout, density) {
        List(layout.nL) { i ->
            val name = lobes[i].name
            if (name.isBlank()) null else {
                val lc = Color(layout.lcol[i] or 0xFF000000.toInt())
                GlowText(measurer.measure(
                    name, labelStyle(lc, lc), overflow = TextOverflow.Ellipsis, maxLines = 1,
                    constraints = Constraints(maxWidth = (150f * density).toInt().coerceAtLeast(60)),
                ))
            }
        }
    }
    val vaultLabel = remember(sunSv, density) {
        val c = lerp(Color(0xFFF596E1), Color(0xFF6F6080), sunSv)
        GlowText(measurer.measure("vault", labelStyle(c, if (sunSv > 0.5f) Color.Transparent else c), maxLines = 1))
    }
    val tapScope = rememberCoroutineScope()
    val ideaStyle = remember { TextStyle(fontFamily = pixelFont, fontSize = 7.sp, color = Color(0xFFE6DBF5)) }
    val newestStyle = remember { TextStyle(fontFamily = pixelFont, fontSize = 7.sp, color = Color(0xFFFFC8EF)) }
    val hintStyle = remember { TextStyle(fontFamily = pixelFont, fontSize = 9.sp, color = Px.TextDim) }

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
            .pointerInput(lobes, placed, layout, selRing, onTapSun) {
                // Single taps act at once (waiting for a possible double tap felt laggy). Returns what a
                // second tap may turn into a double tap: a ring Idea (open is delayed) or a Lobe.
                var pendingOpen: Job? = null
                fun handle(tap: Offset): Any? {
                    val u = pxSize * cam.scale
                    val ox = ceil(size.width / pxSize) * pxSize / 2f
                    val oy = ceil(size.height / pxSize) * pxSize / 2f
                    fun screen(w: Offset) = Offset((w.x - cam.x) * u + ox, (w.y - cam.y) * u + oy)
                    // Drawn (wobbling) dot position when the renderer has one, else the layout position.
                    fun ipos(k: Int) = if (renderer.ipx.size > k) Offset(renderer.ipx[k], renderer.ipy[k]) else Offset(layout.ix[k], layout.iy[k])
                    if (selRing != null) {
                        // Selected lobe: the whole label (plus a margin) is the hit area.
                        val first = layout.istart[selIdx]
                        val m = 10.dp.toPx(); val minHalf = 22.dp.toPx()
                        var best = -1; var bd = Float.MAX_VALUE
                        for (j in selRing.tls.indices) {
                            val c = screen(ipos(first + j))
                            val tl = selRing.tls[j]
                            val hw = max(tl.size.width / 2f + m, minHalf); val hh = max(tl.size.height / 2f + m, minHalf)
                            val d = (c - tap).getDistance()
                            if (abs(tap.x - c.x) <= hw && abs(tap.y - c.y) <= hh && d < bd) { bd = d; best = j }
                        }
                        if (best >= 0) {
                            // opening covers the canvas, so wait out the double-tap window before opening
                            val idea = placed[first + best].idea
                            pendingOpen = tapScope.launch { delay(viewConfiguration.doubleTapTimeoutMillis); onTapIdea(idea) }
                            return idea
                        }
                    }
                    val ik = (0 until layout.nI).minByOrNull { (screen(ipos(it)) - tap).getDistance() }
                    if (ik != null && (screen(ipos(ik)) - tap).getDistance() <= maxOf(26.dp.toPx(), 6f * u)) {
                        onTapIdea(placed[ik].idea)
                        return null
                    }
                    if ((screen(Offset(SUN_X, SUN_Y)) - tap).getDistance() <= maxOf(36.dp.toPx(), 14f * u)) {
                        onTapSun()
                        return null
                    }
                    val lobe = lobes.minByOrNull { (screen(lobeWorldPos(it.slot)) - tap).getDistance() }
                    if (lobe != null && (screen(lobeWorldPos(lobe.slot)) - tap).getDistance() <= maxOf(36.dp.toPx(), 14f * u)) {
                        onTapLobe(lobe.id)
                        return lobe
                    }
                    // The main brain is the core at the world origin (inside the r35 ring).
                    if (tap.minus(screen(Offset.Zero)).getDistance() <= maxOf(36.dp.toPx(), 30f * u)) {
                        onTapBrain()
                        return null
                    }
                    onTapEmpty()
                    return null
                }
                var lastAt = 0L; var lastPos = Offset.Zero; var lastHit: Any? = null
                detectTapGestures(onTap = { tap ->
                    val now = System.nanoTime() / 1_000_000 // monotonic ms, like uptimeMillis
                    val hit = lastHit
                    if (hit != null && now - lastAt <= viewConfiguration.doubleTapTimeoutMillis && (tap - lastPos).getDistance() <= 48.dp.toPx()) {
                        // Second tap on the same target (the camera may be gliding toward a just-selected lobe).
                        lastHit = null
                        when (hit) {
                            is Idea -> { pendingOpen?.cancel(); onDoubleTapIdea(hit) }
                            is Lobe -> onDoubleTapLobe(hit.id)
                        }
                    } else {
                        lastAt = now; lastPos = tap; lastHit = handle(tap)
                    }
                })
            },
    ) {
        val vw = size.width.toInt()
        val vh = size.height.toInt()
        if (vw <= 0 || vh <= 0) return@Canvas
        val px = pxSize.toInt()
        renderer.resize(ceil(vw / pxSize).toInt(), ceil(vh / pxSize).toInt())
        // Scattered base positions everywhere; the selected lobe's thoughts glide onto their ring.
        for (k in 0 until layout.nI) { layout.ix[k] = placed[k].pos.x; layout.iy[k] = placed[k].pos.y }
        if (selRing != null) {
            val t = ringT.value
            val first = layout.istart[selIdx]
            for (j in selRing.target.indices) {
                val b = placed[first + j].pos; val g = selRing.target[j]
                layout.ix[first + j] = b.x + (g.x - b.x) * t
                layout.iy[first + j] = b.y + (g.y - b.y) * t
            }
        }
        renderer.draw(time, cam.x, cam.y, cam.scale, layout, selIdx, sunSv, sunSel)
        drawIntoCanvas { renderer.blit(it, px) }

        // Labels at full resolution, same world -> device transform as the bitmap.
        val z = cam.scale
        val u = pxSize * z
        val ox = renderer.W * pxSize / 2f
        val oy = renderer.H * pxSize / 2f
        fun dx(wx: Float) = (wx - cam.x) * u + ox
        fun dy(wy: Float) = (wy - cam.y) * u + oy

        for (i in 0 until layout.nL) {
            val dim = if ((selIdx < 0 && !sunSel) || selIdx == i) 1f else 0.45f
            val g = lobeLabels[i] ?: continue
            val tl = g.tl
            val left = dx(layout.lx[i]) - tl.size.width / 2f
            val top = dy(layout.ly[i] + 12f)
            if (left > size.width || left + tl.size.width < 0f || top > size.height || top + tl.size.height < 0f) continue
            g.draw(this, left, top, dim)
        }

        run {
            val dim = if (selIdx < 0) 1f else 0.45f
            vaultLabel.draw(this, dx(SUN_X) - vaultLabel.tl.size.width / 2f, dy(SUN_Y + 24f), dim)
        }

        val titleAlpha = ((z - 1.6f) / 0.5f).coerceIn(0f, 1f)
        if (titleAlpha > 0f && renderer.ipx.size >= layout.nI) {
            for (i in 0 until layout.nL) {
                if (i == selIdx) continue
                val dim = if ((selIdx < 0 && !sunSel) || selIdx == i) 1f else 0.45f
                for (k in layout.istart[i] until layout.istart[i + 1]) {
                    val p = placed.getOrNull(k) ?: continue
                    // cull before measuring: titles are at most 120dp wide and well under 40dp tall
                    val lx0 = dx(renderer.ipx[k] + 4f); val ly0 = dy(renderer.ipy[k] - 2f)
                    if (lx0 > size.width || lx0 + 120f * density < 0f || ly0 - 40f * density > size.height || ly0 + 40f * density < 0f) continue
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

        // Selected lobe: every thought is labelled, always, with a staggered gentle pulse.
        if (selRing != null && renderer.ipx.size >= layout.nI) {
            val first = layout.istart[selIdx]
            selRing.tls.forEachIndexed { j, tl ->
                val k = first + j
                if (tl.size.width == 0) return@forEachIndexed
                val left = dx(renderer.ipx[k]) - tl.size.width / 2f
                val top = dy(renderer.ipy[k]) - tl.size.height / 2f
                if (left > size.width || left + tl.size.width < 0f || top > size.height || top + tl.size.height < 0f) return@forEachIndexed
                val pulse = 0.8f + 0.2f * sin(time * 1.6f + j * 1.3f)
                selRing.glows[j].draw(this, left, top, pulse)
            }
        }

        if (layout.nL == 0) {
            val hint = measurer.measure("TAP THE BRAIN TO GROW A LOBE", hintStyle)
            drawText(hint, topLeft = Offset(dx(0f) - hint.size.width / 2f, dy(44f)))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Resume sheet
// ---------------------------------------------------------------------------------------------

@Composable
private fun VaultSheet(
    status: String,
    busy: Boolean,
    onKeep: () -> Unit,
    onRestore: () -> Unit,
    sync: SyncUi,
    onSignIn: () -> Unit,
    onPassphrase: () -> Unit,
    onSyncNow: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .pixelCard(fill = Px.Surface, border = Color(0xFFF596E1), shadow = false, notch = 4.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("VAULT", Modifier.padding(vertical = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
        Text(status, Modifier.padding(top = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PixelButton("KEEP A COPY", { if (!busy) onKeep() }, primary = true, modifier = Modifier.weight(1f))
            PixelButton("RESTORE", { if (!busy) onRestore() }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text("SYNC", Modifier.padding(vertical = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
        val line = sync.working ?: when {
            !sync.available -> "sync is not set up in this build."
            !sync.signedIn -> "keep your devices in step through google drive."
            !sync.unlocked -> "signed in. the passphrase comes next."
            sync.lastSyncAt == 0L -> "ready. not synced yet."
            else -> "last sync ${java.time.Instant.ofEpochMilli(sync.lastSyncAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))}"
        }
        Text(line, Modifier.padding(top = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim))
        sync.error?.let { Text(it, Modifier.padding(top = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Color(0xFFF596E1))) }
        if (sync.available) {
            Spacer(Modifier.height(12.dp))
            val idle = sync.working == null
            when {
                !sync.signedIn -> PixelButton("SIGN IN", { if (idle) onSignIn() }, primary = true, modifier = Modifier.fillMaxWidth())
                !sync.unlocked -> PixelButton("PASSPHRASE", { if (idle) onPassphrase() }, primary = true, modifier = Modifier.fillMaxWidth())
                else -> PixelButton("SYNC NOW", { if (idle) onSyncNow() }, primary = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** [create]: no vault on Drive yet, so this sets the passphrase and asks twice. It is held here only until submitted. */
@Composable
private fun PassphraseDialog(create: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val ok = if (create) pass.length >= MIN_PASSPHRASE && pass == again else pass.isNotEmpty()
    fun submit() { if (ok) onSubmit(pass) }
    PixelDialog(onDismiss) {
        Text(if (create) "SET PASSPHRASE" else "ENTER PASSPHRASE", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(10.dp))
        Text(
            "If you forget it, the Drive copy can't be read. Your data on this device stays safe.",
            style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim),
        )
        Spacer(Modifier.height(14.dp))
        PixelField(pass, { pass = it }, if (create) "$MIN_PASSPHRASE+ characters, 4+ words" else "Passphrase", password = true, onDone = ::submit)
        if (create) {
            Spacer(Modifier.height(10.dp))
            PixelField(again, { again = it }, "Same again", password = true, onDone = ::submit)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            PixelButton("CANCEL", onDismiss)
            PixelButton("OK", ::submit, primary = true)
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
    password: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(fontFamily = PixelFont, fontSize = 11.sp, lineHeight = 18.sp, color = Px.Text),
        cursorBrush = SolidColor(Px.Magenta),
        visualTransformation = if (password) PasswordVisualTransformation('*') else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = if (password) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            autoCorrectEnabled = if (password) false else null,
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Unspecified,
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
    onDismiss: () -> Unit,
    onSave: (Idea) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember { mutableStateOf(idea.title) }

    PixelDialog(onDismiss) {
        Text(if (idea.id == 0L) "NEW IDEA" else "IDEA", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(14.dp))
        PixelField(title, { title = it }, "Title")
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (idea.id != 0L) PixelButton("DELETE", onDelete)
            PixelButton("CANCEL", onDismiss)
            // copy keeps body and noteId as they were
            PixelButton("SAVE", { onSave(idea.copy(title = title)) }, primary = true)
        }
    }
}
