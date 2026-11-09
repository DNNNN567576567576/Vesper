package app.vesper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.TextLayoutResult
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
import androidx.compose.foundation.layout.FlowRow
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
import app.vesper.res.brain_lobe
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
import app.vesper.data.Idea
import app.vesper.data.Lobe
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
internal const val TAU = 6.2831855f
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

/** A group node; [size] is how many ideas hang off it. */
internal class PlacedBranch(val branch: Branch, val lobe: Lobe, val pos: Offset, val ph: Float, val bend: Float, val size: Int)

/** An idea on the map; [parent] indexes [Web.branches], -1 = directly on the lobe. */
internal class Placed(
    val idea: Idea, val lobe: Lobe, val pos: Offset,
    val ph: Float, val bend: Float, val j: Int, val newest: Boolean, val parent: Int,
)

/** Everything on the map below the lobes. [branches] and [items] run lobe by lobe, in the order of the lobes given. */
internal class Web(val branches: List<PlacedBranch>, val items: List<Placed>, val focus: Long = -1L)

// Items sit on a staggered grid wide enough for a label and tight enough to keep the clouds small. World units.
internal const val CELL_X = 9f
internal const val CELL_Y = 5f

private fun cellPos(ix: Int, iy: Int) = Offset((ix + (iy and 1) * 0.5f) * CELL_X, iy * CELL_Y)

/**
 * One lobe's cloud: the grid cells it may use. Each lobe owns a wedge around the core, halfway to its neighbours and to the
 * vault, so clouds can't overlap however large they grow: they just reach further out. Cells near any node stay empty.
 */
private class Cloud(private val c: Offset, private val others: List<Offset>, private val a: Float, private val left: Float, private val right: Float) {
    private val used = HashSet<Pair<Int, Int>>()

    private fun allowed(p: Offset): Boolean {
        val rho = hypot(p.x, p.y)
        if (rho < 46f) return false
        var d = atan2(p.y, p.x) - a
        if (d > TAU / 2) d -= TAU
        if (d < -TAU / 2) d += TAU
        val m = 5f / rho
        if (d < -left + m || d > right - m) return false
        val ex = (p.x - c.x) / 14f; val ey = (p.y - c.y) / (if (p.y > c.y) 18f else 11f) // the lobe, and its name below it
        return ex * ex + ey * ey >= 1f && hypot(p.x - SUN_X, p.y - SUN_Y) >= 24f && others.none { hypot(p.x - it.x, p.y - it.y) < 20f }
    }

    /** The [n] free cells nearest [anchor], nearest first, now taken. */
    fun take(anchor: Offset, n: Int): List<Pair<Int, Int>> {
        if (n == 0) return emptyList()
        val ax = rnd(anchor.x / CELL_X); val ay = rnd(anchor.y / CELL_Y)
        var r = ceil(sqrt(n.toFloat())).toInt() + 3
        while (true) {
            val found = ArrayList<Pair<Int, Int>>()
            for (iy in ay - 2 * r..ay + 2 * r) for (ix in ax - r..ax + r) {
                if ((ix to iy) !in used && allowed(cellPos(ix, iy))) found.add(ix to iy)
            }
            if (found.size >= n || r > 512) {
                val best = found.sortedWith(compareBy({ (cellPos(it.first, it.second) - anchor).getDistanceSquared() }, { it.second }, { it.first })).take(n)
                used.addAll(best)
                return best
            }
            r *= 2
        }
    }

    fun reserve(ix: Int, iy: Int) { used.add(ix to iy) }
}

private fun blobRadius(n: Int) = sqrt(n * CELL_X * CELL_Y / (TAU / 2))

/**
 * The whole web, from uids and slots only: local row ids differ per device, these don't. Groups fan out from their lobe,
 * ideas fill the cells nearest their group, or nearest the lobe when they have none. The [focus]ed lobe instead gets
 * [focusFormation]: a family tree, the groups side by side under the lobe and each one's neurons under it.
 */
