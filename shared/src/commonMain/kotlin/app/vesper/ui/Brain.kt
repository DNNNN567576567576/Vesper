package app.vesper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import app.vesper.res.brain_main
import app.vesper.res.vault_heart
import app.vesper.sync.MIN_PASSPHRASE
import app.vesper.sync.SyncManager
import app.vesper.sync.SyncUi
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.vesper.res.vault_heart_dead
import org.jetbrains.compose.resources.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
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
import app.vesper.data.Branch
import app.vesper.data.Goal
import app.vesper.data.Idea
import app.vesper.data.Lobe
import app.vesper.data.Step
import app.vesper.LearnViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
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
import kotlin.math.pow
import androidx.compose.animation.core.CubicBezierEasing

// ---------------------------------------------------------------------------------------------
// Palette + layout
// ---------------------------------------------------------------------------------------------

/** One purple shade per sub brain, by colorIndex. Purple only: no pink in the Brain tab. */
internal val LobeColors = listOf(
    Color(0xFFB69AFF),
    Color(0xFF9C84FF),
    Color(0xFFC9B6FF),
    Color(0xFF8A6CF0),
    Color(0xFFA88BFF),
    Color(0xFF7F66E6),
)

internal fun lobeColor(l: Lobe) = LobeColors[l.colorIndex.mod(LobeColors.size)]

/** How rotten the map is (0..1). Locked by the approved mockup. */
private const val ROT = 0.64f
internal const val TAU = 6.2831855f
private const val PI_F = 3.1415927f

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

/** The portal ring's half height in world units (low-res pixels at zoom 1); the core sits at (0, 0). */
internal const val RING_RY = 100f

/** The ring's half width for a [w] x [h] dp view (title row included): wider views get a wider ellipse. */
internal fun ringRx(w: Float, h: Float): Float = if (w <= 0f || h <= 0f) 66f else (RING_RY * w / max(1f, h - 76f) * 1.15f).coerceIn(58f, 190f)

/** Portal [rank] of [n], evenly round one ellipse ([rx] by [RING_RY]): the first at the top, or the top and bottom left open when [n] is even. */
internal fun ringPos(rank: Int, n: Int, rx: Float): Offset {
    val a = -TAU / 4f + (rank + if (n % 2 == 0) 0.5f else 0f) * TAU / max(1, n)
    return Offset(cos(a) * rx, sin(a) * RING_RY)
}

/** Where [lobe] sits on the ring: sub brains go round it in slot order. */
internal fun lobeRingPos(lobes: List<Lobe>, lobe: Lobe, rx: Float): Offset {
    val order = lobes.sortedWith(compareBy({ it.slot }, { it.uid }))
    return ringPos(order.indexOfFirst { it.id == lobe.id }.coerceAtLeast(0), lobes.size, rx)
}

/** A portal's size: every portal is the same ([ns]); only the one being flown through ([fly], [flyP]) grows, squished by a tap ([tapP]). */
internal fun portalScale(i: Int, ns: Float, fly: Int, flyP: Float, tapP: Float): Float {
    if (i != fly) return ns
    val squish = when {
        tapP <= 0f || tapP >= 1f -> 1f
        tapP < 0.4f -> 1f - 0.18f * tapP / 0.4f
        tapP < 0.75f -> 0.82f + 0.26f * (tapP - 0.4f) / 0.35f
        else -> 1.08f - 0.08f * (tapP - 0.75f) / 0.25f
    }
    return ns.toDouble().pow(1.0 - flyP).toFloat() * squish
}

/**
 * A group in a world: [pos] is where its name hangs, [name] that name's box (l, t, r, b, dp from [pos]); [size] is how many notes it has.
 * An empty group gets a ghost star at [ghost] with "empty" in the box [ghostName] (dp from [ghost]).
 */
internal class PlacedBranch(val branch: Branch, val lobe: Lobe, val pos: Offset, val size: Int, val name: FloatArray, val ghost: Offset? = null, val ghostName: FloatArray? = null)

/** A note's star in a world; [parent] indexes [Web.branches], -1 = loose. [j] is its place in its constellation (0 starts one). */
internal class Placed(val idea: Idea, val lobe: Lobe, val pos: Offset, val ph: Float, val j: Int, val newest: Boolean, val parent: Int, val name: FloatArray)

/** What is placed below the portals: only the [focus]ed sub brain's world ([dim]); the main map places nothing. */
internal class Web(val branches: List<PlacedBranch>, val items: List<Placed>, val focus: Long = -1L, val dim: Dimension? = null)

/** A portal's ring radius and how far its outer dotted ring reaches, in world units at zoom 1. */
internal const val PORTAL_R = 11f
internal const val PORTAL_OUT = 16f
/** How deep (dp) the portal arc hangs at the top of a world. */
internal const val ARC_D = 56f

/**
 * The [focus]ed sub brain's world, from uids only (local row ids differ per device, these don't), laid out for the view [frame]
 * by [dimensionLayout]. Any other [focus] (the main map) places nothing: portals show their contents as stars instead.
 */
internal fun placeIdeas(lobes: List<Lobe>, branches: List<Branch>, ideas: List<Idea>, focus: Long = -1L, frame: DimFrame = DimFrame()): Web {
    val lobe = lobes.firstOrNull { it.id == focus } ?: return Web(emptyList(), emptyList(), focus)
    val groups = branches.filter { it.lobeId == lobe.id }.sortedBy { it.uid }
    val known = groups.mapTo(HashSet()) { it.id }
    val mine = ideas.filter { it.lobeId == lobe.id }.sortedBy { it.uid }
    val byGroup = mine.groupBy { i -> i.branchId?.takeIf { it in known } }
    val loose = byGroup[null].orEmpty()
    val newest = mine.maxWithOrNull(compareBy({ it.updatedAt }, { it.uid }))?.uid
    val d = dimensionLayout(loose.size, groups.map { byGroup[it.id].orEmpty().size }, frame)
    val outI = ArrayList<Placed>()
    fun item(idea: Idea, at: Offset, j: Int, parent: Int, name: FloatArray) =
        outI.add(Placed(idea, lobe, at, Rng(idea.uid.hashCode() * 7919 + 13).next() * TAU, j, idea.uid == newest, parent, name))
    var n = 0
    d.loose.forEachIndexed { c, part -> part.forEachIndexed { j, at -> item(loose[n++], at, j, -1, d.looseNames[c][j]) } }
    groups.forEachIndexed { g, b -> byGroup[b.id].orEmpty().forEachIndexed { j, i -> item(i, d.items[g][j], j, g, d.itemNames[g][j]) } }
    val outB = groups.mapIndexed { g, b -> PlacedBranch(b, lobe, d.groups[g], byGroup[b.id].orEmpty().size, d.groupNames[g], d.ghosts[g]?.first, d.ghosts[g]?.second) }
    return Web(outB, outI, focus, d)
}

/** Nodes stop growing at this zoom, so zooming further opens the web up instead of blowing the sprites up. */
internal const val NODE_ZOOM = 2f
internal fun nodeScale(z: Float) = min(1f, NODE_ZOOM / z)

private const val HOME_ZOOM = 0.85f
private const val MIN_ZOOM = 0.6f
private const val MAX_ZOOM = 12f
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

/** The vault sun: one fixed node (not a lobe), centered below the brain (x = 0), off and below the portal ring. */
internal const val SUN_X = 0f
internal const val SUN_Y = 158f
internal const val SUN_BEND = 8f
private const val HEART_SCALE = 0.85f // sprite is 26x38, so about 22x32 world units (lobe: 24x19.5); its base sits on SUN_Y
internal const val HEART_MID = 14f // world units from the heart's base up to its middle, for glow, keep-out and tap

/** 0 = fresh, 1 = fully eclipsed: grace of 7 days, then 14 days to go dark. Never backed up = 1. */
private fun staleness(last: Long?): Float =
    if (last == null) 1f else (((System.currentTimeMillis() - last) / 86_400_000f - 7f) / 14f).coerceIn(0f, 1f)

internal fun ideaLabel(i: Idea) = i.title.ifBlank { i.body.lineSequence().firstOrNull().orEmpty() }

/** Pan/zoom state, kept above the tab switcher so the map is where you left it. [x]/[y] is the world point at screen centre. */
@Stable
class BrainCamera {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var scale by mutableFloatStateOf(HOME_ZOOM)

    /** The last camera [fitUntouched] set; the camera counts as untouched while it is still the default or exactly that. */
    private var fitted: FloatArray? = null
    /** Where the camera was on the main map before flying into a portal: where it flies back out to. */
    internal var outside: FloatArray? = null
    /** The last overview fit, if any. */
    internal val overview: FloatArray? get() = fitted
    /** The zoom of the last overview fit: the map's home, so "zoomed in" means past it. */
    var home by mutableFloatStateOf(HOME_ZOOM)
        private set

    /**
     * Puts the camera on the overview [aim] if the size is known ([viewW] > 0) and nobody has panned or zoomed since the default
     * or the last fit, or always when [force]d (the window changed width). Called again whenever the size or the lobes change,
     * so the order they arrive in does not matter.
     */
    internal fun fitUntouched(viewW: Int, force: Boolean = false, aim: () -> FloatArray) {
        val f = fitted
        val untouched = if (f == null) x == 0f && y == 0f && scale == HOME_ZOOM else x == f[0] && y == f[1] && scale == f[2]
        if (viewW <= 0 || !(untouched || force)) return
        val a = aim()
        x = a[0]; y = a[1]; scale = a[2]; fitted = a; home = a[2]
    }
}

/** Everything the renderer needs, derived from the real lobes/ideas plus the seeded rot decoration. */
internal class Layout(
    val nL: Int, val lobeIds: LongArray, val lx: FloatArray, val ly: FloatArray, val lbend: FloatArray,
    val lcol: IntArray, val lslot: IntArray, val istart: IntArray,
    val nI: Int, val ix: FloatArray, val iy: FloatArray, val iph: FloatArray,
    val ij: IntArray, val inew: BooleanArray, val ipar: IntArray, val irings: IntArray, val irecent: BooleanArray,
    val bstart: IntArray, val bx: FloatArray, val by: FloatArray,
    val idone: BooleanArray, val gx: FloatArray, val gy: FloatArray, // done notes; each group's ghost star (NaN = it has notes)
    val dust: FloatArray,      // x, y, ph, b
    val stains: FloatArray,    // x, y, r
    val cracks: Array<FloatArray>, // x0, y0, x1, y1, ...
    val specks: FloatArray,    // x, y, colour index
    val spores: FloatArray,    // x, y, ph, sp
    val drips: FloatArray,     // x, y, n, len, owner lobe (-1 none)
)

