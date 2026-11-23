package app.vesper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
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
import app.vesper.LearnViewModel
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
internal class Rng(seed: Int) {
    private var s = seed
    fun next(): Float {
        s += 0x6D2B79F5
        var t = (s xor (s ushr 15)) * (1 or s)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble().div(4294967296.0).toFloat()
    }
}

/** A task's node: its radius in world units at zoom 1. */
internal const val TASK_NODE_R = 4.5f

/** The dp per world unit a phone's home view settles near: the brains' own names, which every note keeps off, are planned at it. */
internal const val HOME_K = 1.2f
internal const val MOON_HALF = 11f // world units: a goal moon (5.5 low-res pixels each way) at the home zoom
/** shortcut: only this many goals hang round the learn brain on the map (the learn view has them all); raise it if the map gets room. */
internal const val GOAL_CAP = 10

/** A task's diamond at home (world), with its twinkle. */
internal fun taskNodeBox(p: Offset) = floatArrayOf(p.x - 5f, p.y - 5f, p.x + 5f, p.y + 5f)
/** A goal's moon at home (world). */
internal fun moonBox(p: Offset) = floatArrayOf(p.x - MOON_HALF, p.y - MOON_HALF, p.x + MOON_HALF, p.y + MOON_HALF)
/** The names on the main map a task or goal keeps off (world boxes, at the home view's [HOME_K]): the vault's, the learn brain's and the tasks brain's. */
internal fun labelBoxes() = listOf(floatArrayOf(SUN_X - 29f / HOME_K, SUN_Y + 12f, SUN_X + 29f / HOME_K, SUN_Y + 12f + LEARN_LABEL_H / HOME_K)) +
    listOf(LEARN_AT, TASKS_AT).map { learnLabelBox(it, HOME_K) }
/**
 * Everything else fixed on the main map that a task or goal must keep off (world boxes): the core, the heart with its nerve, the nerves to
 * the learn and tasks brains, both brains, a small circle round each reserved spot, the note sparks.
 */
internal fun hardBoxes(noteAt: List<Offset>): List<FloatArray> {
    val out = arrayListOf(
        floatArrayOf(-40f, -40f, 40f, 40f),
        floatArrayOf(-11f, 36f, 11f, SUN_Y - HEART_MID),
        floatArrayOf(SUN_X + HEART_BOX[0], SUN_Y + HEART_BOX[1], SUN_X + HEART_BOX[2], SUN_Y + HEART_BOX[3]),
        learnBrainBox(LEARN_AT), learnBrainBox(TASKS_AT),
    )
    for (q in RESERVED_AT) out.add(floatArrayOf(q.x - 11f, q.y - 11f, q.x + 11f, q.y + 11f))
    for (b in listOf(LEARN_AT, TASKS_AT)) for (s in 0..9) { val f = (38f + s * 6f) / RING_R; out.add(floatArrayOf(b.x * f - 4f, b.y * f - 4f, b.x * f + 4f, b.y * f + 4f)) }
    for (q in noteAt) out.add(floatArrayOf(q.x - 9f, q.y - 9f, q.x + 9f, q.y + 9f))
    if (noteAt.isEmpty()) out.add(floatArrayOf(-160f / HOME_K, 42f, 160f / HOME_K, 44f + 14f / HOME_K)) // the "double-tap the brain" hint under it
    return out
}
internal fun fixedBoxes(noteAt: List<Offset>) = hardBoxes(noteAt) + labelBoxes()

private fun hits(a: FloatArray, b: FloatArray) = a[0] < b[2] + 1.5f && b[0] < a[2] + 1.5f && a[1] < b[3] + 1.5f && b[1] < a[3] + 1.5f

/** The flower's first ring round a small brain (world radius), the step out to the next, and the room each node gets along a ring. */
internal const val PETAL_R0 = 28f
internal const val PETAL_STEP = 15f
private const val PETAL_GAP = 15f

/** 0 until [m] in a spread-out order (golden-ratio steps), so a ring that is only partly filled is still filled all round. */
private fun spread(m: Int): List<Int> {
    val out = LinkedHashSet<Int>(); var q = 0
    while (out.size < m) { out.add(((q * 0.618034f) % 1f * m).toInt().coerceIn(0, m - 1)); q++ }
    return out.toList()
}

/**
 * [n] items in a flower round the small brain at [c]: slots on concentric rings ([PETAL_R0] out by [PETAL_STEP]), each ring's evenly
 * spaced (odd rings turned half a step). The heart, the main brain and its nerves close one side of each small brain, so the slots fill
 * nearest first by a distance that counts height double: the half flower that is left grows about as wide as it is tall. Item [i] (in order,
 * so a later item never moves an earlier one) takes the first slot whose footprint ([foot]: its diamond or moon) touches nothing in [fixed]
 * or the earlier items and stays within [minX]..[maxX] and a phone-wide home view. The flowers are decoration: no names, no taps. The slots turn the other way round a brain
 * right of x = 0, so the learn brain's flower mirrors the tasks brain's. With no slot left (hundreds of items) it falls back to a column under [c].
 */
internal fun ringPlace(n: Int, c: Offset, foot: (Offset) -> FloatArray, fixed: List<FloatArray>, minX: Float = -1e9f, maxX: Float = 1e9f): List<Offset> {
    val taken = ArrayList(fixed)
    val base = atan2(c.y, c.x); val dir = if (c.x < 0f) 1f else -1f
    val slots = ArrayList<Pair<Float, Float>>() // (angle, radius), in the order they are tried
    for (k in 0 until 20) {
        val r = PETAL_R0 + k * PETAL_STEP
        val m = max(6, (TAU * r / PETAL_GAP).toInt())
        for (j in spread(m)) slots.add(base + dir * (j + 0.5f * (k % 2)) * TAU / m to r)
    }
    val order = slots.withIndex().sortedBy { (q, s) -> hypot(cos(s.first) * s.second, 1.3f * sin(s.first) * s.second) + q * 1e-4f }.map { it.value }
    val out = ArrayList<Offset>()
    var full = false
    for (i in 0 until n) {
        var pick: Offset? = null
        search@ for (xMax in if (full) floatArrayOf() else X_MAX) for ((a, r) in order) {
            val p = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
            val f = foot(p)
            if (f[0] >= max(-xMax, minX) && f[2] <= min(xMax, maxX) && taken.none { hits(f, it) }) { pick = p; break@search }
        }
        if (pick == null) full = true
        val sp = pick ?: Offset(c.x, c.y + PETAL_R0 + 20 * PETAL_STEP + 22f * i)
        taken.add(foot(sp)); out.add(sp)
    }
    return out
}

/** The goals keep right of this x and the tasks left of its mirror, so each half of the map is its own brain's. */
internal const val GOAL_MIN_X = 4f

/** How far sideways (world) a task's diamond or a goal's moon may sit: a phone-wide home view first, wider only when nothing else fits. */
internal val X_MAX = floatArrayOf(150f, 220f, 400f)

/**
 * Where the player dragged pinned papers on the board: how far (world units) each sits from its own spot, by the note's uid, saved per
 * device in [app.vesper.Settings] (no DB, no sync). The brains, the board and the task and goal flowers never move.
 */
internal object NoteMoves {
    const val KEY = "noteMoves"
    val d = HashMap<String, Offset>()
    operator fun get(uid: String) = d[uid] ?: Offset.Zero
    fun move(uid: String, dx: Float, dy: Float) { d[uid] = get(uid) + Offset(dx, dy) }
    fun load(s: app.vesper.Settings) {
        d.clear()
        for (e in s.getString(KEY).orEmpty().split(';')) {
            val p = e.split(',')
            val x = p.getOrNull(1)?.toFloatOrNull(); val y = p.getOrNull(2)?.toFloatOrNull()
            if (p.size == 3 && p[0].isNotEmpty() && x != null && y != null && x.isFinite() && y.isFinite()) d[p[0]] = Offset(x.coerceIn(-2000f, 2000f), y.coerceIn(-2000f, 2000f))
        }
    }
    fun save(s: app.vesper.Settings) = s.putString(KEY, d.entries.joinToString(";") { "${it.key},${it.value.x},${it.value.y}" })
    fun reset(s: app.vesper.Settings) { d.clear(); save(s) }
    /** Forgets the notes not in [uids] (burned ones); true when any was forgotten. */
    fun keepOnly(uids: Set<String>) = d.keys.retainAll(uids)
}