internal fun placeIdeas(lobes: List<Lobe>, branches: List<Branch>, ideas: List<Idea>, focus: Long = -1L): Web {
    val centres = lobes.map { lobeWorldPos(it.slot) }
    val sunAngle = atan2(SUN_Y, SUN_X)
    val marks = (centres.map { atan2(it.y, it.x) } + sunAngle).sorted()
    val byLobe = ideas.groupBy { it.lobeId }
    val outB = ArrayList<PlacedBranch>()
    val outI = ArrayList<Placed>()
    lobes.forEachIndexed { li, lobe ->
        val c = centres[li]
        val a = atan2(c.y, c.x)
        val at = marks.indexOf(a)
        val prev = if (at == 0) marks.last() - TAU else marks[at - 1]
        val next = if (at == marks.lastIndex) marks.first() + TAU else marks[at + 1]
        val left = min((a - prev) / 2f, 1.4f); val right = min((next - a) / 2f, 1.4f)
        val cloud = Cloud(c, centres.filterIndexed { i, _ -> i != li }, a, left, right)

        val groups = branches.filter { it.lobeId == lobe.id }.sortedBy { it.uid }
        val known = groups.mapTo(HashSet()) { it.id }
        val mine = byLobe[lobe.id].orEmpty().sortedBy { it.uid }
        val byGroup = mine.groupBy { i -> i.branchId?.takeIf { it in known } }
        val loose = byGroup[null].orEmpty()
        val newest = mine.maxWithOrNull(compareBy({ it.updatedAt }, { it.uid }))?.uid

        fun item(idea: Idea, cell: Pair<Int, Int>?, fallback: Offset, j: Int, parent: Int) {
            val r = Rng(idea.uid.hashCode() * 7919 + 13)
            val base = cell?.let { cellPos(it.first, it.second) } ?: fallback
            val p = Offset(base.x + (r.next() - 0.5f) * 2f, base.y + (r.next() - 0.5f) * 1.2f)
            outI.add(Placed(idea, lobe, p, r.next() * TAU, (r.next() - 0.5f) * 8f, j, idea.uid == newest, parent))
        }

        if (lobe.id == focus) {
            fun rings(l: List<Idea>) = l.map { ringCount(it.title, it.body) }
            val f = focusFormation(c, rings(loose), groups.map { rings(byGroup[it.id].orEmpty()) })
            groups.forEachIndexed { g, b ->
                val r = Rng(b.uid.hashCode() * 31 + 7)
                outB.add(PlacedBranch(b, lobe, f.groups[g], r.next() * TAU, (r.next() - 0.5f) * 12f, byGroup[b.id].orEmpty().size))
            }
            val first = outB.size - groups.size
            loose.forEachIndexed { j, i -> item(i, null, f.loose[j], j, -1) }
            groups.forEachIndexed { g, b -> byGroup[b.id].orEmpty().forEachIndexed { j, i -> item(i, null, f.items[g][j], j, first + g) } }
            return@forEachIndexed
        }

        // Fan the groups out from the lobe, wider where the wedge allows, each with room for its own blob.
        val arcs = groups.map { 2f * blobRadius(byGroup[it.id].orEmpty().size) + CELL_X }
        val rho = hypot(c.x, c.y)
        val fanLeft = atan2(rho * left, 30f).coerceIn(0.1f, 1.3f); val fanRight = atan2(rho * right, 30f).coerceIn(0.1f, 1.3f)
        val d = max(20f + blobRadius(loose.size) + (arcs.maxOrNull() ?: 0f) / 2f, 0.6f * arcs.sum() / (fanLeft + fanRight))
        var along = a - arcs.sum() / d * fanLeft / (fanLeft + fanRight)
        val nodes = groups.mapIndexed { g, b ->
            val ang = along + arcs[g] / (2f * d)
            along += arcs[g] / d
            val cell = cloud.take(Offset(c.x + cos(ang) * d, c.y + sin(ang) * d), 1).firstOrNull()
            // room for the node and the name that hangs under it
            cell?.let { (ix, iy) -> for (x in -1..1) for (y in -1..1) cloud.reserve(ix + x, iy + y) }
            val r = Rng(b.uid.hashCode() * 31 + 7)
            val pos = cell?.let { cellPos(it.first, it.second) } ?: Offset(c.x + cos(ang) * d, c.y + sin(ang) * d)
            outB.add(PlacedBranch(b, lobe, pos, r.next() * TAU, (r.next() - 0.5f) * 12f, byGroup[b.id].orEmpty().size))
            outB.lastIndex to pos
        }
        val anchor = Offset(c.x + cos(a) * 8f, c.y + sin(a) * 8f)
        cloud.take(anchor, loose.size).let { cells -> loose.forEachIndexed { j, i -> item(i, cells.getOrNull(j), anchor, j, -1) } }
        groups.forEachIndexed { g, b ->
            val (index, pos) = nodes[g]
            val list = byGroup[b.id].orEmpty()
            cloud.take(pos, list.size).let { cells -> list.forEachIndexed { j, i -> item(i, cells.getOrNull(j), pos, j, index) } }
        }
    }
    return Web(outB, outI, focus)
}

/** Nodes stop growing at this zoom, so zooming further opens the web up instead of blowing the sprites up. */
internal const val NODE_ZOOM = 2f
/** How bright the rest of the map stays behind a focused sub brain, so its neighbours and the heart do not crowd it. */
internal const val FOCUS_DIM = 0.2f
internal fun nodeScale(z: Float) = min(1f, NODE_ZOOM / z)

private const val HOME_ZOOM = 0.85f
private const val MIN_ZOOM = 0.6f
private const val MAX_ZOOM = 12f
/** A focused sub brain zooms in as far as this to fill the screen. */
private const val FOCUS_MAX_ZOOM = 6f
/** Group names and taps start here; ideas when their labels fit their cells, see itemZ. */
internal const val GROUP_ZOOM = 1.5f
private const val LOBE_MIN_ZOOM = 1.7f