/** [now] decides which items are recent (touched within 48 hours). */
internal fun buildLayout(lobes: List<Lobe>, web: Web, now: Long = System.currentTimeMillis(), rx: Float = 66f): Layout {
    val placedIn = web.items
    val nL = lobes.size
    val idxOf = HashMap<Long, Int>()
    lobes.forEachIndexed { i, l -> idxOf[l.id] = i }
    val placed = placedIn.filter { idxOf.containsKey(it.lobe.id) }.sortedBy { idxOf[it.lobe.id]!! }

    val lx = FloatArray(nL); val ly = FloatArray(nL); val lbend = FloatArray(nL)
    val lcol = IntArray(nL); val lslot = IntArray(nL); val ids = LongArray(nL)
    val istart = IntArray(nL + 1)
    for (i in 0 until nL) {
        val l = lobes[i]
        val p = if (l.id == web.focus) Offset.Zero else lobeRingPos(lobes, l, rx) // a portal page's lobe is its core
        lx[i] = p.x; ly[i] = p.y
        lbend[i] = (Rng(l.uid.hashCode() * 31 + 7).next() - 0.5f) * 30f
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
    val ij = IntArray(nI) { placed[it].j }
    val inew = BooleanArray(nI) { placed[it].newest }
    val ipar = IntArray(nI) { placed[it].parent }
    val irings = IntArray(nI) { ringCount(placed[it].idea.title, placed[it].idea.body) }
    val irecent = BooleanArray(nI) { now - placed[it].idea.updatedAt in 0..RECENT_MS }

    val bstart = IntArray(nL + 1)
    web.branches.forEach { b -> idxOf[b.lobe.id]?.let { bstart[it + 1]++ } }
    for (i in 0 until nL) bstart[i + 1] += bstart[i]
    val bx = FloatArray(web.branches.size) { web.branches[it].pos.x }
    val by = FloatArray(web.branches.size) { web.branches[it].pos.y }
    val idone = BooleanArray(nI) { placed[it].idea.done }
    val gx = FloatArray(web.branches.size) { web.branches[it].ghost?.x ?: Float.NaN }
    val gy = FloatArray(web.branches.size) { web.branches[it].ghost?.y ?: Float.NaN }

    // dust
    val rd = Rng(101)
    val dust = FloatArray(220 * 4)
    for (i in 0 until 220) {
        dust[i * 4] = (rd.next() - 0.5f) * 420f
        dust[i * 4 + 1] = (rd.next() - 0.5f) * 620f
        dust[i * 4 + 2] = rd.next() * TAU
        dust[i * 4 + 3] = rd.next()
    }

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

    return Layout(
        nL, ids, lx, ly, lbend, lcol, lslot, istart, nI, ix, iy, iph, ij, inew, ipar, irings, irecent,
        bstart, bx, by, idone, gx, gy,
        dust, stains, cracks, specks, spores, dl.toFloatArray(),
    )
}

// ---------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------

internal fun rnd(v: Float): Int = floor(v + 0.5f).toInt()

private fun mixRgb(a: Int, b: Int, m: Float): Int {
    val k = m.coerceIn(0f, 1f)
    val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * k).toInt()
    val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * k).toInt()
    val bl = ((a and 255) + ((b and 255) - (a and 255)) * k).toInt()
    return (r shl 16) or (g shl 8) or bl
}

private val SPECK_COLS = intArrayOf(0x140A24, 0x2E1A50, 0x4A3478)

private const val SPARK_RUN = 1.4f

/** How far (0..1) along its vein spark [seed] is at [t] seconds, or -1 when it rests: one run of [SPARK_RUN] s every [every] to 1.8x [every] s, each at its own moment. */
internal fun sparkProgress(seed: Int, t: Float, every: Float): Float {
    val r = Rng(seed * 31 + 5)
    val period = every * (1f + 0.8f * r.next())
    val at = (((t + r.next() * period) % period) + period) % period
    return if (at < SPARK_RUN) at / SPARK_RUN else -1f
}

/** The point [s] (0..1) along the vein bowed by [bend] from (x1, y1) to (x2, y2), into [out]; the same curve ctrl/bz draw. */
internal fun veinPoint(out: FloatArray, x1: Float, y1: Float, x2: Float, y2: Float, bend: Float, s: Float) {
    val dx = x2 - x1; val dy = y2 - y1
    val len = hypot(dx, dy).let { if (it == 0f) 1f else it }
    val cx = (x1 + x2) / 2f - dy / len * bend; val cy = (y1 + y2) / 2f + dx / len * bend
    val u = 1f - s
    out[0] = u * u * x1 + 2f * u * s * cx + s * s * x2
    out[1] = u * u * y1 + 2f * u * s * cy + s * s * y2
}

/**
 * Renders the map into a low-res [Pix] (one pixel = one "world unit" at zoom 1). All pixel work is
 * integer-coordinate rects; glows reuse Pix's pre-rendered sprite. Sprites arrive decoded (drawable-nodpi, unscaled).
 */