/** At most this many of the newest main-brain notes hang off the main brain as tiny nodes (no names); the card has them all. */
internal const val NOTE_CAP = 40
private const val NOTE_R0 = 52f // the first ring's radius round the main brain, clear of its r35 ring
private const val NOTE_R_STEP = 16f
private const val NOTE_GAP = 16f // along a ring between neighbours

/**
 * Where [n] note nodes sit relative to the main brain: rings round it over the top and sides only (the heart, learn and tasks
 * brains and the task names are all below), newest on the inner ring, spread by the golden ratio so a few notes sit evenly and
 * a full ring is even. [seeds] (the notes' uids) nudge each node's distance so the strands differ in length.
 */
internal fun noteSpots(seeds: List<String>): List<Offset> {
    val out = ArrayList<Offset>()
    val span = PI_F + 0.3f // from just under the left side, over the top, to just under the right
    var ring = 0
    while (out.size < seeds.size) {
        val r = NOTE_R0 + ring * NOTE_R_STEP
        val m = min((span * r / NOTE_GAP).toInt(), seeds.size - out.size)
        for (q in 0 until m) {
            val f = (q * 0.618034f + ring * 0.37f) % 1f
            val a = -PI_F - 0.15f + f * span
            val rr = r + (Rng(seeds[out.size].hashCode()).next() - 0.5f) * 4f
            out.add(Offset(cos(a) * rr, sin(a) * rr))
        }
        ring++
    }
    return out
}

/** Paper [i]'s cell (l, t, r, b, world, from the board's middle): its own random spot, slid where the player dragged it but never off the cork. */
internal fun Layout.paperCell(i: Int): FloatArray {
    val q = paperAt[i]; val o = paperOffset(q, NoteMoves[pinned[i].uid])
    return floatArrayOf(q[0] + o.x, q[1] + o.y, q[2] + o.x, q[3] + o.y)
}
/** The topmost (newest) paper under the world point [w], or -1. */
internal fun Layout.paperUnder(w: Offset): Int = pinned.indices.firstOrNull { val q = paperCell(it); w.x - boardAt.x in q[0]..q[2] && w.y - boardAt.y in q[1]..q[3] } ?: -1
/**
 * The note a tap at the world point [w] opens, with where it sits (world): the topmost paper under it, else the nearest main-brain spark
 * within [reach] (world). The task and goal flowers are decoration: a tap on them is never a hit.
 */
internal fun Layout.noteHit(w: Offset, reach: Float): Pair<Idea, Offset>? {
    paperUnder(w).takeIf { it >= 0 }?.let { i -> return pinned[i] to boardAt + paperCell(i).let { Offset((it[0] + it[2]) / 2f, (it[1] + it[3]) / 2f) } }
    return notes.indices.minByOrNull { (noteAt[it] - w).getDistance() }?.takeIf { (noteAt[it] - w).getDistance() <= reach }?.let { notes[it] to noteAt[it] }
}
/** [o] clamped so the paper in [cell] stays on the cork. */
internal fun paperOffset(cell: FloatArray, o: Offset) = Offset(
    o.x.coerceIn(-BOARD_W / 2f + BOARD_FRAME - cell[0], BOARD_W / 2f - BOARD_FRAME - cell[2]),
    o.y.coerceIn(-BOARD_H / 2f + BOARD_FRAME - cell[1], BOARD_H / 2f - BOARD_FRAME - cell[3]),
)

/** Nodes stop growing at this zoom, so zooming further opens the web up instead of blowing the sprites up. */
internal const val NODE_ZOOM = 2f
internal fun nodeScale(z: Float) = min(1f, NODE_ZOOM / z)

private const val HOME_ZOOM = 0.85f
/** As far out as a pinch goes; the overview fit stops at the first zoom (from [NODE_ZOOM] down) that holds the whole map, board and all. */
private const val MIN_ZOOM = 0.3f
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

/** The vault sun: one fixed node (not a lobe), centered below the brain (x = 0). */
internal const val SUN_X = 0f
internal const val SUN_Y = 128f
internal const val SUN_BEND = 8f
private const val HEART_SCALE = 0.85f // sprite is 26x38, so about 22x32 world units (lobe: 24x19.5); its base sits on SUN_Y
internal const val HEART_MID = 14f // world units from the heart's base up to its middle, for glow, keep-out and tap
/** The vault heart's middle: fixed, straight under the main brain. */
internal val HEART_AT = Offset(SUN_X, SUN_Y - HEART_MID)

/** The pinned-notes corkboard: its size (world units), centred under the main brain (x = 0) and always below every task, goal and name. */
internal const val BOARD_W = 176f
internal const val BOARD_H = 92f
internal const val BOARD_FRAME = 6f // the chunky frame
private const val BOARD_PAD = 4f // cork between the frame and the papers
private const val BOARD_GAP = 4f // between papers
internal const val BOARD_MAX = 12 // papers shown, scattered (overlapping once the cork is full, the newest on top); the rest are a "+N" tag on the frame
internal const val BOARD_MIN_TOP = SUN_Y + 58f // never higher than this: well under the vault heart's name
private const val BOARD_CLEAR = 8f // the gap under the lowest task or goal
/** The board at [c] (l, t, r, b, world), with the "+N" tag that hangs a little under the frame. */
internal fun boardBox(c: Offset) = floatArrayOf(c.x - BOARD_W / 2f, c.y - BOARD_H / 2f, c.x + BOARD_W / 2f, c.y + BOARD_H / 2f + 6f)
/** Where the board sits for tasks at [taskBottoms] and goals at [goalBottoms] (their footprints' bottoms): under all of them. */
internal fun boardHomeFor(bottoms: List<Float>): Offset = Offset(0f, max(BOARD_MIN_TOP, (bottoms.maxOrNull() ?: 0f) + BOARD_CLEAR) + BOARD_H / 2f)
/** A paper's size on the cork (world). */
internal const val PAPER_W = 48f
internal const val PAPER_H = 32f
/**
 * Paper cells (l, t, r, b, world, from the board's middle) for papers [uids], oldest first: each at a random spot on the cork seeded by its
 * uid (the same on every device and every start): of its 200 tries, one that overlaps no earlier paper (the nearest an edge of the cork),
 * else the one that overlaps them least.
 */
internal fun paperSpots(uids: List<String>): List<FloatArray> {
    val m = BOARD_FRAME + 1f
    val x0 = -BOARD_W / 2f + m; val x1 = BOARD_W / 2f - m - PAPER_W
    val y0 = -BOARD_H / 2f + m; val y1 = BOARD_H / 2f - m - PAPER_H
    val out = ArrayList<FloatArray>()
    for (u in uids) {
        val r = Rng(u.hashCode() * 13 + 5)
        fun overlap(c: FloatArray) = out.sumOf { o -> (max(0f, min(c[2], o[2]) - max(c[0], o[0])) * max(0f, min(c[3], o[3]) - max(c[1], o[1]))).toDouble() }
        // least overlap first; among the free tries the one nearest an edge of the cork, so the middle stays open for the next papers
        fun edge(c: FloatArray) = minOf(c[0] - x0, x1 - c[0], c[1] - y0, y1 - c[1])
        out.add(List(200) { val x = x0 + r.next() * (x1 - x0); val y = y0 + r.next() * (y1 - y0); floatArrayOf(x, y, x + PAPER_W, y + PAPER_H) }.minBy { overlap(it) * 1000.0 + edge(it) })
    }
    return out
}
/** How far (world) paper [i] has swayed sideways at [t] seconds. */
internal fun paperSway(t: Float, i: Int) = 0.7f * sin(t * 0.7f + i * 1.9f)
/** A paper's own tilt (rise per run, about 1.5 to 5 degrees either way) and colour, seeded by its uid so they never change. */
internal fun paperTilt(uid: String): Float = Rng(uid.hashCode() * 7 + 3).let { r -> (if (r.next() < 0.5f) -1f else 1f) * (0.025f + 0.06f * r.next()) }
internal fun paperRgb(uid: String): Int = PAPER_RGB[(uid.hashCode() and 0x7FFFFFFF) % PAPER_RGB.size]