/** A camera aim (x, y, zoom) that fits [pts] in a [w] x [h] view of low-res pixels. */
private fun frame(pts: List<Offset>, w: Float, h: Float, minZ: Float, maxZ: Float): FloatArray {
    val x0 = pts.minOf { it.x }; val x1 = pts.maxOf { it.x }
    val y0 = pts.minOf { it.y }; val y1 = pts.maxOf { it.y }
    val z = min((w - 30f) / max(1f, x1 - x0), (h - 80f) / max(1f, y1 - y0)).coerceIn(minZ, maxZ)
    return floatArrayOf((x0 + x1) / 2f, (y0 + y1) / 2f, z)
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

/** The vault sun: one fixed node (not a lobe), centered below the brain (x = 0), past the first lobe ring. */
internal const val SUN_X = 0f
internal const val SUN_Y = 125f
private const val SUN_BEND = 8f
private const val HEART_SCALE = 0.85f // sprite is 26x38, so about 22x32 world units (lobe: 24x19.5); its base sits on SUN_Y
private const val HEART_MID = 14f // world units from the heart's base up to its middle, for glow, keep-out and tap

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

    /**
     * Puts the camera on the overview [aim] if the size is known ([viewW] > 0) and nobody has panned or zoomed since the default
     * or the last fit. Called again whenever the size or the lobes change, so the order they arrive in does not matter.
     */
    internal fun fitUntouched(viewW: Int, aim: () -> FloatArray) {
        val f = fitted
        val untouched = if (f == null) x == 0f && y == 0f && scale == HOME_ZOOM else x == f[0] && y == f[1] && scale == f[2]
        if (viewW <= 0 || !untouched) return
        val a = aim()
        x = a[0]; y = a[1]; scale = a[2]; fitted = a
    }
}

/** Everything the renderer needs, derived from the real lobes/ideas plus the seeded rot decoration. */
internal class Layout(
    val nL: Int, val lobeIds: LongArray, val lx: FloatArray, val ly: FloatArray, val lbend: FloatArray,
    val lcol: IntArray, val lslot: IntArray, val istart: IntArray,
    val nI: Int, val ix: FloatArray, val iy: FloatArray, val iph: FloatArray, val ibend: FloatArray,
    val ij: IntArray, val inew: BooleanArray, val ipar: IntArray, val irings: IntArray, val irecent: BooleanArray,
    val bstart: IntArray, val bx: FloatArray, val by: FloatArray, val bph: FloatArray, val bbend: FloatArray,
    val dust: FloatArray,      // x, y, ph, b
    val blots: FloatArray,     // x, y, r, ph
    val stains: FloatArray,    // x, y, r
    val cracks: Array<FloatArray>, // x0, y0, x1, y1, ...
    val specks: FloatArray,    // x, y, colour index
    val spores: FloatArray,    // x, y, ph, sp
    val drips: FloatArray,     // x, y, n, len, owner lobe (-1 none)
)