internal class BrainRenderer(
    private val coreSprite: ImageBitmap,
    private val heartSprite: ImageBitmap,
    private val heartDeadSprite: ImageBitmap,
) {
    val pix = Pix()
    val W get() = pix.w
    val H get() = pix.h

    /** Show-all: each portal's groups and notes (two numbers per lobe index), drawn as the stars inside it; null = a generic swirl. */
    var contents: IntArray? = null
    /** The portal being flown through (lobe index, -1 = none) and how far: 0 as on the map, 1 it grows with the zoom, past the view. */
    var flyIdx = -1
    var flyP = 0f
    /** The tap on the portal [flyIdx] before it is flown through: 0..1 of its squish and sparkle burst, 0 = none. */
    var tapP = 0f

    /** A search pick flashing in a world: world x, y pairs, and how far the flash has run (0..1); null = none. */
    var flash: FloatArray? = null
    var flashP = 0f

    /** Arrange mode: a dotted ring on every slot a sub brain may move to (world x, y pairs), null = not arranging. */
    var slots: FloatArray? = null
    /** Arrange mode: lobe index [dragIdx] follows the finger at [dragX], [dragY] (world); -1 = none. */
    var dragIdx = -1
    var dragX = 0f
    var dragY = 0f

    /** The learn brain on the main map: world x, y and how many moons circle it; null = none drawn. */
    var learn: FloatArray? = null
    /** How far a fly-through into the learn brain has run: 0 none, 1 inside it. */
    var learnFlyP = 0f

    /** dp per low-res pixel, for the world's portal arc (its width is capped in dp). */
    var pxdp = 2.286f
    /** Screen y (dp) of the world portal's arc centre: pinned under the header, so the page scrolls beneath it (where it sits at rest). */
    var arcTopDp = 0f

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
    private val sp = FloatArray(2)


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

    /** Whether the box around a link can reach the frame; [m] is how far (world) the link may bow out of it. */
    private fun onScreen(x1: Float, y1: Float, x2: Float, y2: Float, m: Float) =
        sx(max(x1, x2) + m) >= 0 && sx(min(x1, x2) - m) <= W && sy(max(y1, y2) + m) >= 0 && sy(min(y1, y2) - m) <= H

    private fun beat(s: Float): Float { val d = (s - beatPh) * 5f; return exp(-d * d) }

    private fun dot(x: Int, y: Int, w: Int, h: Int, rgb: Int, a: Float) = pix.dot(x, y, w, h, rgb, a)

    /** Pixel "circle": two overlapping rects (plus shape). [x], [y], [r] are already low-res. */
    private fun disc(x: Float, y: Float, r: Float, rgb: Int, a: Float) = pix.disc(x, y, r, rgb, a)

    private fun glowAt(wx: Float, wy: Float, r: Float, rgb: Int, a: Float, over: Boolean = false) =
        pix.glow(sx(wx), sy(wy), r * z, rgb, a, add = !over)

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

    /**
     * An organic nerve from the core to ([x2], [y2]): [strands] pixel threads bowed by [bend], pulsing with the beat, wobbling a little,
     * with dark dead specks; no beads. It stops [endGap] short of the far end and at the brain's rim.
     */
    private fun vein(x2: Float, y2: Float, bend: Float, strands: Int, seed: Float, alpha: Float, endGap: Float, hiRgb: Int = 0xB69AFF) {
        if (alpha <= 0f || !onScreen(0f, 0f, x2, y2, 30f)) return
        val len = hypot(x2, y2).let { if (it == 0f) 1f else it }
        val nx = -y2 / len; val ny = x2 / len
        for (st in 0 until strands) {
            val b = bend + (st - (strands - 1) / 2f) * (1f + 2.5f * ROT)
            val n = ceil(len * z * 1.2f).toInt().coerceIn(1, 3000)
            for (i in 0..n) {
                val s = i / n.toFloat()
                veinPoint(sp, 0f, 0f, x2, y2, b, s)
                if (hypot(sp[0], sp[1]) < 33f || hypot(sp[0] - x2, sp[1] - y2) < endGap) continue
                val wob = sin(s * 11f + st * 2.1f + seed + t * 0.6f) * 1.6f * ROT * (1f - abs(s - 0.5f))
                val dead = sin(s * 37f + seed * 3f + st) > 0.93f
                val rgb = if (dead) 0x14061A else mixRgb(0x5A3FA0, hiRgb, beat(s))
                dot(rnd(sx(sp[0] + nx * wob)), rnd(sy(sp[1] + ny * wob)), 1, 1, rgb, alpha)
            }
        }
    }

    private fun sprite(bm: ImageBitmap, wx: Float, wy: Float, scale: Float, alpha: Float, flip: Boolean, filter: ColorFilter? = null) {
        val w = bm.width * scale * z
        val h = bm.height * scale * z
        val cx = sx(wx); val cy = sy(wy)
        if (cx + w < 0 || cy + h < 0 || cx - w > W || cy - h > H) return
        val l = rnd(cx - w / 2f); val t = rnd(cy - h / 2f)
        val dstSize = IntSize(rnd(cx + w / 2f) - l, rnd(cy + h / 2f) - t)
        spritePaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255) / 255f
        spritePaint.colorFilter = filter
        if (flip) { cv.save(); cv.translate(cx, cy); cv.scale(-1f, 1f); cv.translate(-cx, -cy) }
        cv.drawImageRect(bm, IntOffset.Zero, IntSize(bm.width, bm.height), IntOffset(l, t), dstSize, spritePaint)
        if (flip) cv.restore()
    }

    /** A small bright spark [p] (0..1) along a vein: white core, soft purple glow, a short fading tail. */
    private fun spark(x1: Float, y1: Float, x2: Float, y2: Float, bend: Float, p: Float, a: Float) {
        if (p < 0f || a <= 0f || !onScreen(x1, y1, x2, y2, 30f)) return
        val w = if (z > 2f) 2 else 1
        val fade = a * min(1f, min(p, 1f - p) * 8f)
        for (j in 3 downTo 0) {
            veinPoint(sp, x1, y1, x2, y2, bend, max(0f, p - j * 0.05f))
            val f = (1f - j * 0.25f) * fade
            glowAt(sp[0], sp[1], 5f - j, 0x8F7BFF, 0.6f * f)
            dot(rnd(sx(sp[0])), rnd(sy(sp[1])), w, w, if (j == 0) 0xFFFFFF else 0xD6C8FF, f)
        }
    }

    /** Lub-dub as it arrives along the veins, in quarter steps so the scaled sprite stays crisp. */
    private fun heartBeat() = round(max(beat(1f), 0.6f * beat(1.16f)) * 4f) / 4f

    /** Glow behind the vault heart; [sv] 0 = alive, 1 = dead. */
    private fun sun(sv: Float, selected: Boolean, d: Float) {
        val l = 1f - sv
        val ga = (0.35f + 0.3f * heartBeat()) * l * d
        glowAt(SUN_X, SUN_Y - HEART_MID, 34f, 0x8A6CF0, ga)
        if (selected) glowAt(SUN_X, SUN_Y - HEART_MID, 34f, 0x8A6CF0, ga)
    }

    /** The heart swells with the pulse and stops beating as it dies; drawn after the dither pass so the checker doesn't show on it. */
    private fun heart(sv: Float, d: Float) {
        if (d == 0f) return // sprites draw even at alpha 0, so skip them when hidden
        val l = 1f - sv
        val s = HEART_SCALE * (1f + 0.14f * heartBeat() * l)
        val cy = SUN_Y - heartSprite.height * s / 2f // base stays on SUN_Y while it swells
        sprite(heartDeadSprite, SUN_X, cy, s, d, false)
        sprite(heartSprite, SUN_X, cy, s, l * d, false) // the live heart art, recoloured to the app purples
    }

    /** A straight line between two screen points, [gap] px short of each end. */
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, gap: Float, rgb: Int, a: Float) {
        val len = hypot(x2 - x1, y2 - y1)
        val n = ceil(len).toInt().coerceAtMost(4000)
        for (q in 0..n) {
            val dd = q.toFloat()
            if (dd < gap || dd > len - gap) continue
            dot(rnd(x1 + (x2 - x1) * dd / len), rnd(y1 + (y2 - y1) * dd / len), 1, 1, rgb, a)
        }
    }

    /** A four-point pixel star at screen ([x], [y]): a white heart and [arm] px arms in [rgb], fading out. */
    private fun twinkle(x: Float, y: Float, arm: Int, rgb: Int, a: Float) {
        val ix = rnd(x); val iy = rnd(y)
        dot(ix, iy, 1, 1, 0xFFFFFF, a)
        for (k in 1..arm) {
            val f = a * (1f - k / (arm + 1f))
            dot(ix - k, iy, 1, 1, rgb, f); dot(ix + k, iy, 1, 1, rgb, f); dot(ix, iy - k, 1, 1, rgb, f); dot(ix, iy + k, 1, 1, rgb, f)
        }
    }

    /** Dots along a circle of [r] px round screen ([cx], [cy]), from angle [a0] through [sweep], each [th] px square. */
    private fun arc(cx: Float, cy: Float, r: Float, a0: Float, sweep: Float, th: Int, rgb: Int, a: Float) {
        val n = ceil(r * sweep * 1.4f / th).toInt().coerceIn(1, 3000)
        for (q in 0..n) {
            val an = a0 + sweep * q / n
            dot(rnd(cx + cos(an) * r - th / 2f), rnd(cy + sin(an) * r - th / 2f), th, th, rgb, a)
        }
    }

    /**
     * Sub brain [i] as a portal at world ([wx], [wy]), [s] its node scale: a glowing ring in its shade round a dark inside where stars
     * swirl (its groups and notes with show-all on, else a few generic ones), a lighter dashed ring turning one way, an outer dotted
     * ring the other, and three sparkles riding the rim.
     */
    private fun portal(i: Int, wx: Float, wy: Float, s: Float, L: Layout, a: Float) {
        val cx = sx(wx); val cy = sy(wy)
        val r = PORTAL_R * s * z
        val out = PORTAL_OUT * s * z
        if (cx + out * 2f < 0 || cy + out * 2f < 0 || cx - out * 2f > W || cy - out * 2f > H) return
        val col = L.lcol[i]
        val ph = L.lslot[i] * 2.39f
        pix.glow(cx, cy, out * 1.7f, col, (0.3f + 0.25f * beat(1f)) * a)
        // the dark inside, a row at a time so it stays a pixel circle
        for (y in max(ceil(cy - r).toInt(), 0)..min(floor(cy + r).toInt(), H - 1)) {
            val dy = y + 0.5f - cy
            val hw = sqrt(max(0f, r * r - dy * dy))
            val x0 = rnd(cx - hw)
            dot(x0, y, rnd(cx + hw) - x0, 1, 0x0B0618, a)
        }
        // three faint arms winding in
        val arms = (r * 2.2f).toInt().coerceIn(4, 900)
        for (arm in 0 until 3) for (q in 0 until arms) {
            val f = q / arms.toFloat()
            val an = arm * TAU / 3f + f * 3.4f - t * 0.45f + ph
            dot(rnd(cx + cos(an) * f * r * 0.92f), rnd(cy + sin(an) * f * r * 0.92f), 1, 1, mixRgb(0x0B0618, col, 0.2f + 0.3f * f), 0.8f * a)
        }
        // the stars, slowly swirling, the inner ones faster: one bright star per group and one small one per note, 40 at most
        val c = contents
        val ng = if (c != null) min(c[i * 2], 12) else 0
        val nn = if (c != null) min(c[i * 2 + 1], 40 - ng) else 6
        val big = max(1, rnd(r / 40f))
        val rr = Rng(L.lslot[i] * 977 + 3)
        for (k in 0 until ng + nn) {
            val f = sqrt(rr.next()) * 0.84f; val a0 = rr.next() * TAU; val tw = rr.next() * TAU
            val an = a0 - t * (0.1f + 0.3f * (1f - f))
            val x = cx + cos(an) * f * r; val y = cy + sin(an) * f * r
            if (k < ng) {
                pix.glow(x, y, 2.5f + 2f * big, 0xE6DCFF, 0.45f * a)
                twinkle(x, y, big, 0xD6C8FF, a)
            } else {
                val b = (if (c == null) 0.35f else 0.6f) + 0.3f * sin(t * 1.5f + tw)
                dot(rnd(x - big / 2f), rnd(y - big / 2f), big, big, if (k % 3 == 0) 0xFFFFFF else 0xD6C8FF, b * a)
            }
        }
        // the rings; the one being flown through drops its dashes, dots and sparkles by mid-flight, before they blow up into bars
        val th = max(1, rnd(r / 9f))
        arc(cx, cy, r, 0f, TAU, th, col, a)
        val da = a * (if (i == flyIdx) (1f - flyP * 2.5f).coerceIn(0f, 1f) else 1f)
        if (da <= 0f) return
        val rd = (PORTAL_R + 2.5f) * s * z
        val light = mixRgb(col, 0xFFFFFF, 0.45f)
        val dth = max(1, rnd(r / 14f))
        for (q in 0 until 10) arc(cx, cy, rd, q * TAU / 10f + t * 0.5f + ph, TAU / 10f * 0.55f, dth, light, 0.85f * da)
        val oth = max(1, rnd(out / 14f))
        for (q in 0 until 24) {
            val an = q * TAU / 24f - t * 0.3f + ph
            dot(rnd(cx + cos(an) * out - oth / 2f), rnd(cy + sin(an) * out - oth / 2f), oth, oth, col, (if (q % 3 == 0) 0.95f else 0.5f) * da)
        }
        for (q in 0 until 3) {
            val an = t * 0.8f + q * TAU / 3f + ph
            val x = cx + cos(an) * rd; val y = cy + sin(an) * rd
            pix.glow(x, y, 3f + r * 0.12f, 0xC9B6FF, 0.5f * da)
            twinkle(x, y, max(dth, if (sin(t * 2f + q * 2f + ph) > 0.4f) 2 else 1), 0xD6C8FF, da)
        }
    }

    /** A half ring of dots on an ellipse round screen ([cx], [cy]), radii [rx], [ry], from angle [a0] to [a1] (0..PI = the lower half). */
    /** The small learn brain: a mirrored core in a dotted ring, up to six moons circling it slowly. [s] its node scale. */
    private fun learnBrain(x: Float, y: Float, moons: Int, s: Float, a: Float) {
        if (a <= 0f) return
        glowAt(x, y, 22f * s, 0x7A4ACF, (0.2f + 0.3f * beat(0.4f)) * a)
        glowAt(x, y, 14f * s, 0xB69AFF, 0.25f * a)
        ring(x, y, 12.5f * s, 30, t * 1.5f, 0xB69AFF, 0.7f * a)
        sprite(coreSprite, x, y, LEARN_SCALE * s, a, true)
        val w = if (z * s > 2f) 2 else 1
        for (k in 0 until min(moons, 6)) {
            val an = t * 0.15f + k * TAU / min(moons, 6)
            val mx = x + cos(an) * 16f * s; val my = y + sin(an) * 13f * s
            glowAt(mx, my, 3f * s, 0x8F7BFF, 0.5f * a)
            dot(rnd(sx(mx)), rnd(sy(my)), w, w, 0xE6DCFF, a)
        }
    }

    private fun arcE(cx: Float, cy: Float, rx: Float, ry: Float, a0: Float, a1: Float, th: Int, rgb: Int, a: Float) {
        val n = ceil(max(rx, ry) * (a1 - a0) * 1.4f / th).toInt().coerceIn(1, 3000)
        for (q in 0..n) {
            val an = a0 + (a1 - a0) * q / n
            dot(rnd(cx + cos(an) * rx - th / 2f), rnd(cy + sin(an) * ry - th / 2f), th, th, rgb, a)
        }
    }

    /** The world's portal arc, in low-res px: centre x, centre y (on the page top), half width, depth, and the time. Valid after [draw]. */
    fun arcGeom() = floatArrayOf(W / 2f, arcTopDp / pxdp, min(0.55f * W, 300f / pxdp), ARC_D / pxdp, t)

    /**
     * The doorway you came through, hanging as a shallow smile over the top of the page (pinned to the top of the screen, the page scrolls under it): its dark inside with
     * slowly swirling stars, a soft glow and a few faint light rays swaying down over the page. The ring itself is smooth, drawn at
     * full resolution by [drawPortalRing].
     */
    private fun portalAbove(col: Int) {
        val g = arcGeom(); val cx = g[0]; val cy = g[1]; val ox = g[2]; val oy = g[3]
        if (cy + oy < 0f || cy > H) return
        pix.glow(cx, cy + oy * 0.4f, ox * 0.9f, col, 0.3f + 0.15f * beat(1f))
        // rays: soft wedges from the mouth, fading out by 60% of the way down the screen
        val y1 = H * 0.6f
        for (i in 0 until 6) {
            val u = (i - 2.5f) / 2.5f
            val sway = sin(t * 0.3f + i * 1.9f)
            val x0 = cx + u * ox * 0.5f; val slope = u * 0.2f + 0.05f * sway
            val v = (x0 - cx) / ox
            val y0 = cy + oy * sqrt(max(0f, 1f - v * v))
            for (y in max(rnd(y0), 0) until min(rnd(y1), H)) {
                val f = (y - y0) / (y1 - y0)
                val hw = 2f + f * 9f * (1f + 0.3f * sway)
                val a = 0.14f * (1f - f) * (1f - f) * (0.75f + 0.25f * sin(t * 0.5f + i * 2.3f))
                val xc = x0 + (y - y0) * slope
                val l = rnd(xc - hw)
                dot(l, y, rnd(xc + hw) - l, 1, 0xD6C8FF, a)
            }
        }
        // the dark inside, a row at a time: a deep hole, darkest down at the arc, softening toward the top
        for (y in max(ceil(cy).toInt(), 0)..min(floor(cy + oy).toInt(), H - 1)) {
            val v = (y + 0.5f - cy) / oy
            val hw = ox * sqrt(max(0f, 1f - v * v))
            val x0 = rnd(cx - hw)
            dot(x0, y, rnd(cx + hw) - x0, 1, 0x07030F, 0.6f + 0.38f * min(1f, v * 1.6f))
        }
        // a gentle glow of its shade just inside the arc
        for (k in 1..4) arcE(cx, cy, ox - k, oy - k, 0f, PI_F, 1, col, 0.2f * (1f - k / 5f))
        // stars swirling slowly inside, the inner ones faster
        val rr = Rng(4242)
        for (k in 0 until 16) {
            val f = sqrt(rr.next()) * 0.85f; val a0 = rr.next() * TAU; val tw = rr.next() * TAU
            val an = a0 - t * (0.04f + 0.1f * (1f - f))
            val x = cx + cos(an) * f * ox * 0.95f; val y = cy + abs(sin(an)) * f * oy * 0.85f
            val b = 0.55f + 0.3f * sin(t * 1.5f + tw)
            if (k % 5 == 0) twinkle(x, y, 1, 0xD6C8FF, b + 0.15f) else dot(rnd(x), rnd(y), 1, 1, if (k % 3 == 0) 0xFFFFFF else 0xD6C8FF, b)
        }
    }

    /** A quick bright pulse and a small ring burst on each point of [f] (world x, y pairs), [flashP] of the way through. */
    private fun flashAt(f: FloatArray) {
        val e = flashP.coerceIn(0f, 1f)
        val pulse = sin(e * PI_F)
        val out = 1f - (1f - e) * (1f - e)
        for (j in 0 until f.size / 2) {
            val x = sx(f[j * 2]); val y = sy(f[j * 2 + 1])
            pix.glow(x, y, 10f + 8f * pulse, 0xE6DCFF, 0.7f * pulse)
            twinkle(x, y, 2 + rnd(3f * pulse), 0xD6C8FF, pulse)
            for (q in 0 until 10) {
                val an = q * TAU / 10f
                val rr = 4f + 14f * out
                twinkle(x + cos(an) * rr, y + sin(an) * rr, if (q % 2 == 0) 1 else 0, 0xFFFFFF, 1f - e)
            }
        }
    }

    /** Sub brain [i]'s world: constellations of stars, each joined in order by thin lines, a rare spark running along one. */
    private fun world(L: Layout, i: Int) {
        val lineRgb = mixRgb(L.lcol[i], 0xE6DCFF, 0.35f)
        for (k in L.istart[i] + 1 until L.istart[i + 1]) {
            if (L.ij[k] == 0 || L.ipar[k] != L.ipar[k - 1]) continue // a new constellation starts
            line(sx(L.ix[k - 1]), sy(L.iy[k - 1]), sx(L.ix[k]), sy(L.iy[k]), 4f, lineRgb, 0.45f)
            spark(L.ix[k - 1], L.iy[k - 1], L.ix[k], L.iy[k], 0f, sparkProgress(4000 + k, t, 24f), 0.8f)
        }
        // an empty group's ghost: a dim dotted ring where its first star would be
        for (g in L.bstart[i] until L.bstart[i + 1]) if (!L.gx[g].isNaN()) ring(L.gx[g], L.gy[g], 4.5f, 14, t, lineRgb, 0.5f)
        for (k in L.istart[i] until L.istart[i + 1]) {
            val x = sx(L.ix[k]); val y = sy(L.iy[k])
            if (x < -8f || y < -8f || x > W + 8f || y > H + 8f) continue
            if (L.idone[k]) { dot(rnd(x), rnd(y), 1, 1, 0x6F6880, 0.8f); continue } // done: a small dim grey star, no twinkle
            val tw = 0.5f + 0.5f * sin(t * 1.3f + L.iph[k])
            pix.glow(x, y, 6f, 0xC9B6FF, 0.2f + 0.2f * tw)
            twinkle(x, y, if (tw > 0.7f) 3 else 2, 0xD6C8FF, 0.75f + 0.25f * tw)
        }
    }

    /**
     * One frame. [sel] is the lobe whose world this is, or -1 for the main map: the main brain, its veins with sparks running out,
     * every sub brain as a portal, and the vault heart. A world draws its constellations under the portal it came through.
     */
    fun draw(time: Float, camX: Float, camY: Float, zoom: Float, L: Layout, sel: Int, sv: Float, sunSel: Boolean) {
        t = time; this.camX = camX; this.camY = camY; z = zoom
        beatPh = (t * 0.45f) % 1.7f
        val ns = nodeScale(z)
        val focus = sel >= 0
        val d = if (sunSel) 0.45f else 1f // the map dims behind the open vault
        // a world hides the heart completely; a fly-through fades it out early so it never fills the view
        val sunDim = if (focus) 0f else if (flyIdx >= 0) (1f - flyP * 6f).coerceIn(0f, 1f) else if (learnFlyP > 0f) (1f - learnFlyP * 6f).coerceIn(0f, 1f) else 1f

        // a world gets a faint wash of its sub brain's shade
        pix.fill(if (focus) mixRgb(0x07030B, L.lcol[sel], 0.08f) else 0x07030B)

        // fog
        for (i in 0 until 7) {
            glowAt(sin(t * 0.05f + i * 1.3f) * 80f, cos(t * 0.04f + i * 1.7f) * 150f, 80f, if (i % 2 == 1) 0x2A1A6A else 0x3A1A7A, 0.5f * ROT)
        }
        // dark stains
        for (i in 0 until L.stains.size / 3) glowAt(L.stains[i * 3], L.stains[i * 3 + 1], L.stains[i * 3 + 2], 0x030105, 0.55f * ROT, over = true)

        // dust, with parallax; in a world it swirls slowly round the middle, fainter; it stretches into streaks mid fly-through
        val warp = if (!focus && flyIdx in 0 until L.nL) sin(flyP * PI_F) else 0f
        val wcx = if (warp > 0f) sx(L.lx[flyIdx]) else 0f; val wcy = if (warp > 0f) sy(L.ly[flyIdx]) else 0f
        val f = 0.6f + z * 0.25f
        val spanX = 420f * f; val spanY = 620f * f
        for (i in 0 until L.dust.size / 4) {
            var ddx = (L.dust[i * 4] - camX * 0.5f) * f
            var ddy = (L.dust[i * 4 + 1] - camY * 0.5f) * f
            ddx = ((ddx + spanX / 2f) % spanX + spanX) % spanX - spanX / 2f
            ddy = ((ddy + spanY / 2f) % spanY + spanY) % spanY - spanY / 2f
            var a = (1f - ROT * 0.55f) * (0.3f + 0.7f * max(0f, sin(t * 1.3f + L.dust[i * 4 + 2]))) * L.dust[i * 4 + 3]
            if (focus) {
                val an = t * 0.02f + hypot(ddx, ddy) * 0.004f
                val rx = ddx * cos(an) - ddy * sin(an); ddy = ddx * sin(an) + ddy * cos(an); ddx = rx
                a *= 0.55f
            }
            val x = W / 2f + ddx; val y = H / 2f + ddy
            if (warp > 0.02f) {
                // warp: each dust star streaks away from the portal's middle
                val vx = x - wcx; val vy = y - wcy
                line(x, y, x + vx * warp * 0.35f, y + vy * warp * 0.35f, 0f, 0xCDB8FF, a * (1f - 0.4f * warp))
            } else dot(rnd(x), rnd(y), 1, 1, 0xCDB8FF, a)
        }

        if (focus) { world(L, sel); portalAbove(L.lcol[sel]); flash?.let(::flashAt) } else {
            // the main map's rot: big rings and core aura, cracks, mold specks
            floatArrayOf(60f, 105f, 150f).forEachIndexed { i, r -> ring(0f, 0f, r, 90, t * (if (i % 2 == 1) -1f else 1f), 0x39234C, 0.6f * (1f - ROT * 0.5f)) }
            glowAt(0f, 0f, 90f, 0x4A2FA0, 0.3f)
            for (i in L.cracks.indices) {
                val pts = L.cracks[i]
                val rgb = if (i % 3 != 0) 0x140A22 else 0x241038
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
            val sk = if (z > 2f) 2 else 1
            for (i in 0 until L.specks.size / 3) {
                dot(rnd(sx(L.specks[i * 3])), rnd(sy(L.specks[i * 3 + 1])), sk, sk, SPECK_COLS[L.specks[i * 3 + 2].toInt()], 1f)
            }

            // the nerves: core to every portal, core to the heart (faded out while a portal is flown through)
            val va = d * (1f - max(flyP, learnFlyP) * 4f).coerceIn(0f, 1f)
            for (i in 0 until L.nL) vein(L.lx[i], L.ly[i], L.lbend[i], 3, i * 5f, va, (PORTAL_R + 2.5f) * ns)
            vein(SUN_X, SUN_Y - HEART_MID, SUN_BEND, 2, 9f, va * sunDim, 16f, 0xD6C8FF)

            // sparks running out from the core along the veins (the nerves themselves are [vein]);
            // the core gives a small pulse as each one leaves
            var pulse = 0f
            for (i in 0 until L.nL) {
                val p = sparkProgress(1000 + L.lslot[i], t, 12f)
                if (p in 0f..0.15f) pulse = max(pulse, 1f - p / 0.15f)
                spark(0f, 0f, L.lx[i], L.ly[i], L.lbend[i], p, 0.5f * d)
            }

            // drips from the core
            if (ROT > 0.2f) {
                val fade = min(1f, (ROT - 0.2f) * 2f)
                val wh = rnd(z * 1.5f).coerceIn(1, 4)
                for (k in 0 until L.drips.size / 5) {
                    val dx0 = L.drips[k * 5]; val dy0 = L.drips[k * 5 + 1]
                    val n = L.drips[k * 5 + 2].toInt(); val len = L.drips[k * 5 + 3]
                    for (i in 0 until n) {
                        val ph = i * 1.7f + k * 0.9f
                        val x = dx0 + sin(ph * 3f) * (if (n > 5) 14f else 6f)
                        val y = dy0 + (t * 7f + ph * 13f) % len
                        val a = (1f - (y - dy0) / len) * fade * d
                        dot(rnd(sx(x)), rnd(sy(y)), 1, wh, if (i % 3 != 0) 0x4A3A8A else 0x7A62D0, a)
                    }
                }
            }

            // arrange mode: where a sub brain may go
            slots?.let { s -> for (j in 0 until s.size / 2) ring(s[j * 2], s[j * 2 + 1], 13f * ns, 28, t * 2f, 0xC9B6FF, 0.6f) }

            // the portals, all one size; the one being flown through keeps growing with the zoom, after a squish and a sparkle burst
            // the other portals (glow and all) are gone within the first eighth of a fly-through, before they can streak past
            val others = if (flyIdx >= 0) (1f - flyP * 8f).coerceIn(0f, 1f) else (1f - learnFlyP * 8f).coerceIn(0f, 1f)
            for (i in 0 until L.nL) {
                val a = (if (i == dragIdx) 0.35f else d) * (if (i == flyIdx) 1f else others)
                if (a > 0f) portal(i, L.lx[i], L.ly[i], portalScale(i, ns, flyIdx, flyP, tapP), L, a)
            }
            if (tapP > 0f && tapP < 1f && flyIdx in 0 until L.nL) {
                val cx = sx(L.lx[flyIdx]); val cy = sy(L.ly[flyIdx])
                val e = 1f - (1f - tapP) * (1f - tapP)
                for (q in 0 until 10) {
                    val an = q * TAU / 10f + L.lslot[flyIdx] * 0.7f
                    val rr = (PORTAL_OUT + 3f + 20f * e) * ns * z
                    twinkle(cx + cos(an) * rr, cy + sin(an) * rr, if (q % 3 == 0) 2 else 1, 0xFFFFFF, 1f - tapP)
                }
            }
            if (dragIdx in 0 until L.nL) portal(dragIdx, dragX, dragY, 1.1f * ns, L, 1f)

            sun(sv, sunSel, sunDim)

            // the main brain
            glowAt(0f, 0f, 48f, 0x7A4ACF, 0.25f + 0.6f * beat(0.05f) + 0.2f * pulse)
            glowAt(0f, 0f, 36f, 0xB69AFF, 0.3f)
            ring(0f, 0f, 30f, 48, t * 2f, 0xB69AFF, 0.85f)
            ring(0f, 0f, 35f, 56, -t * 1.5f, 0x8A6CF0, 0.7f)
            sprite(coreSprite, 0f, 0f, 0.64f * (1f + 0.06f * pulse), 1f, false)
            learn?.let { learnBrain(it[0], it[1], it[2].toInt(), ns.toDouble().pow(1.0 - learnFlyP).toFloat(), d * others) }

            // spores
            for (i in 0 until L.spores.size / 4) {
                val ph = L.spores[i * 4 + 2]; val sp = L.spores[i * 4 + 3]
                val x = L.spores[i * 4] + sin(t * 0.2f + ph) * 10f
                val y = L.spores[i * 4 + 1] - (t * sp + ph * 30f) % 60f + 30f
                val a = 0.4f + 0.6f * max(0f, sin(t * 2f + ph))
                dot(rnd(sx(x)), rnd(sy(y)), 1, 1, if (i % 3 != 0) 0x8A6CF0 else 0xB69AFF, a)
                if (i % 4 == 0) glowAt(x, y, 3f, 0x8A6CF0, a * 0.5f)
            }
        }

        // 2x2 dither + vignette
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), ditherPaint)
        heart(sv, sunDim)
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
    branches: List<Branch>?,
    camera: BrainCamera,
    selectedLobe: Long,
    onSelectLobe: (Long) -> Unit,
    onAddLobe: (String) -> Unit,
    onRenameLobe: (Long, String) -> Unit,
    /** Moves a sub brain to another slot; whoever sat there takes its old one. */
    onMoveLobe: (Long, Int) -> Unit,
    onDeleteLobe: (Long) -> Unit,
    /** Opens an item's card, growing from [from] (px in the root): [id] 0 starts a new one in [lobeId] (and [branchId], a group). */
    onOpenIdea: (id: Long, lobeId: Long, branchId: Long?, from: Offset) -> Unit,
    onAddBranch: (Long, String) -> Unit,
    onRenameBranch: (Long, String) -> Unit,
    onDeleteBranch: (Long) -> Unit,
    /** The main map shows every group and note (on) or only the sub brains (off). */
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
    /** The Brain's little sounds; the main brain's menu has the switch. */
    soundsOn: Boolean,
    onToggleSounds: () -> Unit,
    vault: Vault,
    sync: SyncManager,
    appScope: CoroutineScope,
    goals: List<Goal>? = null,
    steps: List<Step>? = null,
    /** The learn brain: -2 = closed (the map), -1 = its view, else that goal's page. */
    learnAt: Long = -2L,
    onLearnAt: (Long) -> Unit = {},
    /** Bumped when the Brain tab is tapped while already shown: from inside a world or the learn view, fly back to the map. */
    homeSignal: Int = 0,
    learn: LearnViewModel? = null,
) {
    val lobeList = lobes.orEmpty()
    val ideaList = ideas.orEmpty()
    val branchList = branches.orEmpty()
    val selected = lobeList.firstOrNull { it.id == selectedLobe }
    val selectedNow by rememberUpdatedState(selected?.id ?: -1L)
    var homeTick by remember { mutableStateOf(0) }
    // The portal: the camera flies into the tapped portal until its ring passes the edges of the view, then the world fades in,
    // settling from a little too big. Leaving runs it backwards: the world shrinks away, the camera pulls back out of the ring.
    var portal by remember { mutableStateOf<PortalRun?>(null) }
    var flyId by remember { mutableStateOf(-1L) } // the portal being flown through on the main map
    var flyP by remember { mutableFloatStateOf(0f) } // 0 = the map as it was, 1 = inside the ring
    var worldA by remember { mutableFloatStateOf(1f) } // the page's opacity; it scales with it while a portal runs
    var goTo by remember { mutableStateOf<GoTo?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOff by remember { mutableStateOf(Offset.Zero) } // where this view sits in the root, for the note card's start
    var tapP by remember { mutableFloatStateOf(0f) } // a tapped portal's squish, before the fly-through
    var peek by remember { mutableStateOf<Pair<Idea, Offset>?>(null) }
    val density = LocalDensity.current.density
    val pxSize = round(2.28f * density).coerceAtLeast(1f)
    val rx = ringRx(viewSize.width / density, viewSize.height / density)
    val goalList = goals.orEmpty()
    val stepList = steps.orEmpty()
    val learnOpen = learnAt >= -1
    val learnPos = remember(lobeList, rx) { learnSpot(lobeList.map { lobeRingPos(lobeList, it, rx) }, lobeList.map { it.name.length }) }
    var learnFlyP by remember { mutableFloatStateOf(0f) }
    var focusStep by remember { mutableStateOf(-1L) } // a step the today strip opened its goal at
    val learnGoal = remember { longArrayOf(-1L) } // the page a learn fly-in opens: -1 the learn view, else a goal
    val learnAtNow by rememberUpdatedState(onLearnAt)
    fun startLearn(open: Boolean, goal: Long = -1L) {
        if (portal != null) return
        learnGoal[0] = goal
        portal = PortalRun(if (open) 0L else -1L, null, learn = true)
    }
    fun startPortal(to: Long) {
        if (portal != null) return
        val lobe = lobeList.firstOrNull { it.id == if (to >= 0) to else selectedNow } ?: run { onSelectLobe(to); return }
        portal = PortalRun(to, lobe)
    }
    val selectNow by rememberUpdatedState(onSelectLobe)
    LaunchedEffect(portal) {
        val run = portal ?: return@LaunchedEffect
        val p = if (run.learn) learnPos else lobeRingPos(lobeList, run.lobe!!, rx)
        // deep enough that the ring's inside covers the whole view
        val zIn = max(4f, 1.2f * hypot(viewSize.width.toFloat(), viewSize.height.toFloat()) / 2f / pxSize / (if (run.learn) LEARN_R else PORTAL_R))
        fun flyTo(from: FloatArray, e: Float) {
            // the zoom grows evenly in steps, the portal slides to the middle evenly on the screen
            val z = from[2] * (zIn / from[2]).pow(e)
            val k = (1f - e) * from[2] / z
            camera.x = p.x - (p.x - from[0]) * k; camera.y = p.y - (p.y - from[1]) * k; camera.scale = z
            if (run.learn) learnFlyP = e else flyP = e
        }
        val a = Animatable(0f)
        val ease = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
        if (run.to >= 0) {
            val from = floatArrayOf(camera.x, camera.y, camera.scale)
            camera.outside = from
            Sounds.play(Sfx.Tick)
            if (!run.learn) {
                flyId = run.lobe!!.id
                a.animateTo(1f, tween(160, easing = LinearEasing)) { tapP = value } // the tap: a squish and a burst of sparkles first
                tapP = 0f
                a.snapTo(0f)
            }
            Sounds.play(Sfx.WhooshIn)
            a.animateTo(1f, tween(500, easing = ease)) { flyTo(from, value) }
            worldA = 0f
            if (run.learn) learnAtNow(learnGoal[0]) else selectNow(run.to)
            repeat(2) { withFrameNanos { } } // let the world lay itself out unseen
            flyId = -1L; flyP = 0f; learnFlyP = 0f
            a.snapTo(0f)
            a.animateTo(1f, tween(320, easing = LinearOutSlowInEasing)) { worldA = value }
        } else {
            Sounds.play(Sfx.WhooshOut)
            a.animateTo(1f, tween(250, easing = FastOutLinearInEasing)) { worldA = 1f - value }
            val back = camera.outside ?: camera.overview ?: floatArrayOf(p.x, p.y, camera.home)
            if (!run.learn) flyId = run.lobe!!.id
            flyTo(back, 1f)
            if (run.learn) learnAtNow(-2L) else selectNow(-1L)
            repeat(2) { withFrameNanos { } }
            worldA = 1f
            a.snapTo(0f)
            a.animateTo(1f, tween(500, easing = ease)) { flyTo(back, 1f - value) }
            camera.x = back[0]; camera.y = back[1]; camera.scale = back[2]
            flyId = -1L; flyP = 0f; learnFlyP = 0f
        }
        portal = null
    }
    /** One level out: sub brain -> whole map, through the portal. */
    val stepOut: () -> Unit = {
        if (learnAt >= 0) onLearnAt(-1L) else if (learnOpen) startLearn(false) else if (selectedNow >= 0) startPortal(-1L) else homeTick++
    }
    var seenHome by remember { mutableStateOf(homeSignal) }
    LaunchedEffect(homeSignal) {
        if (homeSignal == seenHome) return@LaunchedEffect
        seenHome = homeSignal
        if (portal != null) return@LaunchedEffect
        if (learnOpen) startLearn(false) else if (selectedNow >= 0) startPortal(-1L)
    }
    var learnMenu by remember { mutableStateOf<Offset?>(null) }
    var addingGoal by remember { mutableStateOf(false) }

    var menu by remember { mutableStateOf<MenuAt?>(null) }
    var arrangingRaw by remember { mutableStateOf(false) }
    val arranging = arrangingRaw && selected == null
    var addingLobe by remember { mutableStateOf(false) }
    var renamingLobe by remember { mutableStateOf<Lobe?>(null) }
    val zoomedIn by remember { derivedStateOf { camera.scale > camera.home * 1.5f } }
    BackHandler(enabled = selected != null || zoomedIn || portal != null || arranging || learnOpen, onBack = {
        if (portal == null) { if (arranging) arrangingRaw = false else stepOut() }
    })
    var addingBranch by remember { mutableStateOf(false) }
    var editingBranch by remember { mutableStateOf<Branch?>(null) }
    var deletingBranch by remember { mutableStateOf<Branch?>(null) }
    var deleting by remember { mutableStateOf<Lobe?>(null) }
    var searching by remember { mutableStateOf(false) }

    // Vault sun: backup / restore. Work runs on appScope so leaving the tab can't cancel a write.
    val last by vault.lastBackupAt.collectAsState()
    val sv = if (vault.canBackup) staleness(last) else 0f
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
    var resetStep by remember { mutableStateOf(0) } // two papers before Drive is emptied
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
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewSize = it }.onGloballyPositioned { rootOff = it.positionInRoot() }
                .background(if (portal != null) Color(0xFF0B0618) else Color.Transparent), // the inside of the ring, while the page fades
        ) {
            // a world settles in from 1.25x, and shrinks to 0.8x as it fades out
            Box(Modifier.fillMaxSize().graphicsLayer {
                val s = if (portal == null || (selected == null && !learnOpen)) 1f else if ((portal?.to ?: -1L) >= 0) 1.25f - 0.25f * worldA else 0.8f + 0.2f * worldA
                scaleX = s; scaleY = s; alpha = worldA
            }) {
                if (learnOpen) LearnView(goalList, stepList, HEADER_H, onTapBrain = { learnMenu = it }, onOpenGoal = { focusStep = -1L; onLearnAt(it) })
                else BrainCanvas(
                    lobeList, branchList, ideaList, showAll, selected?.id ?: -1L, homeTick, camera, sv, sunSel, goTo,
                    topInset = if (selected != null) HEADER_H else TITLE_H,
                    locked = portal != null,
                    arranging = arranging,
                    flyId = flyId, flyP = flyP, tapP = tapP, rx = rx,
                    onTapSun = { onSelectLobe(-1L); sunSel = true },
                    onTapLobe = { id -> sunSel = false; startPortal(id) },
                    onTapBrain = { at -> if (arranging) arrangingRaw = false else menu = MenuAt(at, null) },
                    onOpenIdea = { it, at -> onOpenIdea(it.id, it.lobeId, it.branchId, at + rootOff) },
                    onPeek = { it, at -> if (it != null) Sounds.play(Sfx.Tick); peek = it?.let { i -> i to at } },
                    onTapBranch = { b, at -> menu = MenuAt(at, b) },
                    onTapEmpty = { sunSel = false; stepOut() }, // only the whole map gets here; a portal page ignores empty space
                    onMoveLobe = onMoveLobe,
                    learnAt = learnPos, learnMoons = goalList.size, learnFlyP = learnFlyP,
                    onTapLearn = { sunSel = false; startLearn(true) },
                )
            }
            val today = java.time.LocalDate.now().toEpochDay()
            val due = remember(goalList, stepList, today) { dueSteps(goalList, stepList, today) }
            if (!learnOpen && selected == null && portal == null && !arranging && !searching && due.isNotEmpty()) {
                TodayStrip(due, goalList, today, camera, pxSize, viewSize) { st -> focusStep = st.id; sunSel = false; startLearn(true, st.goalId) }
            }
            if (learnOpen) {
                LearnHeader(goalList.size, Modifier.align(Alignment.TopStart), onBack = { startLearn(false) })
            } else if (selected == null) {
                ScreenTitle("Brain")
                Row(
                    Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (arranging) PixelButton("DONE", { arrangingRaw = false }, primary = true)
                    else if (!searching) {
                        ShowAllToggle(showAll, onToggleShowAll)
                        SearchButton { searching = true }
                    }
                }
            } else {
                SubBrainHeader(selected, ideaList.count { it.lobeId == selected.id }, Modifier.align(Alignment.TopStart), onBack = stepOut)
            }
            if (learnAt >= 0 && learn != null) {
                val g = goalList.firstOrNull { it.id == learnAt }
                if (g != null) GoalPage(g, pathOf(stepList, g.id), focusStep, learn, onBack = { onLearnAt(-1L) })
                else if (goals != null) LaunchedEffect(learnAt) { onLearnAt(-1L) } // deleted, here or on another device
            }
            learnMenu?.let { at -> BrainMenu(at, listOf("+ goal" to { addingGoal = true }), { learnMenu = null }) }
            peek?.let { (i, at) -> if (selected != null) PeekBubble(i, at) }
            menu?.let { m ->
                val close = { menu = null }
                val chips: List<Pair<String, () -> Unit>> = when {
                    m.branch != null -> listOf(
                        "+ note" to { onOpenIdea(0L, m.branch.lobeId, m.branch.id, m.at + rootOff) },
                        "Rename" to { editingBranch = m.branch },
                        "Delete" to { deletingBranch = m.branch },
                    )
                    selected != null -> listOf(
                        "+ group" to { addingBranch = true },
                        "+ note" to { onOpenIdea(0L, selected.id, null, m.at + rootOff) },
                        "Rename" to { renamingLobe = selected },
                        "Delete" to { deleting = selected },
                    )
                    else -> listOf(
                        "+ sub brain" to { addingLobe = true },
                        "Arrange" to { arrangingRaw = true },
                        (if (soundsOn) "Sounds on" else "Sounds off") to onToggleSounds,
                    )
                }
                BrainMenu(m.at, chips, close)
            }
            if (portal != null) Box( // nothing underneath answers while the portal runs
                Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
            )
            if (searching) SearchPanel(
                ideaList, lobeList, branchList,
                onPick = { h ->
                    searching = false
                    // a note: its world pans to its star, which flashes, and its card grows from there; a group: pan and flash
                    goTo = h.idea?.let { GoTo(-1L, it.id) } ?: h.branch?.let { GoTo(it.id) }
                    startPortal(h.lobe.id)
                },
                onClose = { searching = false },
            )
        }
        if (sunSel && selected == null && !learnOpen) {
            VaultSheet(
                canBackup = vault.canBackup,
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
                onReset = { resetStep = 1 },
                onSignOut = sync::signOut,
            )
        }
    }

    val vaultExists = syncUi.vaultExists
    if (askingPassphrase && vaultExists != null && syncUi.working == null) {
        PassphraseDialog(create = !vaultExists, onDismiss = { askingPassphrase = false }, onSubmit = { askingPassphrase = false; sync.unlock(it) })
    }

    if (resetStep == 1) {
        TornNoteDialog(
            title = "Reset sync?",
            body = "This deletes Vesper's files from your Google Drive and lets you set a new passphrase. Everything on this device stays. Your other device will ask for the new passphrase.",
            onKeep = { resetStep = 0 },
            onBurn = { resetStep = 2 },
        )
    }
    if (resetStep == 2) {
        TornNoteDialog(
            title = "Burn the Drive copy?",
            body = "No undo. Only do this if you forgot the passphrase or want to start sync over.",
            onKeep = { resetStep = 0 },
            onBurn = { resetStep = 0; sync.reset() },
        )
    }

    if (addingBranch && selected != null) {
        NameDialog("NEW GROUP", "Name it: Read, Watch, Ideas...", onDismiss = { addingBranch = false }, onSubmit = { onAddBranch(selected.id, it) })
    }
    editingBranch?.let { b ->
        NameDialog("RENAME GROUP", "Name", initial = b.name, submit = "SAVE", onDismiss = { editingBranch = null }, onSubmit = { onRenameBranch(b.id, it) })
    }
    renamingLobe?.let { l ->
        NameDialog("RENAME SUB BRAIN", "Name", initial = l.name, submit = "SAVE", onDismiss = { renamingLobe = null }, onSubmit = { onRenameLobe(l.id, it) })
    }
    deletingBranch?.let { b ->
        PixelDialog({ deletingBranch = null }) {
            Text("DELETE ${b.name.uppercase()}?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, lineHeight = 18.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text(
                "Only the group goes. Its notes move directly onto the sub brain.",
                style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { deletingBranch = null })
                PixelButton("DELETE", {
                    deletingBranch = null
                    onDeleteBranch(b.id)
                }, primary = true)
            }
        }
    }
    if (addingGoal) {
        NameDialog("NEW GOAL", "Name it: Panduri, Spanish...", onDismiss = { addingGoal = false }, onSubmit = { learn?.addGoal(it) })
    }
    if (addingLobe) {
        NameDialog("NEW SUB BRAIN", "Name it: Coding, Business...", onDismiss = { addingLobe = false }, onSubmit = onAddLobe)
    }
    deleting?.let { target ->
        PixelDialog({ deleting = null }) {
            Text("DELETE ${target.name.uppercase()}?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text(
                "Its groups and notes go with it, forever. No undo.",
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
    restoreUri?.let { uri ->
        PixelDialog({ restoreUri = null }) {
            Text("RESTORE THIS COPY?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text(
                "everything in vesper now is replaced by this copy. a safety copy of today is kept. if you use sync, newer changes from your other device come back on the next sync.",
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
}

/** A held star's note: its title and first two lines (or "empty"), in a small bubble just above the star at [at] (px), or below it near the top. */
@Composable
private fun PeekBubble(idea: Idea, at: Offset) {
    val lines = idea.body.trim().lines().filter { it.isNotBlank() }.let { if (idea.title.isBlank()) it.drop(1) else it }
    val shape = RoundedCornerShape(12.dp)
    Layout(
        content = {
            Column(
                Modifier.widthIn(max = 240.dp).clip(shape).background(Color(0xFF140B26)).border(1.dp, Color(0xFF9C84FF), shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    ideaLabel(idea).ifBlank { "..." }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, lineHeight = 22.sp, color = Color(0xFFE6DCFF)),
                )
                if (lines.isEmpty()) Text("empty", style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim.copy(alpha = 0.7f)))
                else Text(
                    lines.take(2).joinToString("\n"), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, lineHeight = 19.sp, color = Px.TextDim),
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { ms, c ->
        val p = ms[0].measure(Constraints(maxWidth = c.maxWidth))
        layout(c.maxWidth, c.maxHeight) {
            val m = 8.dp.toPx()
            val above = at.y - 18.dp.toPx() - p.height
            val x = (at.x - p.width / 2f).coerceIn(m, max(m, c.maxWidth - p.width - m))
            val y = (if (above >= HEADER_H.toPx()) above else at.y + 18.dp.toPx()).coerceIn(m, max(m, c.maxHeight - p.height - m))
            p.place(x.roundToInt(), y.roundToInt())
        }
    }
}

/** A search pick that is a group: once its dimension is in, the camera centres on group [branchId]. A new instance each time. */
internal class GoTo(val branchId: Long, val ideaId: Long = -1L)

/** A brain's menu, opened by tapping it at [at] (px): the centre brain's (main map or dimension) when [branch] is null, else that group's. */
private class MenuAt(val at: Offset, val branch: Branch?)

internal val HEADER_H = 60.dp
/** The "Brain" title row of the whole map. */
private val TITLE_H = 76.dp

/** A portal in flight: into [lobe]'s world ([to] = its id), or out of it to the whole map ([to] = -1). [learn]: the learn brain's instead. */
private class PortalRun(val to: Long, val lobe: Lobe?, val learn: Boolean = false)

/** The show-all toggle beside the search button: an eye, open (lit) when groups and notes show on the main map, closed and dim when hidden. */
@Composable
private fun ShowAllToggle(on: Boolean, onToggle: () -> Unit) =
    BrainIconButton(if (on) "Hide" else "Show all", onToggle) { u ->
        if (on) {
            drawGrid(u, 2, listOf("...XXXXX...", ".XXX...XXX.", "XX..ooo..XX", "X..ooooo..X", "XX..ooo..XX", ".XXX...XXX.", "...XXXXX..."), Px.Lavender)
        } else {
            drawGrid(u, 3, listOf("XX.......XX", "..XX...XX..", "....XXX....", "..X..X..X.."), Px.TextDim)
        }
    }

/** The search button: a magnifier. */
@Composable
private fun SearchButton(onClick: () -> Unit) =
    BrainIconButton("Search", onClick) { u ->
        drawGrid(u, 0, listOf("..XXXXX....", ".X.....X...", "X.......X..", "X.......X..", "X.......X..", "X.......X..", ".X.....X...", "..XXXXX.o..", ".........o.", "..........o"), Px.Lavender)
    }

/** A 44dp square pixel button for the Brain title row: dark fill, thin light-purple outline (brighter while pressed), an icon drawn on an 11x11 grid. */
@Composable
private fun BrainIconButton(description: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.drawscope.DrawScope.(u: Float) -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier.size(44.dp)
            .pixelCard(fill = Color(0xFF14091F), border = if (pressed) Color(0xFFE6DCFF) else Px.Lavender, shadow = false, notch = 3.dp)
            .semantics { contentDescription = description }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { icon((size.width / 11f).let { kotlin.math.floor(it).coerceAtLeast(1f) }) }
    }
}

/** Draws [rows] as whole-pixel squares of [u] px, row 0 at grid row [y0]: X in [col], o in white. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(u: Float, y0: Int, rows: List<String>, col: Color) {
    val off = (size.width - 11 * u) / 2f
    rows.forEachIndexed { y, r -> r.forEachIndexed { x, ch ->
        if (ch != '.') drawRect(if (ch == 'o') Color.White else col, Offset(off + x * u, off + (y + y0) * u), Size(u, u))
    } }
}

/** Chips in a ring round a tapped brain at [at] (px), over a dim backdrop; a tap outside closes it. Each chip closes it too. */
@Composable
internal fun BrainMenu(at: Offset, chips: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    BackHandler(true, onDismiss)
    val pill = RoundedCornerShape(50)
    Layout(
        content = {
            Box(Modifier.fillMaxSize().background(Color(0xB3050210)).pointerInput(Unit) { detectTapGestures { onDismiss() } })
            chips.forEach { (label, act) ->
                Text(
                    label,
                    Modifier.clip(pill).background(Color(0xFF1A1030)).border(1.dp, Px.Lavender, pill)
                        .clickable(role = Role.Button) { onDismiss(); act() }.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, color = Color.White),
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { ms, c ->
        val back = ms[0].measure(Constraints.fixed(c.maxWidth, c.maxHeight))
        val ps = ms.drop(1).map { it.measure(Constraints()) }
        layout(c.maxWidth, c.maxHeight) {
            back.place(0, 0)
            val r = 84.dp.toPx(); val m = 8.dp.toPx()
            ps.forEachIndexed { i, p ->
                val a = -TAU / 4f + i * TAU / ps.size // the first straight up, the rest round the ring
                val x = (at.x + cos(a) * r - p.width / 2f).coerceIn(m, max(m, c.maxWidth - p.width - m))
                val y = (at.y + sin(a) * r - p.height / 2f).coerceIn(m, max(m, c.maxHeight - p.height - m))
                p.place(x.roundToInt(), y.roundToInt())
            }
        }
    }
}

/** Top strip of a dimension: the trail back, its name in its own shade, and how many notes. */
@Composable
private fun SubBrainHeader(lobe: Lobe, count: Int, modifier: Modifier, onBack: () -> Unit) {
    val c = lobeColor(lobe)
    Column(modifier.fillMaxWidth().background(Px.Bg.copy(alpha = 0.86f))) {
        Row(Modifier.fillMaxWidth().height(HEADER_H - 2.dp).padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // the trail: tapping "< BRAIN >" goes back through the portal
                    Text(
                        "< BRAIN >", Modifier.clickable(onClick = onBack).padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                        style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender),
                    )
                    Text(
                        lobe.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = TitleFont, fontSize = 22.sp, color = c, shadow = Shadow(c.copy(alpha = 0.6f), blurRadius = 10f)),
                    )
                }
                Text(
                    if (count == 1) "1 note" else "$count notes",
                    style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
    }
}

/** The world portal's smooth ring at full resolution: soft glow, a solid ring in [col], a thin lavender dashed one just outside, sparkles. */
private fun DrawScope.drawPortalRing(renderer: BrainRenderer, pxSize: Float, col: Int) {
    val g = renderer.arcGeom()
    val cx = g[0] * pxSize; val cy = g[1] * pxSize; val ox = g[2] * pxSize; val oy = g[3] * pxSize; val t = g[4]
    if (cy + oy < 0f || cy > size.height) return
    val c = Color(0xFF000000.toInt() or col)
    fun ring(rx: Float, ry: Float, from: Float, sweep: Float, w: Dp, color: Color) =
        drawArc(color, from, sweep, false, Offset(cx - rx, cy - ry), Size(2f * rx, 2f * ry), style = Stroke(w.toPx(), cap = StrokeCap.Round))
    ring(ox, oy, 0f, 180f, 16.dp, c.copy(alpha = 0.08f))
    ring(ox, oy, 0f, 180f, 9.dp, c.copy(alpha = 0.14f))
    ring(ox, oy, 0f, 180f, 3.dp, c)
    val gap = 5.dp.toPx(); val dx = ox + gap; val dy = oy + gap
    val light = Color(0xFFD6C8FF)
    val fr = (t * 0.05f) % 1f
    for (q in -1..6) { // six dashes and six gaps round the half turn, sliding
        val a0 = (q + fr) * 30f; val a1 = a0 + 18f
        if (a1 > 0f && a0 < 180f) ring(dx, dy, max(a0, 0f), min(a1, 180f) - max(a0, 0f), 1.dp, light.copy(alpha = 0.8f))
    }
    for (q in 0 until 3) {
        val an = ((t * 0.06f + q / 3f) % 1f) * PI_F
        val e = min(1f, sin(an) * 5f)
        val at = Offset(cx + cos(an) * dx, cy + sin(an) * dy)
        drawCircle(Color(0xFFC9B6FF).copy(alpha = 0.25f * e), 8.dp.toPx(), at)
        drawCircle(Color(0xFFC9B6FF).copy(alpha = 0.4f * e), 4.dp.toPx(), at)
        drawCircle(Color.White.copy(alpha = e), 1.6.dp.toPx(), at)
        val arm = (if (sin(t * 2f + q * 2f) > 0.4f) 7.dp else 5.dp).toPx()
        drawLine(Color.White.copy(alpha = 0.8f * e), at - Offset(arm, 0f), at + Offset(arm, 0f), 1f)
        drawLine(Color.White.copy(alpha = 0.8f * e), at - Offset(0f, arm), at + Offset(0f, arm), 1f)
    }
}

@Composable
private fun BrainCanvas(
    lobes: List<Lobe>,
    branches: List<Branch>,
    ideas: List<Idea>,
    showAll: Boolean,
    selectedId: Long,
    homeTick: Int,
    cam: BrainCamera,
    sunSv: Float,
    sunSel: Boolean,
    goTo: GoTo?,
    topInset: Dp,
    locked: Boolean,
    arranging: Boolean,
    onTapSun: () -> Unit,
    flyId: Long,
    flyP: Float,
    tapP: Float,
    rx: Float,
    onTapLobe: (Long) -> Unit,
    onTapBrain: (Offset) -> Unit,
    /** A note's star (or name) was tapped; its star is at px in this view. */
    onOpenIdea: (Idea, Offset) -> Unit,
    /** Holding a star shows its note (star at px) until let go (null). */
    onPeek: (Idea?, Offset) -> Unit,
    onTapBranch: (Branch, Offset) -> Unit,
    onTapEmpty: () -> Unit,
    onMoveLobe: (Long, Int) -> Unit,
    /** The learn brain's place (world), its moon count, how far a fly into it has run, and its tap. */
    learnAt: Offset = Offset(40f, 40f),
    learnMoons: Int = 0,
    learnFlyP: Float = 0f,
    onTapLearn: () -> Unit = {},
) {
    val density = LocalDensity.current.density
    val pxSize = remember(density) { round(2.28f * density).coerceAtLeast(1f) }
    val core = imageResource(Res.drawable.brain_main)
    val heart = imageResource(Res.drawable.vault_heart)
    val heartDead = imageResource(Res.drawable.vault_heart_dead)
    val renderer = remember { BrainRenderer(core, heart, heartDead) }
    val measurer = rememberTextMeasurer(cacheSize = 200)
    val pageFont = PageFont; val titleFont = TitleFont; val pixelFont = PixelFont // composable getters, read outside remember
    var view by remember { mutableStateOf(IntSize.Zero) }
    // A dimension is laid out for this view (its shape and its name sizes), so it is laid out again when the window changes size.
    val nameH = remember(measurer, density, LocalDensity.current.fontScale) { nameHeights(measurer, density, pageFont, titleFont, pixelFont) }
    renderer.pxdp = pxSize / density
    renderer.arcTopDp = topInset.value
    // the world's title hangs just under the portal arc over the page top, so the page starts that far down (arc depth + 6 dp, less the 12 dp margin)
    val arcTop = topInset.value + ARC_D - 6f
    val frame = if (view.width <= 0) DimFrame(top = arcTop, pxdp = pxSize / density, itemH = nameH[0], groupH = nameH[1], coreH = nameH[2])
        else DimFrame(view.width / density, view.height / density, arcTop, pxSize / density, nameH[0], nameH[1], nameH[2])
    val web = remember(lobes, branches, ideas, selectedId, if (selectedId >= 0) frame.w to frame.h else null) {
        placeIdeas(lobes, branches, ideas, selectedId, frame)
    }
    // With show-all on each portal's stars are its groups and notes, two numbers per lobe in the order of [lobes].
    val contents = remember(lobes, branches, ideas, showAll) {
        if (!showAll) null else IntArray(lobes.size * 2).also { c ->
            lobes.forEachIndexed { i, l -> c[i * 2] = branches.count { it.lobeId == l.id }; c[i * 2 + 1] = ideas.count { it.lobeId == l.id } }
        }
    }
    val layout = remember(lobes, web, rx) { buildLayout(lobes, web, rx = rx) }
    val selIdx = layout.lobeIds.indexOf(selectedId)
    val labels = remember(lobes, web, layout, density, pxSize) { BrainLabels(measurer, density, pxSize, pageFont, titleFont, pixelFont, lobes, web, layout) }
    val learnLabel = remember(measurer, density) {
        val c = Color(0xFFC9B6FF)
        GlowText(measurer.measure("learn", TextStyle(fontFamily = pageFont, fontSize = 18.sp, lineHeight = 20.sp, color = c, shadow = Shadow(c.copy(alpha = 0.7f), blurRadius = 10f * density))))
    }
    var aim by remember { mutableStateOf<FloatArray?>(null) } // x, y, zoom; a new array glides there
    fun overviewAim() = fitOverview(labels::overviewBoxes, view.width / density, view.height / density, topInset.value, pxSize / density, NODE_ZOOM, MIN_ZOOM)
    // The page swap through the portal sets the camera at once (no glide): a dimension at its top, fitted to the width; the whole map as it opens.
    var wasPortal by remember { mutableStateOf(false) }
    LaunchedEffect(selectedId, view.width, view.height, lobes.any { it.id == selectedId }) {
        if (view.width <= 0) return@LaunchedEffect
        val lobe = lobes.firstOrNull { it.id == selectedId }
        val a = if (lobe != null) {
            wasPortal = true
            web.dim?.aim
        } else if (wasPortal) { wasPortal = false; if (locked) null else overviewAim() } else null // a portal flies the camera back itself
        if (a != null) { aim = null; cam.x = a[0]; cam.y = a[1]; cam.scale = a[2] }
    }
    LaunchedEffect(homeTick) { if (homeTick > 0) aim = overviewAim() }
    // Until anyone touches the camera it follows the fitted overview: when the size is known, and again when the lobes load or change
    // (labels is rebuilt then; at a cold start the lobes arrive after the size, and a fit on none must not count as a touch).
    // A new window width (a desktop window maximized or resized) always fits it again.
    var fittedW by remember { mutableStateOf(0) }
    LaunchedEffect(view, labels) {
        if (selectedId < 0) cam.fitUntouched(view.width, force = fittedW > 0 && view.width != fittedW, aim = ::overviewAim)
        if (view.width > 0) fittedW = view.width
    }
    // A search pick, once its world is in and the portal is done: pan so it shows, flash it, then a note's card grows from its star.
    val doneGoTo = remember { arrayOfNulls<GoTo>(1) } // each pick runs once, not again on a later visit
    val webNow by rememberUpdatedState(web)
    val openIdeaNow by rememberUpdatedState(onOpenIdea)
    LaunchedEffect(goTo, web.focus >= 0, locked) {
        val g = goTo ?: return@LaunchedEffect
        val w = webNow
        if (w.focus < 0 || locked || doneGoTo[0] === g || view.height <= 0) return@LaunchedEffect
        val item = w.items.firstOrNull { it.idea.id == g.ideaId }
        val group = if (item == null) w.branches.indexOfFirst { it.branch.id == g.branchId } else -1
        if (item == null && group < 0) return@LaunchedEffect
        doneGoTo[0] = g
        val spot = item?.pos ?: w.branches[group].pos
        val oy = ceil(view.height / pxSize) * pxSize / 2f
        fun screenY() = (spot.y - cam.y) * pxSize * cam.scale + oy
        val toY = if (item == null) max(cam.y, spot.y) // a group: glide its name to the middle, as before
            else if (screenY() !in (topInset.value + 40f) * density..view.height - 70f * density) spot.y else cam.y
        if (toY != cam.y) {
            aim = null
            val y0 = cam.y
            Animatable(0f).animateTo(1f, tween(380, easing = FastOutSlowInEasing)) { cam.y = y0 + (toY - y0) * value }
        }
        renderer.flash = if (item != null) floatArrayOf(spot.x, spot.y)
            else (listOf(spot) + w.items.filter { it.parent == group }.map { it.pos }).flatMap { listOf(it.x, it.y) }.toFloatArray()
        Animatable(0f).animateTo(1f, tween(if (item != null) 350 else 600, easing = LinearEasing)) { renderer.flashP = value }
        renderer.flash = null
        if (item != null) {
            val u = pxSize * cam.scale
            val ox = ceil(view.width / pxSize) * pxSize / 2f
            openIdeaNow(item.idea, Offset((spot.x - cam.x) * u + ox, screenY()))
        }
    }
    LaunchedEffect(aim) {
        val a = aim ?: return@LaunchedEffect
        val x0 = cam.x; val y0 = cam.y; val s0 = cam.scale
        Animatable(0f).animateTo(1f, tween(400, easing = FastOutSlowInEasing)) {
            cam.x = x0 + (a[0] - x0) * value; cam.y = y0 + (a[1] - y0) * value; cam.scale = s0 + (a[2] - s0) * value
        }
    }
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
    // Arrange mode: a ring on every place on the ring a sub brain may move to (it swaps with whoever sits there).
    val slots = remember(layout, arranging) {
        if (!arranging || layout.nL == 0) null else FloatArray(layout.nL * 2) { if (it % 2 == 0) layout.lx[it / 2] else layout.ly[it / 2] }
    }
    fun lobePos(l: Lobe) = layout.lobeIds.indexOf(l.id).let { if (it < 0) Offset.Zero else Offset(layout.lx[it], layout.ly[it]) }

    /** In a world the page may not leave: the top stays where it rests, and it scrolls only until the last name reaches the bottom area. */
    fun clampWorldY(size: IntSize, oy: Float) {
        val w = webNow; val dim = w.dim
        if (w.focus < 0 || dim == null) return
        val u = pxSize * cam.scale
        val rest = (0f - dim.aim[1]) * pxSize * dim.aim[2] + oy // screen y of the sub brain's spot when the page rests
        val minY = (oy - rest) / u
        var bottom = 0f // lowest name edge, screen px below the spot at this zoom
        fun edge(p: Offset, name: FloatArray?) { bottom = max(bottom, p.y * u + (name?.get(3) ?: 0f) * density) }
        w.items.forEach { edge(it.pos, it.name) }
        w.branches.forEach { edge(it.pos, it.name); it.ghost?.let { g -> edge(g, it.ghostName) } }
        val maxY = (bottom + oy - (size.height - 80f * density)) / u
        cam.y = cam.y.coerceIn(minY, max(minY, maxY))
    }

    /** Scales by [zoom] around [at] (px in a [size] view) and follows [pan]: the world point under the fingers stays put. */
    fun zoomAt(size: IntSize, at: Offset, pan: Offset, zoom: Float) {
        val ox = ceil(size.width / pxSize) * pxSize / 2f
        val oy = ceil(size.height / pxSize) * pxSize / 2f
        val old = cam.scale
        val next = (old * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val rx = at.x - ox
        val ry = at.y - oy
        val wx = cam.x + rx / (pxSize * old)
        val wy = cam.y + ry / (pxSize * old)
        cam.scale = next
        cam.x = wx - (rx + pan.x) / (pxSize * next)
        cam.y = wy - (ry + pan.y) / (pxSize * next)
        clampWorldY(size, oy)
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .clipToBounds() // labels must not spill over the side rail
            .onSizeChanged { view = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ -> zoomAt(size, centroid, pan, zoom) }
            }
            .pointerInput(Unit) {
                // Mouse wheel; touch screens never send this.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Scroll) continue
                        val c = e.changes.first()
                        zoomAt(size, c.position, Offset.Zero, exp(-c.scrollDelta.y * 0.18f))
                        c.consume()
                    }
                }
            }
            .pointerInput(lobes, web, layout, selectedId, locked, arranging, onTapSun, learnAt) {
                var peeked = false
                fun starAt(tap: Offset): Pair<Placed, Offset>? {
                    if (selectedId < 0) return null
                    val u = pxSize * cam.scale
                    val ox = ceil(size.width / pxSize) * pxSize / 2f
                    val oy = ceil(size.height / pxSize) * pxSize / 2f
                    return web.items.map { it to Offset((it.pos.x - cam.x) * u + ox, (it.pos.y - cam.y) * u + oy) }
                        .minByOrNull { (it.second - tap).getDistance() }?.takeIf { (it.second - tap).getDistance() <= 26.dp.toPx() }
                }
                detectTapGestures(onPress = { at ->
                    peeked = false
                    if (locked) return@detectTapGestures
                    val star = starAt(at) ?: return@detectTapGestures
                    // hold ~420 ms on a star to peek; letting go closes it, and that release is no tap
                    if (withTimeoutOrNull(420) { tryAwaitRelease() } != null) return@detectTapGestures
                    peeked = true
                    onPeek(star.first.idea, star.second)
                    tryAwaitRelease()
                    onPeek(null, star.second)
                }, onTap = { tap ->
                    if (peeked) return@detectTapGestures
                    if (locked) return@detectTapGestures
                    val z = cam.scale
                    val u = pxSize * z
                    val ox = ceil(size.width / pxSize) * pxSize / 2f
                    val oy = ceil(size.height / pxSize) * pxSize / 2f
                    fun screen(w: Offset) = Offset((w.x - cam.x) * u + ox, (w.y - cam.y) * u + oy)
                    val reach = 26.dp.toPx()
                    val centre = screen(Offset.Zero)
                    // A world: a star opens its note, the name at the top the sub brain's menu, a group's name or stars its menu.
                    if (selectedId >= 0) {
                        val ik = web.items.indices.minByOrNull { (screen(web.items[it].pos) - tap).getDistance() }
                        val pad = 10.dp.toPx()
                        fun inBox(at: Offset, b: FloatArray) = tap.x >= at.x + b[0] * density - pad && tap.x <= at.x + b[2] * density + pad &&
                            tap.y >= at.y + b[1] * density - pad && tap.y <= at.y + b[3] * density + pad
                        val g = web.branches.indices.firstOrNull { g ->
                            val stars = web.items.filter { it.parent == g }.map { screen(it.pos) } + listOfNotNull(web.branches[g].ghost?.let(::screen))
                            inBox(screen(web.branches[g].pos), web.branches[g].name) ||
                                (stars.isNotEmpty() && tap.x in stars.minOf { it.x } - reach..stars.maxOf { it.x } + reach && tap.y in stars.minOf { it.y } - reach..stars.maxOf { it.y } + reach)
                        }
                        val title = web.dim?.coreName
                        val named = web.items.indices.firstOrNull { inBox(screen(web.items[it].pos), web.items[it].name) }
                        if (ik != null && (screen(web.items[ik].pos) - tap).getDistance() <= reach) onOpenIdea(web.items[ik].idea, screen(web.items[ik].pos))
                        else if (named != null) onOpenIdea(web.items[named].idea, screen(web.items[named].pos))
                        else if (title != null && inBox(centre, title)) onTapBrain(Offset(centre.x, centre.y + (title[1] + title[3]) / 2f * density))
                        else if (g != null) onTapBranch(web.branches[g].branch, screen(web.branches[g].pos))
                        return@detectTapGestures
                    }
                    if ((screen(Offset(SUN_X, SUN_Y - HEART_MID)) - tap).getDistance() <= maxOf(36.dp.toPx(), 18f * u)) {
                        onTapSun()
                        return@detectTapGestures
                    }
                    // The learn brain flies you into its view.
                    if ((screen(learnAt) - tap).getDistance() <= maxOf(30.dp.toPx(), 18f * u * nodeScale(z))) {
                        if (!arranging) onTapLearn()
                        return@detectTapGestures
                    }
                    // The main map: a portal flies you into its world.
                    val lobe = lobes.minByOrNull { (screen(lobePos(it)) - tap).getDistance() }
                    if (lobe != null && (screen(lobePos(lobe)) - tap).getDistance() <= maxOf(36.dp.toPx(), PORTAL_OUT * u * nodeScale(z))) {
                        if (!arranging) onTapLobe(lobe.id)
                        return@detectTapGestures
                    }
                    // The main brain is the core at the world origin (inside the r35 ring).
                    if ((centre - tap).getDistance() <= maxOf(36.dp.toPx(), 30f * u)) {
                        onTapBrain(centre)
                        return@detectTapGestures
                    }
                    onTapEmpty()
                })
            }
            .pointerInput(lobes, layout, arranging) {
                // Arrange mode: drag a sub brain onto another slot. Innermost, so a drag that starts on a sub brain wins over panning.
                if (!arranging) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val u = pxSize * cam.scale
                    val ox = ceil(size.width / pxSize) * pxSize / 2f
                    val oy = ceil(size.height / pxSize) * pxSize / 2f
                    fun world(p: Offset) = Offset((p.x - ox) / u + cam.x, (p.y - oy) / u + cam.y)
                    val w0 = world(down.position)
                    val lobe = lobes.minByOrNull { (lobePos(it) - w0).getDistance() }
                        ?.takeIf { (lobePos(it) - w0).getDistance() * u <= maxOf(36.dp.toPx(), 14f * u * nodeScale(cam.scale)) }
                        ?: return@awaitEachGesture
                    val start = lobePos(lobe)
                    var moved = false
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        val delta = c.position - down.position
                        if (!moved && delta.getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) {
                            c.consume()
                            renderer.dragIdx = layout.lobeIds.indexOf(lobe.id)
                            renderer.dragX = start.x + delta.x / u; renderer.dragY = start.y + delta.y / u
                        }
                    }
                    if (moved) {
                        val drop = Offset(renderer.dragX, renderer.dragY)
                        val to = lobes.filter { it.id != lobe.id }.minByOrNull { (lobePos(it) - drop).getDistance() }
                        if (to != null && (lobePos(to) - drop).getDistance() * u <= 48.dp.toPx()) onMoveLobe(lobe.id, to.slot)
                    }
                    renderer.dragIdx = -1
                }
            },
    ) {
        val vw = size.width.toInt()
        val vh = size.height.toInt()
        if (vw <= 0 || vh <= 0) return@Canvas
        val px = pxSize.toInt()
        renderer.resize(ceil(vw / pxSize).toInt(), ceil(vh / pxSize).toInt())
        renderer.slots = slots
        renderer.contents = contents
        renderer.flyIdx = layout.lobeIds.indexOf(flyId); renderer.flyP = flyP; renderer.tapP = tapP
        renderer.learn = if (selIdx >= 0) null else floatArrayOf(learnAt.x, learnAt.y, learnMoons.toFloat())
        renderer.learnFlyP = learnFlyP
        renderer.draw(time, cam.x, cam.y, cam.scale, layout, selIdx, sunSv, sunSel)
        drawIntoCanvas { renderer.blit(it, px) }

        val fade = (1f - max(flyP, learnFlyP) * 8f).coerceIn(0f, 1f)
        if (selIdx >= 0) drawPortalRing(renderer, pxSize, layout.lcol[selIdx])
        labels.draw(this, renderer, cam.x, cam.y, cam.scale, selIdx, sunSel, sunSv, fade = fade)
        if (selIdx < 0 && fade > 0f) {
            val u = pxSize * cam.scale
            val x = (learnAt.x - cam.x) * u + renderer.W * pxSize / 2f
            val y = (learnAt.y + 17f * nodeScale(cam.scale) - cam.y) * u + renderer.H * pxSize / 2f
            learnLabel.draw(this, x - learnLabel.tl.size.width / 2f, y, fade * (if (sunSel) 0.45f else 1f))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Resume sheet
// ---------------------------------------------------------------------------------------------

@Composable
private fun VaultSheet(
    canBackup: Boolean,
    status: String,
    busy: Boolean,
    onKeep: () -> Unit,
    onRestore: () -> Unit,
    sync: SyncUi,
    onSignIn: () -> Unit,
    onPassphrase: () -> Unit,
    onSyncNow: () -> Unit,
    onReset: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .pixelCard(fill = Px.Surface, border = Px.Lavender, shadow = false, notch = 4.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (canBackup) {
            Text("VAULT", Modifier.padding(vertical = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
            Text(status, Modifier.padding(top = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelButton("KEEP A COPY", { if (!busy) onKeep() }, primary = true, modifier = Modifier.weight(1f))
                PixelButton("RESTORE", { if (!busy) onRestore() }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        Text("SYNC", Modifier.padding(vertical = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
        val line = sync.working ?: when {
            !sync.available -> "sync is not set up in this build."
            !sync.signedIn -> "keep your devices in step through google drive."
            !sync.unlocked -> "signed in. the passphrase comes next."
            sync.lastSyncAt == 0L -> "ready. not synced yet."
            else -> "last sync ${java.time.Instant.ofEpochMilli(sync.lastSyncAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))}"
        }
        Text(line, Modifier.padding(top = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim))
        sync.error?.let { Text(it, Modifier.padding(top = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.Lavender)) }
        if (sync.available) {
            Spacer(Modifier.height(12.dp))
            val idle = sync.working == null
            when {
                !sync.signedIn -> PixelButton("SIGN IN", { if (idle) onSignIn() }, primary = true, modifier = Modifier.fillMaxWidth())
                !sync.unlocked -> PixelButton("PASSPHRASE", { if (idle) onPassphrase() }, primary = true, modifier = Modifier.fillMaxWidth())
                else -> PixelButton("SYNC NOW", { if (idle) onSyncNow() }, primary = true, modifier = Modifier.fillMaxWidth())
            }
            if (sync.signedIn) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PixelButton("RESET SYNC", { if (idle) onReset() }, modifier = Modifier.weight(1f))
                    PixelButton("SIGN OUT", { if (idle) onSignOut() }, modifier = Modifier.weight(1f))
                }
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
        PixelField(pass, { pass = it }, if (create) "$MIN_PASSPHRASE+ characters, 4+ words" else "Passphrase", password = true, onDone = ::submit, cursor = Px.Lavender)
        if (create) {
            Spacer(Modifier.height(10.dp))
            PixelField(again, { again = it }, "Same again", password = true, onDone = ::submit, cursor = Px.Lavender)
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
internal fun PixelField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = 0.dp,
    onDone: (() -> Unit)? = null,
    password: Boolean = false,
    plain: Boolean = false, // no capitals or corrections, for links
    cursor: Color = Px.Violet,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(fontFamily = PixelFont, fontSize = 11.sp, lineHeight = 18.sp, color = Px.Text),
        cursorBrush = SolidColor(cursor),
        visualTransformation = if (password) PasswordVisualTransformation('*') else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = if (password || plain) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            autoCorrectEnabled = if (password || plain) false else null,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NameDialog(
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    initial: String = "",
    submit: String = "GROW",
) {
    var text by remember { mutableStateOf(initial) }
    fun submit() {
        if (text.isNotBlank()) onSubmit(text)
        onDismiss()
    }
    PixelDialog(onDismiss) {
        Text(title, style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(16.dp))
        PixelField(text, { text = it.take(24) }, hint, onDone = ::submit, cursor = Px.Lavender)
        Spacer(Modifier.height(18.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("CANCEL", onDismiss)
            PixelButton(submit, ::submit, primary = true)
        }
    }
}