/** Whether the world point [w] is on the pinned board at [c] (frame, "+N" tag and all). */
internal fun onBoard(c: Offset, w: Offset) = boardBox(c).let { b -> w.x in b[0]..b[2] && w.y in b[1]..b[3] }

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

/** The main map: the tasks (task [i] is [tasks][i], its node at [tx], [ty] on a vein bowed by [tbend]) and the seeded rot decoration. */
internal class Layout(
    val tasks: List<Idea>, val tx: FloatArray, val ty: FloatArray, val tbend: FloatArray,
    val dust: FloatArray,      // x, y, ph, b
    val stains: FloatArray,    // x, y, r
    val cracks: Array<FloatArray>, // x0, y0, x1, y1, ...
    val specks: FloatArray,    // x, y, colour index
    val spores: FloatArray,    // x, y, ph, sp
    val drips: FloatArray,     // x, y, n, len, owner (-1 none)
    val notes: List<Idea> = emptyList(), // the newest main-brain notes shown round the main brain, with their spots relative to it
    val noteAt: List<Offset> = emptyList(),
    val goals: List<Goal> = emptyList(), // the goal moons round the learn brain (at most [GOAL_CAP]), their spots (home positions) and brightness
    val goalAt: List<Offset> = emptyList(),
    val goalB: FloatArray = FloatArray(goals.size) { 1f },
    val pinned: List<Idea> = emptyList(), // the newest pinned notes (at most [BOARD_MAX]), newest first, one paper each at [paperAt]
    val pinnedTotal: Int = 0,
    val boardAt: Offset = Offset(0f, BOARD_MIN_TOP + BOARD_H / 2f), // the board's home: under everything above
    val paperAt: List<FloatArray> = paperSpots(pinned.reversed().map { it.uid }).reversed(),
)