/** [now] decides which items are recent (touched within 48 hours). */
internal fun buildLayout(lobes: List<Lobe>, web: Web, now: Long = System.currentTimeMillis()): Layout {
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
        val p = lobeWorldPos(l.slot)
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
    val ibend = FloatArray(nI) { placed[it].bend }
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
    val bph = FloatArray(web.branches.size) { web.branches[it].ph }
    val bbend = FloatArray(web.branches.size) { web.branches[it].bend }

    // dust
    val rd = Rng(101)
    val dust = FloatArray(220 * 4)
    for (i in 0 until 220) {
        dust[i * 4] = (rd.next() - 0.5f) * 420f
        dust[i * 4 + 1] = (rd.next() - 0.5f) * 620f
        dust[i * 4 + 2] = rd.next() * TAU
        dust[i * 4 + 3] = rd.next()
    }

    // blots (rot spots): each kept with probability min(1, ROT * 1.2), decided by its own roll so it is stable;
    // then pushed clear of every lobe, group, item, label and the vault, or left out when there is no room
    val keep = min(1f, ROT * 1.2f)
    val bAll = ArrayList<FloatArray>()
    val keepOut = keepOutBoxes(lx, ly, bx, by, ix, iy, SUN_X, SUN_Y).let { base ->
        // a focused sub brain also keeps holes off its whole tree
        val f = idxOf[web.focus]
        if (f == null) base else base + focusKeepOutBoxes(lx[f], ly[f], bx.slice(bstart[f] until bstart[f + 1]), by.slice(bstart[f] until bstart[f + 1]), ix.slice(istart[f] until istart[f + 1]), iy.slice(istart[f] until istart[f + 1]))
    }
    fun blot(x: Float, y: Float, r: Float, ph: Float, u: Float) {
        if (u < keep) clearHole(x, y, r + 1f, keepOut)?.let { bAll.add(floatArrayOf(it[0], it[1], r, ph)) }
    }
    val rc = Rng(202)
    repeat(12) {
        val a = rc.next() * TAU; val d = 22f + rc.next() * 24f
        val r = 2f + rc.next() * 5f; val ph = rc.next() * TAU; val u = rc.next()
        blot(cos(a) * d, sin(a) * d * 1.2f, r, ph, u)
    }
    for (i in 0 until nL) {
        val rl = Rng(lobes[i].uid.hashCode() * 17 + 303)
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
        nL, ids, lx, ly, lbend, lcol, lslot, istart, nI, ix, iy, iph, ibend, ij, inew, ipar, irings, irecent,
        bstart, bx, by, bph, bbend,
        dust, blots, stains, cracks, specks, spores, dl.toFloatArray(),
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

private val BEADS = floatArrayOf(0.25f, 0.45f, 0.62f, 0.8f)
private val SPECK_COLS = intArrayOf(0x1E0A24, 0x4A1650, 0x7A2A6A)

/**
 * Renders the map into a low-res [Pix] (one pixel = one "world unit" at zoom 1). All pixel work is
 * integer-coordinate rects; glows reuse Pix's pre-rendered sprite. Sprites arrive decoded (drawable-nodpi, unscaled).
 */
internal class BrainRenderer(
    private val coreSprite: ImageBitmap,
    private val lobeSprite: ImageBitmap,
    private val heartSprite: ImageBitmap,
    private val heartDeadSprite: ImageBitmap,
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

    /** Whether the box around a link can reach the frame; [m] is how far (world) the link may bow out of it. */
    private fun onScreen(x1: Float, y1: Float, x2: Float, y2: Float, m: Float) =
        sx(max(x1, x2) + m) >= 0 && sx(min(x1, x2) - m) <= W && sy(max(y1, y2) + m) >= 0 && sy(min(y1, y2) - m) <= H

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
        if (!onScreen(0f, 0f, x2, y2, 30f)) return
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
        if (!onScreen(x1, y1, x2, y2, 10f)) return
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

    /** Where a pulse is [p] (0..1) of the way from the core, through the lobe and group, to item [k]; leaves it in bzx/bzy. */
    private fun sparkPos(L: Layout, k: Int, p: Float) {
        var li = 0
        while (L.istart[li + 1] <= k) li++
        val g = L.ipar[k]
        val px = if (g < 0) L.lx[li] else L.bx[g]; val py = if (g < 0) L.ly[li] else L.by[g]
        val xs = floatArrayOf(0f, L.lx[li], px, ipx[k]); val ys = floatArrayOf(0f, L.ly[li], py, ipy[k])
        val bends = floatArrayOf(L.lbend[li], if (g < 0) 0f else L.bbend[g], L.ibend[k])
        // legs: core -> lobe, lobe -> group (only with one), parent -> item
        val from = if (g < 0) intArrayOf(0, 2) else intArrayOf(0, 1, 2)
        val to = if (g < 0) intArrayOf(1, 3) else intArrayOf(1, 2, 3)
        val bd = if (g < 0) floatArrayOf(bends[0], bends[2]) else bends
        var total = 0f
        for (j in from.indices) total += hypot(xs[to[j]] - xs[from[j]], ys[to[j]] - ys[from[j]])
        var at = p.coerceIn(0f, 1f) * total
        for (j in from.indices) {
            val len = hypot(xs[to[j]] - xs[from[j]], ys[to[j]] - ys[from[j]])
            if (at <= len || j == from.lastIndex) {
                val x1 = xs[from[j]]; val y1 = ys[from[j]]; val x2 = xs[to[j]]; val y2 = ys[to[j]]
                ctrl(x1, y1, x2, y2, bd[j])
                bz(x1, y1, ccx, ccy, x2, y2, if (len <= 0f) 1f else (at / len).coerceIn(0f, 1f))
                return
            }
            at -= len
        }
    }

    /** The bright pulse running the veins to item [k], with a short tail; flares the neuron as it lands. */
    private fun sparkPulse(L: Layout, k: Int, p: Float) {
        for (j in 4 downTo 0) {
            sparkPos(L, k, max(0f, p - j * 0.045f))
            val f = 1f - j * 0.2f
            glowAt(bzx, bzy, (6f - j) , 0xF0C8F0, 0.9f * f)
            val w = if (z > 2f) 2 else 1
            dot(rnd(sx(bzx)), rnd(sy(bzy)), w, w, 0xFFFFFF, f)
        }
        if (p > 0.8f) glowAt(ipx[k], ipy[k], 12f, 0xF596E1, 0.8f * (p - 0.8f) * 5f)
    }

    /** Lub-dub as it arrives along the veins, in quarter steps so the scaled sprite stays crisp. */
    private fun heartBeat() = round(max(beat(1f), 0.6f * beat(1.16f)) * 4f) / 4f

    /** Glow behind the vault heart; [sv] 0 = alive, 1 = dead. */
    private fun sun(sv: Float, selected: Boolean, d: Float) {
        val l = 1f - sv
        val ga = (0.35f + 0.3f * heartBeat()) * l * d
        glowAt(SUN_X, SUN_Y - HEART_MID, 34f, 0xD45AAB, ga)
        if (selected) glowAt(SUN_X, SUN_Y - HEART_MID, 34f, 0xD45AAB, ga)
    }

    /** The heart swells with the pulse and stops beating as it dies; drawn after the dither pass so the checker doesn't show on it. */
    private fun heart(sv: Float, d: Float) {
        if (d == 0f) return // sprites draw even at alpha 0, so skip them when hidden
        val l = 1f - sv
        val s = HEART_SCALE * (1f + 0.14f * heartBeat() * l)
        val cy = SUN_Y - heartSprite.height * s / 2f // base stays on SUN_Y while it swells
        sprite(heartDeadSprite, SUN_X, cy, s, d, false)
        sprite(heartSprite, SUN_X, cy, s, l * d, false)
    }

    /** One frame, in the mockup's draw order. [sel] is the selected lobe index or -1. */
    fun draw(time: Float, camX: Float, camY: Float, zoom: Float, L: Layout, sel: Int, sv: Float, sunSel: Boolean, spark: Int = -1, sparkP: Float = 0f) {
        t = time; this.camX = camX; this.camY = camY; z = zoom
        beatPh = (t * 0.45f) % 1.7f
        val ns = nodeScale(z)
        if (ipx.size < L.nI) { ipx = FloatArray(L.nI); ipy = FloatArray(L.nI) }
        fun dimOf(i: Int) = if ((sel < 0 && !sunSel) || sel == i) 1f else if (sel >= 0) FOCUS_DIM else 0.45f
        val sunDim = if (sel < 0) 1f else 0f // a focused sub brain hides the heart and its veins completely

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

        // big rings + core aura: a focused sub brain shows no trace of the core
        if (sel < 0) {
            floatArrayOf(60f, 105f, 150f).forEachIndexed { i, r -> ring(0f, 0f, r, 90, t * (if (i % 2 == 1) -1f else 1f), 0x39234C, 0.6f * (1f - ROT * 0.5f)) }
            glowAt(0f, 0f, 90f, 0x5A2FA0, 0.3f)
        }

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
            if (sel >= 0 && i != sel) continue // a focused sub brain draws nothing of the other lobes
            val d = dimOf(i)
            vein(L.lx[i], L.ly[i], L.lbend[i], 3, i * 5f, if (sel >= 0) 0f else d) // a focused sub brain shows no veins to the core: they would cross its tree
            for (g in L.bstart[i] until L.bstart[i + 1]) {
                thread(L.lx[i], L.ly[i], L.bx[g], L.by[g], L.bbend[g], L.bph[g], L.lcol[i], d)
                thread(L.lx[i], L.ly[i], L.bx[g], L.by[g], L.bbend[g] + 2.5f, L.bph[g] + 1f, L.lcol[i], d)
            }
            for (k in L.istart[i] until L.istart[i + 1]) {
                val nx = L.ix[k] + sin(t * 0.5f + L.iph[k]) * 1.5f * ns
                val ny = L.iy[k] + cos(t * 0.4f + L.iph[k]) * 1.5f * ns
                ipx[k] = nx; ipy[k] = ny
                val g = L.ipar[k]
                thread(if (g < 0) L.lx[i] else L.bx[g], if (g < 0) L.ly[i] else L.by[g], nx, ny, L.ibend[k], L.iph[k], L.lcol[i], d)
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
                val d = if (sel >= 0) 0f else dimOf(i)
                ctrl(0f, 0f, L.lx[i], L.ly[i], L.lbend[i])
                bz(0f, 0f, ccx, ccy, L.lx[i], L.ly[i], ps)
                glowAt(bzx, bzy, 6f, 0xF0C8F0, 0.9f * d)
                val w = if (z > 2f) 2 else 1
                dot(rnd(sx(bzx)), rnd(sy(bzy)), w, w, 0xFFFFFF, d)
            }
        }

        // ideas: a core dot, ringed by how much is written; a magenta core when touched in the last 48 hours
        for (i in 0 until L.nL) {
            if (sel >= 0 && i != sel) continue
            val d = dimOf(i)
            val col = L.lcol[i]
            for (k in L.istart[i] until L.istart[i + 1]) {
                val ph = L.iph[k]
                val a = 0.55f + 0.25f * sin(t * 1.7f + ph) + 0.5f * beat(1.5f)
                val s = max(2, rnd(2f * min(z, 2.8f) / 1.4f))
                val cx = sx(ipx[k]); val cy = sy(ipy[k])
                val rings = L.irings[k]
                val reach = s * 0.9f + 3f + rings * 2.2f + 8f * ns * z
                if (cx + reach < 0 || cy + reach < 0 || cx - reach > W || cy - reach > H) continue
                val recent = L.irecent[k]
                if (rings > 0) glowAt(ipx[k], ipy[k], max(3f, 7f - L.ij[k] * 0.5f) * ns, if (recent) 0xD45AAB else col, a * d)
                if (L.inew[k]) glowAt(ipx[k], ipy[k], 11f * ns, 0xD45AAB, 0.5f * d)
                for (r in 0 until rings) {
                    ring(
                        ipx[k], ipy[k], (s * 0.9f + 3f + r * 2.2f) / z, 8 + 4 * r, t * (if (r % 2 == 0) 1.2f else -1.2f) + ph,
                        if (r == 2) 0xD45AAB else 0xB69AFF, 0.8f * d,
                    )
                }
                disc(cx, cy, s * 0.9f + 1f, 0x2A0C30, d)
                val ds = max(1, s shr 1)
                dot(rnd(cx + s * 0.6f), rnd(cy - s * 0.2f), ds, ds, 0x06020A, d)
                dot(rnd(cx - s / 2f), rnd(cy - s / 2f), s, s, if (recent) 0xD45AAB else if (rings == 0) 0x6F6080 else 0xF6EEFF, d)
            }
        }
        if (spark in 0 until L.nI) sparkPulse(L, spark, sparkP)

        // drips
        if (ROT > 0.2f) {
            val fade = min(1f, (ROT - 0.2f) * 2f)
            val wh = rnd(z * 1.5f).coerceIn(1, 4)
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

        // lobes, each with its groups
        for (i in 0 until L.nL) {
            if (sel >= 0 && i != sel) continue
            val d = dimOf(i)
            val isSel = i == sel
            val col = L.lcol[i]
            val ga = (0.3f + 0.5f * beat(1f)) * d
            for (g in L.bstart[i] until L.bstart[i + 1]) {
                glowAt(L.bx[g], L.by[g], 13f * ns, col, ga)
                ring(L.bx[g], L.by[g], 8f * ns, 20, t * 3f, col, 0.8f * d)
                sprite(lobeSprite, L.bx[g], L.by[g], 0.25f * ns, d, g % 2 == 1)
            }
            glowAt(L.lx[i], L.ly[i], 26f * ns, col, ga)
            if (isSel) glowAt(L.lx[i], L.ly[i], 26f * ns, col, ga)
            ring(L.lx[i], L.ly[i], 15f * ns, 32, t * 3f, if (isSel) 0xB69AFF else col, 0.8f * d)
            sprite(lobeSprite, L.lx[i], L.ly[i], 0.5f * ns, d, L.lslot[i] % 2 == 1)
        }

        sun(sv, sunSel, sunDim)

        // core: not drawn at all behind a focused sub brain
        if (sel < 0) {
            glowAt(0f, 0f, 48f, 0xB03AA0, 0.25f + 0.6f * beat(0.05f))
            glowAt(0f, 0f, 36f, 0xB69AFF, 0.3f)
            ring(0f, 0f, 30f, 48, t * 2f, 0xB69AFF, 0.85f)
            ring(0f, 0f, 35f, 56, -t * 1.5f, 0xD45AAB, 0.7f)
            sprite(coreSprite, 0f, 0f, 0.64f, 1f, false)
        }

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
    onDeleteLobe: (Long) -> Unit,
    /** Opens an item's page: [id] 0 starts a new one in [lobeId] (and [branchId], a group). */
    onOpenIdea: (id: Long, lobeId: Long, branchId: Long?) -> Unit,
    onAddBranch: (Long, String) -> Unit,
    onRenameBranch: (Long, String) -> Unit,
    onDeleteBranch: (Long) -> Unit,
    vault: Vault,
    sync: SyncManager,
    appScope: CoroutineScope,
) {
    val lobeList = lobes.orEmpty()
    val ideaList = ideas.orEmpty()
    val branchList = branches.orEmpty()
    val web = remember(lobeList, branchList, ideaList, selectedLobe) { placeIdeas(lobeList, branchList, ideaList, selectedLobe) }
    val selected = lobeList.firstOrNull { it.id == selectedLobe }
    val selectedNow by rememberUpdatedState(selected?.id ?: -1L)
    var homeTick by remember { mutableStateOf(0) }
    /** One level out: sub brain -> whole map. */
    val stepOut: () -> Unit = {
        if (selectedNow >= 0) onSelectLobe(-1L) else homeTick++
    }

    var addingLobe by remember { mutableStateOf(false) }
    val zoomedIn by remember { derivedStateOf { camera.scale > HOME_ZOOM * 1.5f } }
    BackHandler(enabled = selected != null || zoomedIn, onBack = stepOut)
    var adding by remember { mutableStateOf(false) } // the + menu
    var addingBranch by remember { mutableStateOf(false) }
    var editingBranch by remember { mutableStateOf<Branch?>(null) }
    var deletingBranch by remember { mutableStateOf<Branch?>(null) }
    var deleting by remember { mutableStateOf<Lobe?>(null) }
    var managing by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf<GoTo?>(null) }

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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BrainCanvas(
                lobeList, web, selected?.id ?: -1L, homeTick, camera, sv, sunSel, goTo,
                topInset = if (selected != null) HEADER_H else TITLE_H,
                onTapSun = { onSelectLobe(-1L); sunSel = true },
                onTapLobe = { id -> sunSel = false; onSelectLobe(id) },
                onDoubleTapLobe = { id -> onOpenIdea(0L, id, null) },
                onTapBrain = { managing = true },
                onOpenIdea = { onOpenIdea(it.id, it.lobeId, it.branchId) },
                onTapBranch = { editingBranch = it },
                onTapEmpty = { sunSel = false; stepOut() },
            )
            if (selected == null) {
                ScreenTitle("Brain")
                if (!searching) PixelButton("SEARCH", { searching = true }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp))
            } else {
                SubBrainHeader(selected, ideaList.count { it.lobeId == selected.id }, Modifier.align(Alignment.TopStart)) { adding = true }
            }
            if (searching) SearchPanel(ideaList, lobeList, onPick = { searching = false; goTo = GoTo(it.id) }, onClose = { searching = false })
        }
        if (sunSel && selected == null) {
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

    if (adding && selected != null) {
        PixelDialog({ adding = false }) {
            Text("ADD TO ${selected.name.uppercase()}", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, lineHeight = 18.sp, color = Px.Lavender))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("GROUP", { adding = false; addingBranch = true })
                PixelButton("ITEM", { adding = false; onOpenIdea(0L, selected.id, null) }, primary = true)
            }
        }
    }
    if (addingBranch && selected != null) {
        NameDialog("NEW GROUP", "Name it: Read, Watch, Ideas...", onDismiss = { addingBranch = false }, onSubmit = { onAddBranch(selected.id, it) })
    }
    editingBranch?.let { b ->
        NameDialog(
            "GROUP", "Name", initial = b.name, submit = "SAVE", onDismiss = { editingBranch = null }, onSubmit = { onRenameBranch(b.id, it) },
            onDelete = { editingBranch = null; deletingBranch = b },
            onAddItem = { onOpenIdea(0L, b.lobeId, b.id) },
        )
    }
    deletingBranch?.let { b ->
        PixelDialog({ deletingBranch = null }) {
            Text("DELETE ${b.name.uppercase()}?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, lineHeight = 18.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text(
                "Only the group goes. Its items move directly onto the lobe.",
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
    if (addingLobe) {
        NameDialog("NEW LOBE", "Name it: Coding, Business...", onDismiss = { addingLobe = false }, onSubmit = onAddLobe)
    }
    deleting?.let { target ->
        PixelDialog({ deleting = null }) {
            Text("DELETE ${target.name.uppercase()}?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text(
                "Its groups and ideas go with it, forever. No undo.",
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


/** A request from the search list: glide to this item and open it. A new instance each time, so picking the same one again works. */
internal class GoTo(val ideaId: Long)

private val HEADER_H = 60.dp
/** The "Brain" title row of the whole map. */
private val TITLE_H = 76.dp

/** The pulse that runs to a neuron as it opens: [k] is the item, [t0] when it started and [dur] how long (map seconds). */
private class SparkRun(val k: Int, val t0: Float, val dur: Float)

/** Top strip of a focused sub brain: its name in its own colour, how many items, and the + menu. */
@Composable
private fun SubBrainHeader(lobe: Lobe, count: Int, modifier: Modifier, onAdd: () -> Unit) {
    val c = lobeColor(lobe)
    Column(modifier.fillMaxWidth().background(Px.Bg.copy(alpha = 0.86f))) {
        Row(Modifier.fillMaxWidth().height(HEADER_H - 2.dp).padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    lobe.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = TitleFont, fontSize = 22.sp, color = c, shadow = Shadow(c.copy(alpha = 0.6f), blurRadius = 10f)),
                )
                Text(
                    if (count == 1) "1 note" else "$count notes",
                    style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim),
                )
            }
            PixelButton("+", onAdd, primary = true)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
    }
}

@Composable
private fun BrainCanvas(
    lobes: List<Lobe>,
    web: Web,
    selectedId: Long,
    homeTick: Int,
    cam: BrainCamera,
    sunSv: Float,
    sunSel: Boolean,
    goTo: GoTo?,
    topInset: Dp,
    onTapSun: () -> Unit,
    onTapLobe: (Long) -> Unit,
    onDoubleTapLobe: (Long) -> Unit,
    onTapBrain: () -> Unit,
    onOpenIdea: (Idea) -> Unit,
    onTapBranch: (Branch) -> Unit,
    onTapEmpty: () -> Unit,
) {
    val density = LocalDensity.current.density
    val pxSize = remember(density) { round(2.28f * density).coerceAtLeast(1f) }
    val core = imageResource(Res.drawable.brain_main)
    val lobeImg = imageResource(Res.drawable.brain_lobe)
    val heart = imageResource(Res.drawable.vault_heart)
    val heartDead = imageResource(Res.drawable.vault_heart_dead)
    val renderer = remember { BrainRenderer(core, lobeImg, heart, heartDead) }
    val layout = remember(lobes, web) { buildLayout(lobes, web) }
    val selIdx = layout.lobeIds.indexOf(selectedId)
    val measurer = rememberTextMeasurer(cacheSize = 200)
    val pageFont = PageFont; val titleFont = TitleFont; val pixelFont = PixelFont // composable getters, read outside remember
    val labels = remember(lobes, web, layout, density, pxSize) { BrainLabels(measurer, density, pxSize, pageFont, titleFont, pixelFont, lobes, web, layout) }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var aim by remember { mutableStateOf<FloatArray?>(null) } // x, y, zoom; a new array glides there
    var firstRun by remember { mutableStateOf(true) }
    fun overviewAim() = fitOverview(labels::overviewBoxes, view.width / density, view.height / density, topInset.value, pxSize / density, HOME_ZOOM, MIN_ZOOM)
    LaunchedEffect(selectedId, homeTick) {
        if (firstRun) { firstRun = false; return@LaunchedEffect }
        val lobe = lobes.firstOrNull { it.id == selectedId }
        aim = if (lobe != null) {
            // the whole sub brain (lobe, groups, items, rings, names) centred between the header and the bottom bar
            fitFocus(labels.scene(layout.lobeIds.indexOf(lobe.id)), view.width / density, view.height / density, topInset.value, pxSize / density, 0.8f, FOCUS_MAX_ZOOM)
        } else overviewAim()
    }
    // Until anyone touches the camera it follows the fitted overview: when the size is known, and again when the lobes load or change
    // (labels is rebuilt then; at a cold start the lobes arrive after the size, and a fit on none must not count as a touch)
    LaunchedEffect(view, labels) {
        if (selectedId < 0) cam.fitUntouched(view.width, ::overviewAim)
    }
    LaunchedEffect(aim) {
        val a = aim ?: return@LaunchedEffect
        val x0 = cam.x; val y0 = cam.y; val s0 = cam.scale
        Animatable(0f).animateTo(1f, tween(400, easing = FastOutSlowInEasing)) {
            cam.x = x0 + (a[0] - x0) * value; cam.y = y0 + (a[1] - y0) * value; cam.scale = s0 + (a[2] - s0) * value
        }
    }
    val tapScope = rememberCoroutineScope()
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

    // Opening a neuron: a pulse runs the veins to it (about a third of a second), then the page slides up. A newer tap replaces it.
    var spark by remember { mutableStateOf<SparkRun?>(null) }
    val pending = remember { arrayOfNulls<Job>(1) }
    val openNow by rememberUpdatedState(onOpenIdea)
    fun onScreen(w: Offset): Boolean {
        val sx = (w.x - cam.x) * pxSize * cam.scale + view.width / 2f
        val sy = (w.y - cam.y) * pxSize * cam.scale + view.height / 2f
        return sx >= 0f && sx <= view.width && sy >= topInset.value * density && sy <= view.height
    }
    fun openItem(k: Int, ms: Long = 340L) {
        val idea = web.items[k].idea
        pending[0]?.cancel()
        // off the screen there is nothing to watch: open at once rather than make anyone wait
        val wait = if (onScreen(web.items[k].pos)) ms else 0L
        spark = if (wait > 0) SparkRun(k, time, wait * 0.95f / 1000f) else null
        pending[0] = tapScope.launch { delay(wait); spark = null; openNow(idea) }
    }
    LaunchedEffect(goTo) {
        val g = goTo ?: return@LaunchedEffect
        val k = web.items.indexOfFirst { it.idea.id == g.ideaId }
        if (k < 0) return@LaunchedEffect
        val p = web.items[k].pos
        val z = max(cam.scale, 2.4f)
        if (onScreen(p)) {
            aim = floatArrayOf(p.x, p.y, z) // the camera glides while the pulse runs
            openItem(k, 400L)
        } else {
            cam.x = p.x; cam.y = p.y; cam.scale = z
            openItem(k)
        }
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
            .pointerInput(lobes, web, layout, selectedId, onTapSun) {
                // Single taps act at once (waiting for a possible double tap felt laggy). Returns what a
                // second tap may turn into a double tap: a Lobe.
                fun handle(tap: Offset): Any? {
                    pending[0]?.cancel(); spark = null // a new tap beats a neuron still opening
                    val z = cam.scale
                    val u = pxSize * z
                    val ox = ceil(size.width / pxSize) * pxSize / 2f
                    val oy = ceil(size.height / pxSize) * pxSize / 2f
                    fun screen(w: Offset) = Offset((w.x - cam.x) * u + ox, (w.y - cam.y) * u + oy)
                    // Drawn (wobbling) dot position when the renderer has one, else the layout position.
                    fun ipos(k: Int) = if (renderer.ipx.size > k) Offset(renderer.ipx[k], renderer.ipy[k]) else web.items[k].pos
                    // In a focused sub brain its groups and items answer at any zoom; otherwise once zoomed in.
                    fun live(lobeId: Long) = if (selectedId >= 0) lobeId == selectedId else z >= GROUP_ZOOM
                    val reach = 26.dp.toPx()
                    val g = web.branches.filter { live(it.lobe.id) }.minByOrNull { (screen(it.pos) - tap).getDistance() }
                    val dg = if (g == null) Float.MAX_VALUE else (screen(g.pos) - tap).getDistance()
                    val ik = web.items.indices.filter { live(web.items[it].lobe.id) }.minByOrNull { (screen(ipos(it)) - tap).getDistance() }
                    val di = if (ik == null) Float.MAX_VALUE else (screen(ipos(ik)) - tap).getDistance()
                    if (g != null && dg <= reach && dg <= di) { onTapBranch(g.branch); return null }
                    if (ik != null && di <= reach) { openItem(ik); return null }
                    if (selectedId < 0 && (screen(Offset(SUN_X, SUN_Y - HEART_MID)) - tap).getDistance() <= maxOf(36.dp.toPx(), 18f * u)) {
                        onTapSun()
                        return null
                    }
                    val lobe = lobes.filter { selectedId < 0 || it.id == selectedId }.minByOrNull { (screen(lobeWorldPos(it.slot)) - tap).getDistance() }
                    if (lobe != null && (screen(lobeWorldPos(lobe.slot)) - tap).getDistance() <= maxOf(36.dp.toPx(), 14f * u * nodeScale(z))) {
                        onTapLobe(lobe.id)
                        return lobe
                    }
                    // The main brain is the core at the world origin (inside the r35 ring).
                    if (selectedId < 0 && tap.minus(screen(Offset.Zero)).getDistance() <= maxOf(36.dp.toPx(), 30f * u)) {
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
                        if (hit is Lobe) onDoubleTapLobe(hit.id)
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
        val sp = spark
        renderer.draw(time, cam.x, cam.y, cam.scale, layout, selIdx, sunSv, sunSel, sp?.k ?: -1, if (sp == null) 0f else ((time - sp.t0) / sp.dur).coerceIn(0f, 1f))
        drawIntoCanvas { renderer.blit(it, px) }

        labels.draw(this, renderer, cam.x, cam.y, cam.scale, selIdx, sunSel, sunSv)
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
            .pixelCard(fill = Px.Surface, border = Color(0xFFF596E1), shadow = false, notch = 4.dp)
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
        sync.error?.let { Text(it, Modifier.padding(top = 4.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Color(0xFFF596E1))) }
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
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(fontFamily = PixelFont, fontSize = 11.sp, lineHeight = 18.sp, color = Px.Text),
        cursorBrush = SolidColor(Px.Magenta),
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
private fun NameDialog(
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    initial: String = "",
    submit: String = "GROW",
    onDelete: (() -> Unit)? = null,
    onAddItem: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    fun submit() {
        if (text.isNotBlank()) onSubmit(text)
        onDismiss()
    }
    PixelDialog(onDismiss) {
        Text(title, style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(16.dp))
        PixelField(text, { text = it.take(24) }, hint, onDone = ::submit)
        Spacer(Modifier.height(18.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            onDelete?.let { PixelButton("DELETE", it) }
            onAddItem?.let { PixelButton("ITEM", { it(); onDismiss() }) }
            PixelButton("CANCEL", onDismiss)
            PixelButton(submit, ::submit, primary = true)
        }
    }
}