/** The main map for [tasks]: open ones first, then done ones; each sits where its own seed puts it, so marking one done moves nothing. */
internal fun buildLayout(tasks: List<Idea>, notes: List<Idea> = emptyList(), goals: List<Goal> = emptyList(), pinned: List<Idea> = emptyList()): Layout {
    val shown = notes.take(NOTE_CAP)
    val noteAt = noteSpots(shown.map { it.uid })
    val order = tasks.sortedWith(compareBy({ it.done }, { it.id }))
    val n = order.size
    val gs = goals.take(GOAL_CAP)
    val fixed = fixedBoxes(noteAt)
    val goalAt = ringPlace(gs.size, LEARN_AT, ::moonBox, fixed, minX = GOAL_MIN_X)
    val byId = order.sortedBy { it.id }
    val spots = ringPlace(byId.size, TASKS_AT, ::taskNodeBox, fixed, maxX = -GOAL_MIN_X)
    val board = boardHomeFor(spots.map { taskNodeBox(it)[3] } + goalAt.map { moonBox(it)[3] })
    val spotOf = byId.indices.associate { byId[it].uid to spots[it] }
    // the flowers are decoration: their diamonds and moons sit where [ringPlace] put them, with no names and no taps
    val tx = FloatArray(n); val ty = FloatArray(n); val tbend = FloatArray(n)
    order.forEachIndexed { j, task ->
        val p = spotOf.getValue(task.uid)
        tx[j] = p.x; ty[j] = p.y
        val h = task.uid.hashCode() * 31 + 7
        tbend[j] = (if (Rng(h + 1).next() < 0.5f) 1f else -1f) * (4f + Rng(h).next() * 4f)
    }
    val now = System.currentTimeMillis()

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
        specks[i * 3] = (r.next() - 0.5f) * 220f; specks[i * 3 + 1] = (r.next() - 0.5f) * 340f
        val c = r.next()
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

    // drips from the core
    val drips = floatArrayOf(0f, 18f, 14f, 55f, -1f)

    return Layout(order, tx, ty, tbend, dust, stains, cracks, specks, spores, drips, shown, noteAt, gs, goalAt, FloatArray(gs.size) { goalBrightness(gs[it].updatedAt, now) }, pinned.take(BOARD_MAX), pinned.size, board)
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
internal val PAPER_RGB = intArrayOf(0xE6DCFF, 0xF3EEFF, 0xD6C8FF)
private val BOARD_SPECK_RGB = intArrayOf(0x120A22, 0x3A2570, 0x4A3478, 0x1E1038)
/** The cork's speckles (x, y relative to the board's middle, colour index): fixed, so the board never shimmers. */
private val BOARD_SPECKS = FloatArray(170 * 3).also { out ->
    val r = Rng(4242)
    val m = BOARD_FRAME + 1f
    for (k in 0 until 170) {
        out[k * 3] = -BOARD_W / 2f + m + r.next() * (BOARD_W - 2 * m - 1.4f)
        out[k * 3 + 1] = -BOARD_H / 2f + m + r.next() * (BOARD_H - 2 * m - 1.4f)
        out[k * 3 + 2] = (r.next() * BOARD_SPECK_RGB.size).toInt().coerceAtMost(BOARD_SPECK_RGB.size - 1).toFloat()
    }
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

    /** The learn brain's moon count; -1 = no learn brain drawn. */
    var learnMoons = -1
    /** The daily brain's brightness (dim while an item is undone today, 1 when all are done); -1 = no daily brain drawn. */
    var daily = -1f
    /** Whether the bible brain (top right) is drawn. */
    var bible = false
    /** The uid of the paper being dragged, or null: it gets a bright ring so it reads as lifted. */
    var lifted: String? = null

    private val cv get() = pix.canvas
    private val spritePaint = pixPaint()
    private val ditherPaint = makeDitherPaint(0.26f * ROT)
    private val vignette = pixPaint()

    // per-frame state
    var t = 0f
        private set
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
     * An organic nerve from ([x1], [y1]) to ([x2], [y2]): [strands] pixel threads bowed by [bend], pulsing with the beat, wobbling a little,
     * with dark dead specks; no beads. It leaves [startGap] clear at the brain it grows from and stops [endGap] short of the far end.
     */
    private fun vein(x1: Float, y1: Float, x2: Float, y2: Float, bend: Float, strands: Int, seed: Float, alpha: Float, startGap: Float, endGap: Float, hiRgb: Int = 0xB69AFF) {
        if (alpha <= 0f || !onScreen(x1, y1, x2, y2, 30f)) return
        val len = hypot(x2 - x1, y2 - y1).let { if (it == 0f) 1f else it }
        val nx = -(y2 - y1) / len; val ny = (x2 - x1) / len
        for (st in 0 until strands) {
            val b = bend + (st - (strands - 1) / 2f) * (1f + 2.5f * ROT)
            val n = ceil(len * z * 1.2f).toInt().coerceIn(1, 3000)
            for (i in 0..n) {
                val s = i / n.toFloat()
                veinPoint(sp, x1, y1, x2, y2, b, s)
                if (hypot(sp[0] - x1, sp[1] - y1) < startGap || hypot(sp[0] - x2, sp[1] - y2) < endGap) continue
                val wob = sin(s * 11f + st * 2.1f + seed + t * 0.6f) * 1.6f * ROT * (1f - abs(s - 0.5f))
                val dead = sin(s * 37f + seed * 3f + st) > 0.93f
                val rgb = if (dead) 0x14061A else mixRgb(0x5A3FA0, hiRgb, beat(s))
                dot(rnd(sx(sp[0] + nx * wob)), rnd(sy(sp[1] + ny * wob)), 1, 1, rgb, alpha)
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

    /** Where the paper [uid] being dragged sits (world), if it is on the board. */
    private fun liftedAt(L: Layout, uid: String): Offset? =
        L.pinned.indexOfFirst { it.uid == uid }.takeIf { it >= 0 }?.let { L.paperCell(it).let { q -> L.boardAt + Offset((q[0] + q[2]) / 2f, (q[1] + q[3]) / 2f) } }

    /** Lub-dub as it arrives along the veins, in quarter steps so the scaled sprite stays crisp. */
    private fun heartBeat() = round(max(beat(1f), 0.6f * beat(1.16f)) * 4f) / 4f

    /** Glow behind the vault heart; [sv] 0 = alive, 1 = dead. */
    private fun sun(sv: Float, selected: Boolean, d: Float) {
        val l = 1f - sv
        val ga = (0.35f + 0.3f * heartBeat()) * l * d
        val h = HEART_AT
        glowAt(h.x, h.y, 34f, 0x8A6CF0, ga)
        if (selected) glowAt(h.x, h.y, 34f, 0x8A6CF0, ga)
    }

    /** The heart swells with the pulse and stops beating as it dies; drawn after the dither pass so the checker doesn't show on it. */
    private fun heart(sv: Float, d: Float) {
        if (d == 0f) return // sprites draw even at alpha 0, so skip them when hidden
        val l = 1f - sv
        val s = HEART_SCALE * (1f + 0.14f * heartBeat() * l)
        val h = HEART_AT
        val cy = h.y + HEART_MID - heartSprite.height * s / 2f // base stays put while it swells
        sprite(heartDeadSprite, h.x, cy, s, d, false)
        sprite(heartSprite, h.x, cy, s, l * d, false) // the live heart art, recoloured to the app purples
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

    /** The small learn brain: a mirrored core in a dotted ring. [s] its node scale. */
    private fun learnBrain(x: Float, y: Float, s: Float, a: Float) {
        if (a <= 0f) return
        glowAt(x, y, 22f * s, 0x7A4ACF, (0.2f + 0.3f * beat(0.4f)) * a)
        glowAt(x, y, 14f * s, 0xB69AFF, 0.25f * a)
        ring(x, y, 12.5f * s, 30, t * 1.5f, 0xB69AFF, 0.7f * a)
        sprite(coreSprite, x, y, LEARN_SCALE * s, a, true)
    }

    /** A goal's moon on the map: the purple pixel moon, small, in a soft glow; [b] its brightness. */
    private fun goalMoon(x: Float, y: Float, b: Float, a: Float) {
        if (a <= 0f) return
        glowAt(x, y, MOON_HALF, 0x8F7BFF, 0.3f * b * a)
        pix.goalMoon(rnd(sx(x)), rnd(sy(y)), moonScale(z), b * a)
    }

    /** The small tasks brain, the learn brain's twin without moons. */
    private fun tasksBrain(x: Float, y: Float, s: Float, a: Float) {
        if (a <= 0f) return
        glowAt(x, y, 22f * s, 0x7A4ACF, (0.2f + 0.3f * beat(0.4f)) * a)
        glowAt(x, y, 14f * s, 0xB69AFF, 0.25f * a)
        ring(x, y, 12.5f * s, 30, -t * 1.5f, 0xB69AFF, 0.7f * a)
        sprite(coreSprite, x, y, LEARN_SCALE * s, a, false)
    }

    /** The small daily brain, the tasks brain's twin at [b] brightness (its glow and ring dim with it). */
    private fun dailyBrain(x: Float, y: Float, s: Float, b: Float, a: Float) {
        if (a <= 0f) return
        glowAt(x, y, 22f * s, 0x7A4ACF, (0.2f + 0.3f * beat(0.4f)) * a * b)
        glowAt(x, y, 14f * s, 0xB69AFF, 0.25f * a * b)
        ring(x, y, 12.5f * s, 30, t * 1.2f, 0xB69AFF, 0.7f * a * b)
        sprite(coreSprite, x, y, LEARN_SCALE * s, a * (0.35f + 0.65f * b), true)
    }

    /** The small bible brain: the daily brain's mirror, always lit. */
    private fun bibleBrain(x: Float, y: Float, s: Float, a: Float) {
        if (a <= 0f) return
        glowAt(x, y, 22f * s, 0x7A4ACF, (0.2f + 0.3f * beat(0.4f)) * a)
        glowAt(x, y, 14f * s, 0xB69AFF, 0.25f * a)
        ring(x, y, 12.5f * s, 30, -t * 1.2f, 0xB69AFF, 0.7f * a)
        sprite(coreSprite, x, y, LEARN_SCALE * s, a, false)
    }

    /** A main-brain note: a tiny twinkling star, [s] its node scale; a done one a grey dot. */
    private fun noteNode(x: Float, y: Float, done: Boolean, ph: Float, s: Float, a: Float) {
        if (a <= 0f) return
        if (done) { dot(rnd(sx(x)), rnd(sy(y)), 1, 1, 0x6F6880, 0.8f * a); return }
        val tw = 0.5f + 0.5f * sin(t * 1.3f + ph)
        glowAt(x, y, 4f * s, 0x8F7BFF, (0.2f + 0.2f * tw) * a)
        twinkle(sx(x), sy(y), if (tw > 0.7f) 2 else 1, 0xD6C8FF, (0.8f + 0.2f * tw) * a)
    }

    /** The pinned-notes corkboard: a chunky purple frame (light top-left edge, dark bottom-right), a speckled cork board, and a tilted lilac paper under a pin for each pinned note (their words are drawn over it by [BrainLabels]). */
    private fun board(L: Layout, a: Float) {
        if (a <= 0f) return
        val c = L.boardAt
        fun wr(x0: Float, y0: Float, x1: Float, y1: Float, rgb: Int, al: Float) {
            val ax = rnd(sx(x0)); val ay = rnd(sy(y0))
            dot(ax, ay, max(1, rnd(sx(x1)) - ax), max(1, rnd(sy(y1)) - ay), rgb, al)
        }
        val l = c.x - BOARD_W / 2f; val tp = c.y - BOARD_H / 2f; val r = c.x + BOARD_W / 2f; val b = c.y + BOARD_H / 2f
        val f = BOARD_FRAME
        glowAt(c.x, c.y, 80f, 0x7A4ACF, 0.12f * a)
        wr(l + 2f, tp + 2f, r + 2f, b + 2f, 0x030105, 0.6f * a) // drop shadow
        wr(l, tp, r, b, 0x8A6CF0, a) // the frame
        wr(l, tp, r, tp + 2f, 0xC9B6FF, a); wr(l, tp, l + 2f, b, 0xC9B6FF, a) // lit top and left edges
        wr(l + 2f, b - 2f, r, b, 0x4A3478, a); wr(r - 2f, tp + 2f, r, b, 0x4A3478, a) // shaded bottom and right edges
        wr(l + f - 1f, tp + f - 1f, r - f + 1f, b - f + 1f, 0x0B0618, a) // the cork sits sunk in it
        wr(l + f, tp + f, r - f, b - f, 0x2A1858, a) // the cork
        for (k in 0 until BOARD_SPECKS.size / 3) {
            val sx0 = c.x + BOARD_SPECKS[k * 3]; val sy0 = c.y + BOARD_SPECKS[k * 3 + 1]
            wr(sx0, sy0, sx0 + 1.4f, sy0 + 1.4f, BOARD_SPECK_RGB[BOARD_SPECKS[k * 3 + 2].toInt()], a)
        }
        for (i in L.pinned.indices.reversed()) { // oldest first: the newest lies on top
            val q = L.paperCell(i); val sw = paperSway(t, i); val tilt = paperTilt(L.pinned[i].uid)
            val ax0 = rnd(sx(c.x + q[0] + sw)); val ax1 = max(ax0 + 2, rnd(sx(c.x + q[2] + sw)))
            val ay0 = rnd(sy(c.y + q[1])); val ah = max(2, rnd(sy(c.y + q[3])) - ay0)
            val mid = (ax0 + ax1) / 2f
            val rgb = paperRgb(L.pinned[i].uid)
            for (px in ax0 until ax1) dot(px + 1, ay0 + rnd((px + 0.5f - mid) * tilt) + 1, 1, ah, 0x0B0618, 0.6f * a) // its shadow
            for (px in ax0 until ax1) {
                val y = ay0 + rnd((px + 0.5f - mid) * tilt)
                dot(px, y, 1, ah, rgb, a)
                dot(px, y + ah - 1, 1, 1, 0xA79CC4, a) // the bottom edge a little darker
            }
            val m = mid.toInt()
            dot(m - 1, ay0 + 1, 3, 1, 0x6A4FC0, 0.8f * a) // the pin's shade
            dot(m - 1, ay0 - 1, 2, 2, 0xFFFFFF, a) // the pin
        }
        if (L.pinnedTotal > L.pinned.size) { // the "+N" tag on the frame's bottom right edge
            wr(r - 34f, b - 6f, r - 5f, b + 6f, 0xC9B6FF, a)
            wr(r - 33f, b - 5f, r - 6f, b + 5f, 0x1E1038, a)
        }
    }

    /** A task's node: a small pixel diamond, white-hearted and twinkling; a done one dim grey. [s] its node scale. */
    private fun taskNode(x: Float, y: Float, done: Boolean, ph: Float, s: Float, a: Float) {
        if (a <= 0f) return
        val u = max(1, rnd(z * s * 0.75f))
        val tw = 0.5f + 0.5f * sin(t * 1.3f + ph)
        if (!done) glowAt(x, y, 6f * s, 0x8F7BFF, (0.25f + 0.2f * tw) * a)
        val cx = rnd(sx(x)); val cy = rnd(sy(y))
        for (dy in -2..2) for (dx in -(2 - abs(dy))..(2 - abs(dy))) {
            val ring = abs(dx) + abs(dy)
            val rgb = when {
                done -> if (ring == 2) 0x4A3A5A else 0x6F6880
                ring == 2 -> 0xC9B6FF
                ring == 0 -> 0xFFFFFF
                else -> 0xE6DCFF
            }
            dot(cx + dx * u - u / 2, cy + dy * u - u / 2, u, u, rgb, (if (done) 0.8f else 0.85f + 0.15f * tw) * a)
        }
    }

    /**
     * One frame of the main map: the main brain with its nerves to the vault heart, the learn brain and the tasks brain, a free
     * diamond for every task round the tasks brain, a moon for every goal round the learn brain, and the pinned board under it all.
     */
    fun draw(time: Float, camX: Float, camY: Float, zoom: Float, L: Layout, sv: Float, sunSel: Boolean) {
        t = time; this.camX = camX; this.camY = camY; z = zoom
        beatPh = (t * 0.45f) % 1.7f
        val ns = nodeScale(z)
        val d = if (sunSel) 0.45f else 1f // the map dims behind the open vault
        // a fly into the learn brain fades the heart out early so it never fills the view, the other brains even sooner
        val m = Offset.Zero; val h = HEART_AT; val ln = LEARN_AT; val tb = TASKS_AT

        pix.fill(0x07030B)

        // fog
        for (i in 0 until 7) {
            glowAt(sin(t * 0.05f + i * 1.3f) * 80f, cos(t * 0.04f + i * 1.7f) * 150f, 80f, if (i % 2 == 1) 0x2A1A6A else 0x3A1A7A, 0.5f * ROT)
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

        // the rot: big rings and core aura, cracks, mold specks
        floatArrayOf(60f, 105f, 150f).forEachIndexed { i, r -> ring(m.x, m.y, r, 90, t * (if (i % 2 == 1) -1f else 1f), 0x39234C, 0.6f * (1f - ROT * 0.5f)) }
        glowAt(m.x, m.y, 90f, 0x4A2FA0, 0.3f)
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

        // the nerves: core to the heart, the learn brain and the tasks brain; tasks and goals hang free
        val va = d
        vein(m.x, m.y, h.x, h.y, SUN_BEND, 2, 9f, va, 33f, 16f, 0xD6C8FF)
        if (learnMoons >= 0) vein(m.x, m.y, ln.x, ln.y, 6f, 2, 21f, va, 33f, 14f * ns)
        vein(m.x, m.y, tb.x, tb.y, -6f, 2, 33f, va, 33f, 14f * ns)
        if (daily >= 0f) vein(m.x, m.y, DAILY_AT.x, DAILY_AT.y, 6f, 2, 45f, va, 33f, 14f * ns)
        if (bible) vein(m.x, m.y, BIBLE_AT.x, BIBLE_AT.y, -6f, 2, 57f, va, 33f, 14f * ns)

        // drips from the core
        if (ROT > 0.2f) {
            val fade = min(1f, (ROT - 0.2f) * 2f)
            val wh = rnd(z * 1.5f).coerceIn(1, 4)
            for (k in 0 until L.drips.size / 5) {
                val dx0 = m.x + L.drips[k * 5]; val dy0 = m.y + L.drips[k * 5 + 1]
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

        sun(sv, sunSel, 1f)

        // the main brain
        glowAt(m.x, m.y, 48f, 0x7A4ACF, 0.25f + 0.6f * beat(0.05f))
        glowAt(m.x, m.y, 36f, 0xB69AFF, 0.3f)
        ring(m.x, m.y, 30f, 48, t * 2f, 0xB69AFF, 0.85f)
        ring(m.x, m.y, 35f, 56, -t * 1.5f, 0x8A6CF0, 0.7f)
        sprite(coreSprite, m.x, m.y, 0.64f, 1f, false)
        if (learnMoons >= 0) {
            learnBrain(ln.x, ln.y, ns, d)
            for (i in L.goals.indices) goalMoon(L.goalAt[i].x, L.goalAt[i].y, L.goalB[i], d)
        }
        tasksBrain(tb.x, tb.y, ns, d)
        if (daily >= 0f) dailyBrain(DAILY_AT.x, DAILY_AT.y, ns, daily, d)
        if (bible) bibleBrain(BIBLE_AT.x, BIBLE_AT.y, ns, d)
        for (i in L.notes.indices) noteNode(m.x + L.noteAt[i].x, m.y + L.noteAt[i].y, L.notes[i].done, i * 1.7f, ns, d)
        for (i in L.tasks.indices) taskNode(L.tx[i], L.ty[i], L.tasks[i].done, L.tbend[i] * 7f, ns, d)

        // spores
        for (i in 0 until L.spores.size / 4) {
            val ph = L.spores[i * 4 + 2]; val sp = L.spores[i * 4 + 3]
            val x = L.spores[i * 4] + sin(t * 0.2f + ph) * 10f
            val y = L.spores[i * 4 + 1] - (t * sp + ph * 30f) % 60f + 30f
            val a = 0.4f + 0.6f * max(0f, sin(t * 2f + ph))
            dot(rnd(sx(x)), rnd(sy(y)), 1, 1, if (i % 3 != 0) 0x8A6CF0 else 0xB69AFF, a)
            if (i % 4 == 0) glowAt(x, y, 3f, 0x8A6CF0, a * 0.5f)
        }

        // 2x2 dither + vignette
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), ditherPaint)
        board(L, d)
        lifted?.let { uid -> liftedAt(L, uid)?.let { ring(it.x, it.y, 30f, 40, t * 3f, 0xE6DCFF, 0.9f) } }
        heart(sv, 1f)
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), vignette)
    }
}

/** What a tap on the main map lands on. */
internal enum class MapHit { Note, Board, Heart, Learn, Tasks, Daily, Bible, Brain, Empty, None }

/**
 * What a tap at the world point [w] hits ([double] for a double-tap), a world unit being [u] px, the nodes at scale [ns] and a dp
 * [dp] px; with the note and where it sits for a [MapHit.Note]. [daily]: the daily brain is on the map. The task and goal flowers
 * are decoration and never a hit. A double-tap that misses every brain and the board is [MapHit.None].
 */
internal fun Layout.mapHit(w: Offset, u: Float, ns: Float, dp: Float, double: Boolean, daily: Boolean, bible: Boolean = false): Pair<MapHit, Pair<Idea, Offset>?> {
    fun near(p: Offset, reachPx: Float) = (p - w).getDistance() * u <= reachPx
    val small = maxOf(30f * dp, 18f * u * ns)
    val core = maxOf(36f * dp, 30f * u) // the core, inside the r35 ring
    val board = onBoard(boardAt, w)
    if (double) return when {
        board -> MapHit.Board
        near(LEARN_AT, small) -> MapHit.Learn
        near(TASKS_AT, small) -> MapHit.Tasks
        daily && near(DAILY_AT, small) -> MapHit.Daily
        bible && near(BIBLE_AT, small) -> MapHit.Bible
        near(Offset.Zero, core) -> MapHit.Brain
        else -> MapHit.None
    } to null
    val note = noteHit(w, 16f * dp / u) // a paper or a spark
    return when {
        note != null -> MapHit.Note
        board -> MapHit.Board
        near(HEART_AT, maxOf(36f * dp, 18f * u)) -> MapHit.Heart
        near(LEARN_AT, small) -> MapHit.Learn
        near(TASKS_AT, small) -> MapHit.Tasks
        daily && near(DAILY_AT, small) -> MapHit.Daily
        bible && near(BIBLE_AT, small) -> MapHit.Bible
        near(Offset.Zero, core) -> MapHit.Brain
        else -> MapHit.Empty
    } to note
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
    /** Opens an item's card, growing from [from] (px in the root): [id] 0 starts a new one in [lobeId]. */
    onOpenIdea: (id: Long, lobeId: Long, branchId: Long?, from: Offset) -> Unit,
    /** Starts a new item in the first sub brain called [name] ("" = the loose notes, "Tasks", "Pinned"), made when missing, growing from [from]. */
    onNewIn: (name: String, from: Offset) -> Unit,
    /** The Brain's little sounds; the main brain's menu has the switch. */
    soundsOn: Boolean,
    onToggleSounds: () -> Unit,
    /** Where the player dropped the notes: save them; reset puts them all back on their own spots. */
    onSaveLayout: () -> Unit = {},
    onResetLayout: () -> Unit = {},
    vault: Vault,
    sync: SyncManager,
    appScope: CoroutineScope,
    goals: List<Goal>? = null,
    /** Bumped when the Brain tab is tapped while already shown: close the main brain's card or glide home. */
    homeSignal: Int = 0,
    learn: LearnViewModel? = null,
    /** The Daily checklist: add an item by name, tick or untick one, rename or delete one. */
    onDailyAdd: (String) -> Unit = {},
    onDailyDone: (Idea, Boolean) -> Unit = { _, _ -> },
    onRenameIdea: (Idea, String) -> Unit = { _, _ -> },
    onDeleteIdea: (Idea) -> Unit = {},
    /** The evening reminder's hour (null = off) and the chip that cycles it; null where there is no reminder (desktop). */
    remindAt: Int? = null,
    onRemind: (() -> Unit)? = null,
    /** The Bible canvas behind the top-right brain; null = no bible brain. */
    bible: app.vesper.BibleViewModel? = null,
) {
    val lobeList = lobes.orEmpty()
    val ideaList = ideas.orEmpty()
    val mainNotes = remember(lobeList, ideaList) { mainBrainNotes(lobeList, ideaList) }
    val pinned = remember(lobeList, ideaList) { pinnedNotes(lobeList, ideaList) }
    val tasks = remember(lobeList, ideaList) {
        val ids = lobeList.filter(::isTasks).mapTo(HashSet()) { it.id }
        ideaList.filter { it.lobeId in ids }
    }
    var homeTick by remember { mutableStateOf(0) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOff by remember { mutableStateOf(Offset.Zero) } // where this view sits in the root, for the cards' start
    val goalList = goals.orEmpty()
    // only pinned papers keep a dragged place: a burned or unpinned one forgets it, and so do the old task and goal places (2.7.6);
    // only once the notes have loaded, so a cold start forgets nothing
    LaunchedEffect(pinned, ideas) {
        if (ideas != null && lobes != null && NoteMoves.keepOnly(pinned.mapTo(HashSet()) { it.uid })) onSaveLayout()
    }
    // The main brain's card: open while set, grown from there (root px; unspecified = the middle).
    var cardFrom by remember { mutableStateOf<Offset?>(null) }
    var cardPinned by remember { mutableStateOf(false) } // the card lists the pinned notes, not the main brain's
    var cardClose by remember { mutableStateOf(0) }
    var seenHome by remember { mutableStateOf(homeSignal) }
    var addingGoal by remember { mutableStateOf(false) }
    val daily = remember(lobeList, ideaList) { app.vesper.dailyItems(lobeList, ideaList) }
    var dailyFrom by remember { mutableStateOf<Offset?>(null) } // the daily card: open while set, grown from there (root px)
    var dailyClose by remember { mutableStateOf(0) }
    var addingDaily by remember { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<Pair<Idea, Offset>?>(null) } // a daily item long-pressed, where (px in this view)
    var renamingItem by remember { mutableStateOf<Idea?>(null) }
    var burningItem by remember { mutableStateOf<Idea?>(null) }
    var goalMenu by remember { mutableStateOf<Pair<Goal, Offset>?>(null) } // a goal moon tapped in the learn world, where (px in this view)
    var renamingGoal by remember { mutableStateOf<Goal?>(null) }
    var burningGoal by remember { mutableStateOf<Goal?>(null) }

    val bibleCards = bible?.cards?.collectAsState()?.value.orEmpty()
    val bibleLinks = bible?.links?.collectAsState()?.value.orEmpty()
    // The worlds: 1 the learn brain's, 2 the tasks brain's lantern sky, 3 the bible canvas. [world] is where we are going, [shown] the one composed (it
    // stays through the fly-out); [fly] 0 the map, 1 inside, zooming from [flyAt] (px in this view, the tapped brain).
    var world by remember { mutableStateOf(0) }
    var shown by remember { mutableStateOf(0) }
    val fly = remember { Animatable(0f) }
    var flyAt by remember { mutableStateOf(Offset.Zero) }
    val flyScope = androidx.compose.runtime.rememberCoroutineScope()
    fun enter(w: Int, at: Offset) {
        if (world != 0) return
        world = w; shown = w; flyAt = at
        Sounds.play(Sfx.Open)
        flyScope.launch { fly.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    }
    fun leave() {
        if (world == 0) return
        world = 0
        Sounds.play(Sfx.Close)
        flyScope.launch { fly.animateTo(0f, tween(420, easing = FastOutSlowInEasing)); if (world == 0) shown = 0 }
    }
    val mapHidden by remember { derivedStateOf { shown != 0 && fly.value >= 1f } }
    LaunchedEffect(homeSignal) {
        if (homeSignal == seenHome) return@LaunchedEffect
        seenHome = homeSignal
        if (cardFrom != null) cardClose++ else if (dailyFrom != null) dailyClose++ else if (world != 0) leave() else homeTick++
    }

    var menu by remember { mutableStateOf<MenuAt?>(null) }
    var searching by remember { mutableStateOf(false) }
    val zoomedIn by remember { derivedStateOf { camera.scale > camera.home * 1.5f } }
    BackHandler(enabled = zoomedIn && world == 0, onBack = { homeTick++ })

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
        ) {
            val origin = TransformOrigin(if (viewSize.width > 0) flyAt.x / viewSize.width else 0.5f, if (viewSize.height > 0) flyAt.y / viewSize.height else 0.5f)
            if (!mapHidden) Box(Modifier.fillMaxSize().graphicsLayer {
                val e = fly.value; val k = 1f + 2.5f * e * e
                scaleX = k; scaleY = k; alpha = 1f - e; transformOrigin = origin
            }) {
                BrainCanvas(
                    tasks, camera, homeTick, sv, sunSel,
                    topInset = TITLE_H,
                    locked = world != 0,
                    learnMoons = goalList.size, hint = mainNotes.isEmpty(), mainNotes = mainNotes, goals = goalList,
                    onTapSun = { sunSel = true },
                    onTapBrain = { at -> sunSel = false; cardPinned = false; cardFrom = at + rootOff },
                    onTapBoard = { at -> sunSel = false; cardPinned = true; cardFrom = at + rootOff },
                    onDoubleTapBrain = { at -> sunSel = false; onNewIn("", at + rootOff) },
                    onDoubleTapTasks = { at -> sunSel = false; onNewIn("Tasks", at + rootOff) },
                    onDoubleTapLearn = { sunSel = false; addingGoal = true },
                    pinned = pinned,
                    onDoubleTapBoard = { at -> sunSel = false; onNewIn("Pinned", at + rootOff) },
                    onOpenTask = { t, at -> onOpenIdea(t.id, t.lobeId, t.branchId, at + rootOff) },
                    onTapEmpty = { sunSel = false; homeTick++ },
                    onMoved = onSaveLayout,
                    onTapLearn = { at -> sunSel = false; enter(1, at) },
                    onTapTasks = { at -> sunSel = false; enter(2, at) },
                    onTapDaily = { at -> sunSel = false; dailyFrom = at + rootOff },
                    onDoubleTapDaily = { sunSel = false; addingDaily = true },
                    daily = dailyBrightness(daily),
                    onTapBible = if (bible != null) ({ at -> sunSel = false; enter(3, at) }) else null,
                )
                ScreenTitle("Brain")
                if (!searching) Box(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 16.dp)) { SearchButton { searching = true } }
            }
            if (shown != 0) Box(Modifier.fillMaxSize().graphicsLayer {
                val e = fly.value; val k = 0.55f + 0.45f * e
                scaleX = k; scaleY = k; alpha = e; transformOrigin = origin
            }) {
                if (shown == 1) LearnWorld(
                    goalList, onBack = ::leave, onAdd = { addingGoal = true },
                    onGoal = { g, at -> goalMenu = g to at - rootOff },
                ) else if (shown == 3 && bible != null) BibleWorld(
                    bibleCards, bibleLinks, onBack = ::leave,
                    onAdd = bible::addCard,
                    onMove = { c, x, y -> bible.moveCard(c.id, x, y) },
                    onLock = { bible.setLocked(it.id, !it.locked) },
                    onRename = { c, n -> bible.renameCard(c.id, n) },
                    onKind = { c, k -> bible.setKind(c.id, k) },
                    onNote = { c, n -> bible.setNote(c.id, n) },
                    onDelete = bible::deleteCard,
                    onLink = bible::addLink,
                    onRenameLink = { l, n -> bible.renameLink(l.id, n) },
                    onDeleteLink = { bible.deleteLink(it.id) },
                ) else LanternWorld(
                    tasks, onBack = ::leave,
                    onAdd = { at -> onNewIn("Tasks", at) },
                    onOpen = { t, at -> onOpenIdea(t.id, t.lobeId, t.branchId, at) },
                )
            }
            cardFrom?.let { from ->
                BrainCard(
                    if (cardPinned) pinned else mainNotes, from, cardClose,
                    onTitle = { at -> menu = MenuAt(at - rootOff, pinned = cardPinned) },
                    onOpenIdea = { i, at -> onOpenIdea(i.id, i.lobeId, i.branchId, at) },
                    onClosed = { cardFrom = null },
                    title = if (cardPinned) "pinned" else "main brain",
                )
            }
            dailyFrom?.let { from ->
                DailyCard(
                    daily, from, dailyClose,
                    onToggle = { onDailyDone(it, !it.done) },
                    onItemMenu = { i, at -> itemMenu = i to at - rootOff },
                    onAdd = { addingDaily = true },
                    remindAt = remindAt, onRemind = onRemind,
                    onClosed = { dailyFrom = null },
                )
            }
            itemMenu?.let { (i, at) -> BrainMenu(at, listOf("Rename" to { renamingItem = i }, "Delete" to { burningItem = i }), { itemMenu = null }) }
            goalMenu?.let { (g, at) -> BrainMenu(at, listOf("Rename" to { renamingGoal = g }, "Delete" to { burningGoal = g }), { goalMenu = null }) }
            menu?.let { m ->
                val chips: List<Pair<String, () -> Unit>> = if (m.pinned) listOf("+ note" to { onNewIn("Pinned", m.at + rootOff) }) else listOf(
                    "+ note" to { onNewIn("", m.at + rootOff) },
                    (if (soundsOn) "Sounds on" else "Sounds off") to onToggleSounds,
                    "Reset layout" to { onResetLayout(); homeTick++ },
                )
                BrainMenu(m.at, chips, { menu = null })
            }
            if (searching) SearchPanel(
                ideaList, lobeList,
                onPick = { h ->
                    searching = false
                    // a note's card grows from the middle
                    h.idea?.let { onOpenIdea(it.id, it.lobeId, it.branchId, Offset.Unspecified) }
                },
                onClose = { searching = false },
            )
        }
        if (sunSel) {
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

    if (addingGoal) {
        NameDialog("NEW GOAL", "Name it: Panduri, Spanish...", maxLen = GOAL_NAME_MAX, onDismiss = { addingGoal = false }, onSubmit = { learn?.addGoal(it) })
    }
    renamingGoal?.let { g ->
        NameDialog("RENAME GOAL", "Name it", initial = g.name, submit = "RENAME", maxLen = GOAL_NAME_MAX, onDismiss = { renamingGoal = null }, onSubmit = { learn?.renameGoal(g.id, it) })
    }
    burningGoal?.let { g ->
        TornNoteDialog(
            title = "Delete this goal?",
            body = "\"${g.name}\" goes for good, on every device.",
            onKeep = { burningGoal = null },
            onBurn = { burningGoal = null; learn?.deleteGoal(g.id) },
        )
    }
    if (addingDaily) {
        NameDialog("NEW DAILY", "Name it: pills, workout...", submit = "ADD", maxLen = DAILY_NAME_MAX, onDismiss = { addingDaily = false }, onSubmit = onDailyAdd)
    }
    renamingItem?.let { i ->
        NameDialog("RENAME", "Name it", initial = ideaLabel(i), submit = "RENAME", maxLen = DAILY_NAME_MAX, onDismiss = { renamingItem = null }, onSubmit = { onRenameIdea(i, it) })
    }
    burningItem?.let { i ->
        TornNoteDialog(
            title = "Delete this daily?",
            body = "\"${ideaLabel(i)}\" goes for good, on every device.",
            onKeep = { burningItem = null },
            onBurn = { burningItem = null; onDeleteIdea(i) },
        )
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

/** A daily item's name may be this long. */
internal const val DAILY_NAME_MAX = 40

/** A menu opened by a tap at [at] (px in the Brain view): the tasks brain's ([tasks]), else the main brain's own. */
private class MenuAt(val at: Offset, val pinned: Boolean = false)

internal val HEADER_H = 60.dp
/** The "Brain" title row of the whole map. */
private val TITLE_H = 76.dp

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

/**
 * The main map: the main brain (a tap opens its card), the vault heart, the learn brain and the tasks brain with its tasks.
 * The camera follows the fitted overview until anyone pans or zooms; [homeTick] glides it back there.
 */
@Composable
internal fun BrainCanvas(
    tasks: List<Idea>,
    cam: BrainCamera,
    homeTick: Int,
    sunSv: Float,
    sunSel: Boolean,
    topInset: Dp,
    locked: Boolean,
    /** The learn brain's moon count. */
    learnMoons: Int,
    /** Nothing grown yet: a hint under the main brain. */
    hint: Boolean,
    /** The main brain's notes, newest first: the newest few float round it as sparks; tapping one opens it ([onOpenTask]). */
    mainNotes: List<Idea>,
    /** The learn brain's goals: small moons round it. */
    goals: List<Goal> = emptyList(),
    /** The pinned notes, newest first: a paper on the corkboard each; a tap on a paper opens it ([onOpenTask]), a double-tap on the board pins a new one. */
    pinned: List<Idea> = emptyList(),
    onTapSun: () -> Unit,
    /** The main brain was tapped; it sits at px in this view. */
    onTapBrain: (Offset) -> Unit,
    /** A spark or a paper was tapped: its note, and where it sits (px in this view). */
    onOpenTask: (Idea, Offset) -> Unit,
    onTapEmpty: () -> Unit,
    /** A double-tap on the main brain (a new note), the tasks brain (a new task) or the learn brain (a new goal). */
    onDoubleTapBrain: (Offset) -> Unit = {},
    onDoubleTapTasks: (Offset) -> Unit = {},
    onDoubleTapLearn: () -> Unit = {},
    onDoubleTapBoard: (Offset) -> Unit = {},
    /** A tap on the empty board or its "+N": the list of all pinned notes. */
    onTapBoard: (Offset) -> Unit = {},
    /** A note was dropped after a drag: save where the notes are. */
    onMoved: () -> Unit = {},
    /** A single tap on the learn or tasks brain flies into its world; on the daily brain it opens its checklist. They sit at px in this view. */
    onTapLearn: (Offset) -> Unit = {},
    onTapTasks: (Offset) -> Unit = {},
    onTapDaily: (Offset) -> Unit = {},
    onDoubleTapDaily: (Offset) -> Unit = {},
    /** The daily brain's brightness, -1 = none drawn. */
    daily: Float = -1f,
    /** A single tap on the bible brain (top right) flies into its canvas; null = no bible brain drawn. */
    onTapBible: ((Offset) -> Unit)? = null,
) {
    val density = LocalDensity.current.density
    val pxSize = remember(density) { round(2.28f * density).coerceAtLeast(1f) }
    val core = imageResource(Res.drawable.brain_main)
    val heart = imageResource(Res.drawable.vault_heart)
    val heartDead = imageResource(Res.drawable.vault_heart_dead)
    val renderer = remember { BrainRenderer(core, heart, heartDead) }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val pageFont = PageFont; val pixelFont = PixelFont // composable getters, read outside remember
    var view by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(tasks, mainNotes, goals, pinned) { buildLayout(tasks, mainNotes, goals, pinned) }
    val labels = remember(layout, density, pxSize, hint) { BrainLabels(measurer, density, pxSize, pageFont, pixelFont, layout, hint) }
    var aim by remember { mutableStateOf<FloatArray?>(null) } // x, y, zoom; a new array glides there
    fun overviewAim() = fitOverview(labels::overviewBoxes, view.width / density, view.height / density, topInset.value, pxSize / density, NODE_ZOOM, MIN_ZOOM)
    LaunchedEffect(homeTick) { if (homeTick > 0) aim = overviewAim() }
    // Until anyone touches the camera it follows the fitted overview: when the size is known, and again when the tasks load or change
    // (labels is rebuilt then; at a cold start they arrive after the size, and a fit on none must not count as a touch).
    // A new window width (a desktop window maximized or resized) always fits it again.
    var fittedW by remember { mutableStateOf(0) }
    LaunchedEffect(view, labels) {
        if (!locked) cam.fitUntouched(view.width, force = fittedW > 0 && view.width != fittedW, aim = ::overviewAim)
        if (view.width > 0) fittedW = view.width
    }
    LaunchedEffect(aim) {
        val a = aim ?: return@LaunchedEffect
        val x0 = cam.x; val y0 = cam.y; val s0 = cam.scale
        Animatable(0f).animateTo(1f, tween(400, easing = FastOutSlowInEasing)) {
            cam.x = x0 + (a[0] - x0) * value; cam.y = y0 + (a[1] - y0) * value; cam.scale = s0 + (a[2] - s0) * value
        }
    }
    val time by rememberRotTime()

    /** The world point under [p] (px in a [size] view). */
    fun worldAt(p: Offset, size: IntSize): Offset {
        val u = pxSize * cam.scale
        return Offset((p.x - ceil(size.width / pxSize) * pxSize / 2f) / u + cam.x, (p.y - ceil(size.height / pxSize) * pxSize / 2f) / u + cam.y)
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
            .pointerInput(layout, labels, locked) {
                // Long-press a pinned paper, then drag: it lifts, follows and stays where it is dropped, never off its board. Nothing else
                // moves; taps and empty-space pans are left alone.
                if (locked) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val i = layout.paperUnder(worldAt(down.position, size)).takeIf { it >= 0 } ?: return@awaitEachGesture
                    val uid = layout.pinned[i].uid
                    val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    renderer.lifted = uid
                    Sounds.play(Sfx.Tick)
                    drag(press.id) { c ->
                        val p = c.positionChange() / (pxSize * cam.scale)
                        NoteMoves.move(uid, p.x, p.y)
                        NoteMoves.d[uid] = paperOffset(layout.paperAt[i], NoteMoves[uid])
                        c.consume()
                    }
                    renderer.lifted = null
                    onMoved()
                }
            }
            .pointerInput(layout, labels, locked, onTapSun, daily >= 0f, onTapBible != null) {
                fun hit(tap: Offset, double: Boolean) {
                    if (locked) return
                    val u = pxSize * cam.scale
                    val ox = ceil(size.width / pxSize) * pxSize / 2f
                    val oy = ceil(size.height / pxSize) * pxSize / 2f
                    fun screen(w: Offset) = Offset((w.x - cam.x) * u + ox, (w.y - cam.y) * u + oy)
                    val bw = Offset((tap.x - ox) / u + cam.x, (tap.y - oy) / u + cam.y)
                    val (what, note) = layout.mapHit(bw, u, nodeScale(cam.scale), 1.dp.toPx(), double, daily >= 0f, onTapBible != null)
                    if (double) when (what) {
                        MapHit.Board -> onDoubleTapBoard(screen(layout.boardAt))
                        MapHit.Learn -> onDoubleTapLearn()
                        MapHit.Tasks -> onDoubleTapTasks(screen(TASKS_AT))
                        MapHit.Daily -> onDoubleTapDaily(screen(DAILY_AT))
                        MapHit.Brain -> onDoubleTapBrain(screen(Offset.Zero))
                        else -> {}
                    } else when (what) {
                        MapHit.Note -> note?.let { onOpenTask(it.first, screen(it.second)) }
                        MapHit.Board -> onTapBoard(screen(layout.boardAt))
                        MapHit.Heart -> onTapSun()
                        MapHit.Learn -> onTapLearn(screen(LEARN_AT))
                        MapHit.Tasks -> onTapTasks(screen(TASKS_AT))
                        MapHit.Daily -> onTapDaily(screen(DAILY_AT))
                        MapHit.Bible -> onTapBible?.invoke(screen(BIBLE_AT))
                        MapHit.Brain -> onTapBrain(screen(Offset.Zero))
                        else -> onTapEmpty()
                    }
                }
                detectTapGestures(onDoubleTap = { hit(it, true) }, onTap = { hit(it, false) })
            },
    ) {
        val vw = size.width.toInt()
        val vh = size.height.toInt()
        if (vw <= 0 || vh <= 0) return@Canvas
        renderer.resize(ceil(vw / pxSize).toInt(), ceil(vh / pxSize).toInt())
        renderer.learnMoons = learnMoons
        renderer.daily = daily
        renderer.bible = onTapBible != null
        renderer.draw(time, cam.x, cam.y, cam.scale, layout, sunSv, sunSel)
        drawIntoCanvas { renderer.blit(it, pxSize.toInt()) }
        labels.draw(this, renderer, cam.x, cam.y, cam.scale, sunSel, sunSv, home = cam.home)
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
    maxLen: Int = 24,
) {
    var text by remember { mutableStateOf(initial) }
    fun submit() {
        if (text.isNotBlank()) onSubmit(text)
        onDismiss()
    }
    PixelDialog(onDismiss) {
        Text(title, style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(16.dp))
        PixelField(text, { text = it.take(maxLen) }, hint, onDone = ::submit, cursor = Px.Lavender)
        Spacer(Modifier.height(18.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("CANCEL", onDismiss)
            PixelButton(submit, ::submit, primary = true)
        }
    }
}
