package app.vesper.ui

import androidx.compose.foundation.Canvas
import kotlin.math.exp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.data.Goal
import app.vesper.data.Idea
import app.vesper.res.Res
import app.vesper.res.brain_main
import org.jetbrains.compose.resources.imageResource
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The worlds behind the small brains: a tap on the learn brain flies into its goals, on the tasks brain into a sky of lanterns.
// Layouts are pure and tested (WorldsTest); everything is in dp from the world's top-left.

// ---------------------------------------------------------------------------------------------
// Placement (pure)
// ---------------------------------------------------------------------------------------------

private const val WORLD_M = 12f // dp kept clear at the world's edges
private const val BOX_GAP = 6f // dp between any two boxes

/** Whether boxes (l, t, r, b) [a] and [b] come closer than [gap]. */
internal fun boxesHit(a: FloatArray, b: FloatArray, gap: Float = BOX_GAP) = a[0] < b[2] + gap && b[0] < a[2] + gap && a[1] < b[3] + gap && b[1] < a[3] + gap

/** A world's laid-out things: each one's box (l, t, r, b, dp; the art on top, its name below), the brain's middle, the content height. */
internal class WorldLayout(val boxes: List<FloatArray>, val brain: Offset, val h: Float)

/** The box of a thing whose art ([art] dp tall, centred on [c]) has a name of [label] under it, [gap] apart. */
internal fun itemBox(c: Offset, art: Size, label: Size, gap: Float) =
    max(art.width, label.width).let { w -> floatArrayOf(c.x - w / 2f, c.y - art.height / 2f, c.x + w / 2f, c.y + art.height / 2f + gap + label.height) }

/** Where a thing's art sits (its middle) in its [box], [art] dp tall. */
internal fun artAt(box: FloatArray, art: Size) = Offset((box[0] + box[2]) / 2f, box[1] + art.height / 2f)

/**
 * The learn world [w] x [viewH] dp: the learn brain in the middle ([brainR] dp keep-out round it), one moon per goal round it on
 * growing rings, each moon with its whole name ([labels], measured dp) under it. A moon takes the first spot (inner ring first, its
 * spots in golden-ratio order so the goals spread round the brain) where its box stays inside the width and clear of the brain and
 * every earlier moon. Moons that rise above the top push everything down; a tall world scrolls.
 */
internal fun learnWorldLayout(labels: List<Size>, w: Float, viewH: Float, moon: Size, brainR: Float, gap: Float = 6f): WorldLayout {
    val c0 = Offset(w / 2f, viewH / 2f)
    val brainBox = floatArrayOf(c0.x - brainR, c0.y - brainR, c0.x + brainR, c0.y + brainR)
    val boxes = ArrayList<FloatArray>()
    for (label in labels) {
        var placed: FloatArray? = null
        var r = brainR + moon.height / 2f + 10f
        while (placed == null) {
            val m = max(6, (TAU * r / 34f).toInt())
            for (q in 0 until m) {
                val a = -TAU / 4f + ((q * 0.618034f) % 1f) * TAU
                val b = itemBox(Offset(c0.x + cos(a) * r, c0.y + sin(a) * r * 0.9f), moon, label, gap)
                if (b[0] < WORLD_M || b[2] > w - WORLD_M || boxesHit(b, brainBox) || boxes.any { boxesHit(b, it) }) continue
                placed = b; break
            }
            r += 14f
            if (r > 20000f) { // too narrow for this name: under everything else, centred
                val y = (boxes.maxOfOrNull { it[3] } ?: brainBox[3]).coerceAtLeast(brainBox[3]) + BOX_GAP + 1f + moon.height / 2f
                placed = itemBox(Offset(w / 2f, y), moon, label, gap)
            }
        }
        boxes.add(placed)
    }
    val top = min(brainBox[1], boxes.minOfOrNull { it[1] } ?: brainBox[1])
    val dy = max(0f, WORLD_M - top)
    val shifted = boxes.map { floatArrayOf(it[0], it[1] + dy, it[2], it[3] + dy) }
    val bottom = max(brainBox[3], boxes.maxOfOrNull { it[3] } ?: 0f) + dy
    return WorldLayout(shifted, c0 + Offset(0f, dy), max(viewH, bottom + WORLD_M))
}

/** A lantern's name: at most 20 letters, then "…". */
internal fun lanternName(task: Idea): String = ideaLabel(task).trim().ifEmpty { "..." }.let { if (it.length > 20) it.take(20).trimEnd() + "…" else it }

/**
 * The tasks world's calm cloud for tasks with [seeds] (from their uids, in the order they came), each lantern's box [box] dp (lantern
 * and name), round the tasks brain at (0, 0) with [brainR] dp kept clear. Each lantern tries its own seeded angle first, then golden-ratio
 * steps round the ring, on growing rings ([CLOUD_TALL] times taller than wide, like a phone), and takes the first spot clear of the brain and of
 * every lantern before it. A lantern's spot depends only on its uid and the ones that came before it, so a new task never moves an old one.
 */
internal fun lanternCloud(seeds: List<Int>, box: Size, brainR: Float): List<FloatArray> {
    val brainBox = floatArrayOf(-brainR, -brainR, brainR, brainR)
    val boxes = ArrayList<FloatArray>()
    for (seed in seeds) {
        val a0 = Rng(seed * 31 + 17).next() * TAU
        var r = brainR + box.height / 2f + BOX_GAP
        var placed: FloatArray? = null
        while (placed == null) {
            val m = max(8, (TAU * r / (box.width * 0.45f)).toInt())
            for (q in 0 until m) {
                val a = a0 + ((q * 0.618034f) % 1f) * TAU
                val c = Offset(cos(a) * r, sin(a) * r * CLOUD_TALL)
                val b = floatArrayOf(c.x - box.width / 2f, c.y - box.height / 2f, c.x + box.width / 2f, c.y + box.height / 2f)
                if (boxesHit(b, brainBox) || boxes.any { boxesHit(b, it) }) continue
                placed = b; break
            }
            r += 10f
        }
        boxes.add(placed)
    }
    return boxes
}

/** The box (l, t, r, b, dp) round the brain at (0, 0) ([brainR] dp) and every one of [boxes]. */
internal fun cloudBounds(boxes: List<FloatArray>, brainR: Float) = floatArrayOf(
    min(-brainR, boxes.minOfOrNull { it[0] } ?: 0f), min(-brainR, boxes.minOfOrNull { it[1] } ?: 0f),
    max(brainR, boxes.maxOfOrNull { it[2] } ?: 0f), max(brainR, boxes.maxOfOrNull { it[3] } ?: 0f),
)

/** The tasks world's zoom: its start (the whole cloud in a [w] x [h] dp view, never above 1) and its soft bounds. */
internal fun cloudZoom(bounds: FloatArray, w: Float, h: Float): Float {
    val fit = min(1f, min((w - 2f * WORLD_M) / (2f * max(-bounds[0], bounds[2])), (h - 2f * WORLD_M) / (2f * max(-bounds[1], bounds[3]))))
    return fit.coerceAtLeast(CLOUD_MIN_Z)
}
internal const val CLOUD_MIN_Z = 0.75f
internal const val CLOUD_TALL = 1.9f
internal const val CLOUD_MAX_Z = 1.8f

/** How far (dp, each way) the camera may pan from the brain at zoom [z] in a [w] x [h] dp view: just enough to bring the cloud's edge in. */
internal fun cloudPan(bounds: FloatArray, w: Float, h: Float, z: Float) = Offset(
    max(0f, max(-bounds[0], bounds[2]) - (w / 2f - WORLD_M) / z),
    max(0f, max(-bounds[1], bounds[3]) - (h / 2f - WORLD_M) / z),
)

/** The lantern names' scale at zoom [z]: with the zoom below 1 (so they never overlap), a bit above it. */
internal fun lanternNameScale(z: Float) = if (z <= 1f) z else 1f + 0.4f * (z - 1f)

/** The index of the box under [p] (dp), or -1. */
internal fun boxAt(boxes: List<FloatArray>, p: Offset, slack: Float = 4f) =
    boxes.indexOfFirst { p.x in it[0] - slack..it[2] + slack && p.y in it[1] - slack..it[3] + slack }

// ---------------------------------------------------------------------------------------------
// Art
// ---------------------------------------------------------------------------------------------

/**
 * A sky lantern, drawn in code: a paper body lit from inside (W the glowing paper, L its lighter rim, M the purple frame, D the
 * dark ribs) with a small white flame (F) under its open mouth. Purple shades and white only.
 */
internal val LANTERN_ROWS = listOf(
    "...MMMMM...",
    "..MLLLLLM..",
    ".MLWWRWWLM.",
    "MLWWWRWWWLM",
    "MLWRWRWRWLM",
    "MLWRWRWRWLM",
    "MLWRWRWRWLM",
    "MLWRWRWRWLM",
    ".MLRWRWRLM.",
    ".MLWWRWWLM.",
    "..MLWRWLM..",
    "..MDDDDDM..",
    "....DFD....",
    ".....F.....",
)
internal const val LANTERN_W = 11
internal const val LANTERN_H = 14
internal fun lanternRgb(ch: Char): Int = when (ch) {
    'W' -> 0xF3EEFF; 'R' -> 0xD6C8FF; 'L' -> 0xB69AFF; 'M' -> 0x7F66E6; 'D' -> 0x4A3478; 'F' -> 0xFFFFFF; else -> -1
}

/** The lantern centred on pixel ([cx], [cy]), [s] pixels per lantern pixel; [fl] 0..1 flickers its inner light. */
internal fun Pix.lantern(cx: Int, cy: Int, s: Int, fl: Float, a: Float) {
    val x0 = cx - LANTERN_W * s / 2; val y0 = cy - LANTERN_H * s / 2
    glow(cx.toFloat(), cy.toFloat() - s, 7f * s, 0x9C84FF, (0.16f + 0.08f * fl) * a)
    LANTERN_ROWS.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch ->
            val rgb = lanternRgb(ch)
            if (rgb >= 0) dot(x0 + x * s, y0 + y * s, s, s, rgb, if (ch == 'W' || ch == 'F') (0.8f + 0.2f * fl) * a else a)
        }
    }
}

/** The calm night behind a world: fog, a few twinkling stars, then (after the art) a light dither. */
private fun Pix.nightSky(t: Float, seed: Int, stars: Int) {
    fill(0x07030B)
    for (i in 0 until 5) glow(w / 2f + sin(t * 0.05f + i * 1.3f) * w * 0.35f, h * (0.15f + 0.18f * i) + cos(t * 0.04f + i * 1.7f) * 30f, 70f, if (i % 2 == 1) 0x2A1A6A else 0x3A1A7A, 0.3f)
    for (i in 0 until stars) {
        val a = 0.15f + 0.55f * max(0f, sin(t * (0.5f + hf(i, seed + 3)) + hf(i, seed) * TAU))
        dot((hf(i, seed + 11) * w).toInt(), (hf(i, seed + 29) * h).toInt(), 1, 1, if (i % 5 == 0) 0xFFFFFF else 0xCDB8FF, a)
    }
}

/** A small brain in the world: the core sprite ([scale] of its size, low-res px) in two dotted rings and a soft glow. */
private fun Pix.worldBrain(core: ImageBitmap, cx: Float, cy: Float, scale0: Float, r: Float, t: Float, flip: Boolean, paint: androidx.compose.ui.graphics.Paint, tapAge: Float = 9f) {
    val scale = scale0 * tapSquish(tapAge)
    tapGlow(tapAge).let { if (it > 0f) glow(cx, cy, r * 1.3f, 0xD6C8FF, 0.55f * it) }
    glow(cx, cy, r * 1.6f, 0x7A4ACF, 0.3f + 0.15f * sin(t * 1.1f))
    glow(cx, cy, r * 1.1f, 0xB69AFF, 0.25f)
    for ((rr, dir, rgb) in listOf(Triple(r * 0.86f, 2f, 0xB69AFF), Triple(r, -1.5f, 0x8A6CF0))) {
        val count = (TAU * rr / 2.5f).toInt()
        for (q in 0 until count) if (q % 4 != 0) {
            val an = q * TAU / count + t * dir * 0.05f
            dot(rotRnd(cx + cos(an) * rr), rotRnd(cy + sin(an) * rr), 1, 1, rgb, 0.8f)
        }
    }
    val sw = core.width * scale; val sh = core.height * scale
    val l = rotRnd(cx - sw / 2f); val tp = rotRnd(cy - sh / 2f)
    if (flip) { canvas.save(); canvas.translate(cx, cy); canvas.scale(-1f, 1f); canvas.translate(-cx, -cy) }
    canvas.drawImageRect(core, IntOffset.Zero, IntSize(core.width, core.height), IntOffset(l, tp), IntSize(rotRnd(cx + sw / 2f) - l, rotRnd(cy + sh / 2f) - tp), paint)
    if (flip) canvas.restore()
}

// ---------------------------------------------------------------------------------------------
// The worlds
// ---------------------------------------------------------------------------------------------

private val WorldInk = Color(0xFFE6DCFF)
private const val WORLD_BRAIN_SCALE = 0.42f // of the core sprite, low-res px
private const val WORLD_BRAIN_R = 25f // low-res px: the outer dotted ring

/** 8 whole screen pixels per font pixel at this [density] (rounded, at least 1): the pixel font's native grid, never smeared. */
internal fun crispPx(density: Float): Float = 8f * Math.round(density).coerceAtLeast(1)

/** The strip on top of a world: the way back to the map, its name, how many things are in it. */
@Composable
internal fun WorldHeader(title: String, count: String, onBack: () -> Unit, extra: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(Px.Bg.copy(alpha = 0.86f))) {
        Row(Modifier.fillMaxWidth().height(HEADER_H - 2.dp).padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "< BRAIN", Modifier.semantics { contentDescription = "Back to the map" }.clickable(onClick = onBack).padding(end = 14.dp, top = 8.dp, bottom = 8.dp),
                style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender),
            )
            Text(title, style = TextStyle(fontFamily = TitleFont, fontSize = 30.sp, color = Px.Lavender, shadow = Shadow(Px.Lavender.copy(alpha = 0.6f), blurRadius = 10f)))
            // Press Start 2P is an 8px grid: at a fractional pixel size an 8 smears into an &, so the count is sized in whole pixels
            if (count.isNotEmpty()) Text(count, Modifier.padding(start = 10.dp, top = 6.dp), style = TextStyle(fontFamily = PixelFont, fontSize = with(LocalDensity.current) { crispPx(density).toSp() }, color = Px.TextDim))
            extra()
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
    }
}

/** A world under its header: [content] gets the view's size (dp) and where it sits in the root. A [scroll]ing one scrolls when taller. */
@Composable
private fun WorldPage(
    title: String, count: String, onBack: () -> Unit, scroll: Boolean,
    content: @Composable (w: Float, viewH: Float, origin: () -> Offset) -> Unit,
) {
    BackHandler(true, onBack)
    Column(Modifier.fillMaxSize().background(Color(0xFF07030B))) {
        WorldHeader(title, count, onBack)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val w = maxWidth.value; val viewH = maxHeight.value
            var origin by remember { mutableStateOf(Offset.Zero) }
            val m = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Box(Modifier.fillMaxSize().then(m).onGloballyPositioned { origin = it.positionInRoot() }) {
                content(w, viewH) { origin }
            }
        }
    }
}

/** A dim hint line, measured once (not every frame). */
@Composable
private fun rememberHint(text: String, w: Float): TextLayoutResult {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val font = PixelFont
    val density = LocalDensity.current.density
    return remember(text, w, density) {
        measurer.measure(text, TextStyle(fontFamily = font, fontSize = 8.sp, color = Px.TextDim, textAlign = TextAlign.Center), constraints = Constraints(maxWidth = ((w - 32f) * density).toInt().coerceAtLeast(1)))
    }
}

/** A goal moon's gentle float (dp) at [t] seconds: at most [MOON_DRIFT] each way, its own pace by [seed]; its box keeps room for it. */
internal fun moonDrift(t: Float, seed: Int): Offset {
    val ph = (seed and 0xFFFF) / 65536f * TAU
    return Offset(sin(t * 0.31f + ph) * MOON_DRIFT, cos(t * 0.43f + ph * 1.7f) * MOON_DRIFT)
}
internal const val MOON_DRIFT = 4f

/**
 * The learn world: the learn brain in the middle with every goal a slowly floating moon round it, each with its whole name. A tap on the
 * brain adds a goal ([onAdd]); a tap on a moon or its name ([onGoal], with where it sits, px in the root) offers rename and delete.
 */
@Composable
internal fun LearnWorld(goals: List<Goal>, onBack: () -> Unit, onAdd: () -> Unit, onGoal: (Goal, Offset) -> Unit) {
    WorldPage("learn", if (goals.size == 1) "1 goal" else "${goals.size} goals", onBack, scroll = true) { w, viewH, origin ->
        val density = LocalDensity.current.density
        val u = rememberRotPxSize()
        val core = imageResource(Res.drawable.brain_main)
        val pix = remember { Pix() }
        val poke = remember { floatArrayOf(-9f) } // when the brain was last tapped (world seconds)
        val births = remember { Births() }
        val paint = remember { pixPaint() }
        val dither = remember { makeDitherPaint(0.17f) }
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val font = PageFont
        val time = rememberRotTime()
        val lowDp = u / density // dp per low-res pixel
        val moon = Size(11f * 2f * lowDp, 11f * 2f * lowDp)
        val names = remember(goals, density, font) {
            goals.map { g ->
                measurer.measure(g.name, TextStyle(fontFamily = font, fontSize = 17.sp, lineHeight = 17.sp, color = WorldInk, textAlign = TextAlign.Center), constraints = Constraints(maxWidth = (GOAL_LABEL_W * density).toInt()))
            }
        }
        // each box keeps [MOON_DRIFT] of room on every side, so two floating moons (and their names) never touch
        val moonRoom = Size(moon.width + 2f * MOON_DRIFT, moon.height + 2f * MOON_DRIFT)
        val lay = remember(names, w, viewH) { learnWorldLayout(names.map { Size(it.size.width / density + 2f * MOON_DRIFT, it.size.height / density) }, w, viewH, moonRoom, WORLD_BRAIN_R * lowDp + 8f, GOAL_GAP) }
        val hint = rememberHint("TAP THE BRAIN TO ADD A GOAL", w)
        val seeds = remember(goals) { goals.map { it.uid.hashCode() } }
        WorldCanvas(lay.h, Modifier.pointerInput(lay) {
            detectTapGestures { p ->
                val q = p / density
                if ((q - lay.brain).getDistance() <= WORLD_BRAIN_R * lowDp + 16f) { poke[0] = time.floatValue; onAdd() }
                else boxAt(lay.boxes, q).takeIf { it >= 0 }?.let { i -> onGoal(goals[i], origin() + artAt(lay.boxes[i], moonRoom) * density) }
            }
        }) {
            val t = time.floatValue
            pix.resize(ceil(size.width / u).toInt(), ceil(size.height / u).toInt())
            pix.nightSky(t, 41, (pix.w * pix.h / 300).coerceAtMost(240))
            val bc = lay.brain * density / u
            pix.worldBrain(core, bc.x, bc.y, WORLD_BRAIN_SCALE, WORLD_BRAIN_R, t, true, paint, t - poke[0])
            val drift = seeds.map { moonDrift(t, it) }
            births.update(goals.map { it.uid }, t)
            // a moon that was there when the world opened drifts out of the brain into place; a new one pops in from 60 %
            fun home(i: Int) = lay.brain + (artAt(lay.boxes[i], moonRoom) - lay.brain) * births.drift(goals[i].uid, t)
            lay.boxes.forEachIndexed { i, b ->
                val m = (home(i) + drift[i]) * density / u
                val k = births.pop(goals[i].uid, t)
                pix.popped(m.x, m.y, k, appearAlpha(k) * births.drift(goals[i].uid, t)) {
                    pix.glow(m.x, m.y, 16f, 0x8F7BFF, 0.3f)
                    pix.goalMoon(rotRnd(m.x), rotRnd(m.y), 2, 1f)
                }
            }
            pix.canvas.drawRect(0f, 0f, pix.w.toFloat(), pix.h.toFloat(), dither)
            drawIntoCanvas { pix.blit(it, 0, 0, u.toInt()) }
            lay.boxes.forEachIndexed { i, b ->
                val tl = names[i]
                val k = births.pop(goals[i].uid, t)
                val c = home(i) - artAt(b, moonRoom) // how far the drift-in still has it from its box
                val top = Offset(((b[0] + b[2]) / 2f + drift[i].x + c.x) * density - tl.size.width / 2f, (b[1] + MOON_DRIFT + moon.height + GOAL_GAP + drift[i].y + c.y) * density)
                withTransform({ scale(k, k, Offset(top.x + tl.size.width / 2f, top.y)) }) { drawText(tl, topLeft = top, alpha = appearAlpha(k) * births.drift(goals[i].uid, t)) }
            }
            if (goals.isEmpty()) drawText(hint, topLeft = Offset(size.width / 2f - hint.size.width / 2f, (lay.brain.y + WORLD_BRAIN_R * lowDp + 18f) * density))
        }
    }
}

/**
 * The tasks world: a calm night sky, the tasks brain in the middle and every task a softly drifting lantern round it with its name. A
 * drag pans a little and a pinch (or the wheel) zooms a little, within soft bounds; zoomed in, the names grow a bit. A tap on a lantern
 * opens its card ([onOpen], with where it sits, px in the root); a tap on the brain starts a new task ([onAdd]).
 */
@Composable
internal fun LanternWorld(tasks: List<Idea>, onBack: () -> Unit, onAdd: (Offset) -> Unit, onOpen: (Idea, Offset) -> Unit) {
    WorldPage("tasks", if (tasks.size == 1) "1 lantern" else "${tasks.size} lanterns", onBack, scroll = false) { w, viewH, origin ->
        val density = LocalDensity.current.density
        val u = rememberRotPxSize()
        val core = imageResource(Res.drawable.brain_main)
        val pix = remember { Pix() }
        val poke = remember { floatArrayOf(-9f) } // when the brain was last tapped (world seconds)
        val births = remember { Births() }
        val paint = remember { pixPaint() }
        val dither = remember { makeDitherPaint(0.17f) }
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val font = PageFont
        val time = rememberRotTime()
        val lowDp = u / density
        val art = Size(LANTERN_W * lowDp, LANTERN_H * lowDp)
        val brainR = WORLD_BRAIN_R * lowDp + 8f
        val order = remember(tasks) { tasks.sortedWith(compareBy({ it.id }, { it.uid })) } // the order they came: a new one never moves an old one
        val names = remember(order, density, font) {
            order.map { tk ->
                measurer.measure(lanternName(tk), TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 16.sp, color = WorldInk, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (LANTERN_LABEL_W * density).toInt()))
            }
        }
        val box = Size(LANTERN_LABEL_W + 2f * LANTERN_DRIFT, art.height + LANTERN_GAP + 32f + 2f * LANTERN_DRIFT)
        val boxes = remember(order, box, brainR) { lanternCloud(order.map { it.uid.hashCode() }, box, brainR) }
        val bounds = remember(boxes, brainR) { cloudBounds(boxes, brainR) }
        val hint = rememberHint("TAP THE BRAIN FOR A NEW TASK", w)
        // the camera: the world point (dp, the brain at 0, 0) in the view's middle, and the zoom; plain fields, read only while drawing
        val startZ = remember(bounds, w, viewH) { cloudZoom(bounds, w, viewH) }
        val cam = remember(startZ) { floatArrayOf(0f, 0f, startZ) }
        fun zoomBy(at: Offset, pan: Offset, k: Float) { // [at], [pan] in px of the view
            val mid = Offset(w / 2f, viewH / 2f)
            val z = cam[2]
            val nz = (z * k).coerceIn(min(startZ, CLOUD_MIN_Z), CLOUD_MAX_Z)
            val world = Offset(cam[0], cam[1]) + (at / density - mid) / z // the point under the fingers stays put
            val c = world - (at / density + pan / density - mid) / nz
            val b = cloudPan(bounds, w, viewH, nz)
            cam[0] = c.x.coerceIn(-b.x, b.x); cam[1] = c.y.coerceIn(-b.y, b.y); cam[2] = nz
        }
        fun camAt() = Offset(cam[0], cam[1])
        WorldCanvas(viewH, Modifier
            .pointerInput(bounds, w, viewH) { detectTransformGestures { c, pan, k, _ -> zoomBy(c, pan, k) } }
            .pointerInput(bounds, w, viewH) {
                awaitPointerEventScope { // the mouse wheel; touch screens never send it
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Scroll) continue
                        val c = e.changes.first()
                        zoomBy(c.position, Offset.Zero, exp(-c.scrollDelta.y * 0.18f))
                        c.consume()
                    }
                }
            }
            .pointerInput(boxes, order, w, viewH) {
                detectTapGestures { p ->
                    val q = p / density
                    val z = cam[2]
                    val brain = Offset(w / 2f, viewH / 2f) - camAt() * z
                    if ((q - brain).getDistance() <= (WORLD_BRAIN_R * lowDp + 16f) * max(1f, z)) { poke[0] = time.floatValue; onAdd(origin() + brain * density); return@detectTapGestures }
                    val s = artScale(z); val ts = lanternNameScale(z)
                    val hit = boxes.indices.firstOrNull { i ->
                        val c = lanternScreen(boxes[i], art, 0f, 0, camAt(), z, w, viewH)
                        val half = max(art.width * s, names[i].size.width / density * ts) / 2f + 4f
                        q.x in c.x - half..c.x + half && q.y in c.y - art.height * s / 2f - 4f..c.y + art.height * s / 2f + LANTERN_GAP + names[i].size.height / density * ts + 4f
                    }
                    hit?.let { i -> onOpen(order[i], origin() + lanternScreen(boxes[i], art, 0f, 0, camAt(), z, w, viewH) * density) }
                }
            }) {
            val t = time.floatValue
            val z = cam[2]
            pix.resize(ceil(size.width / u).toInt(), ceil(size.height / u).toInt())
            pix.nightSky(t, 73, (pix.w * pix.h / 200).coerceAtMost(400))
            val brain = Offset(w / 2f, viewH / 2f) - camAt() * z
            val bc = brain * density / u
            pix.worldBrain(core, bc.x, bc.y, WORLD_BRAIN_SCALE, WORLD_BRAIN_R, t, false, paint, t - poke[0])
            val s = artScale(z); val ts = lanternNameScale(z)
            births.update(order.map { it.uid }, t)
            // a lantern that was there when the world opened drifts out of the brain into place; a new one pops in from 60 %
            val at = boxes.indices.map { i -> brain + (lanternScreen(boxes[i], art, t, order[i].uid.hashCode(), camAt(), z, w, viewH) - brain) * births.drift(order[i].uid, t) }
            at.forEachIndexed { i, p ->
                val q = p * density / u
                val k = births.pop(order[i].uid, t)
                pix.popped(q.x, q.y, k, appearAlpha(k) * births.drift(order[i].uid, t)) {
                    pix.lantern(rotRnd(q.x), rotRnd(q.y), s, 0.5f + 0.5f * sin(t * 2.3f + (order[i].uid.hashCode() and 0xFF) * 0.1f), 1f)
                }
            }
            pix.canvas.drawRect(0f, 0f, pix.w.toFloat(), pix.h.toFloat(), dither)
            drawIntoCanvas { pix.blit(it, 0, 0, u.toInt()) }
            at.forEachIndexed { i, p ->
                val tl = names[i]
                val k = births.pop(order[i].uid, t)
                val top = (p.y + art.height * s / 2f + LANTERN_GAP) * density
                val pivot = Offset(p.x * density, top)
                withTransform({ scale(ts * k, ts * k, pivot) }) { drawText(tl, topLeft = Offset(pivot.x - tl.size.width / 2f, top), alpha = appearAlpha(k) * births.drift(order[i].uid, t)) }
            }
            if (tasks.isEmpty()) drawText(hint, topLeft = Offset(size.width / 2f - hint.size.width / 2f, (brain.y - WORLD_BRAIN_R * lowDp - 28f) * density))
        }
    }
}

/** How opaque a thing is while it pops in at [k] (0.6..1): clear from 0 to full. */
internal fun appearAlpha(k: Float) = ((k - 0.6f) * 2.5f).coerceIn(0f, 1f)

/** Draws [draw] scaled by [k] about ([cx], [cy]) at [a] opacity; at rest (1, 1) it is just [draw]. */
private inline fun Pix.popped(cx: Float, cy: Float, k: Float, a: Float, draw: () -> Unit) {
    if (k >= 1f && a >= 1f) { draw(); return }
    val old = ma
    canvas.save(); canvas.translate(cx, cy); canvas.scale(k, k); canvas.translate(-cx, -cy); ma = old * a
    draw()
    ma = old; canvas.restore()
}

/** The lantern art's pixel scale at zoom [z]: twice as big from 1.5. */
internal fun artScale(z: Float) = if (z >= 1.5f) 2 else 1

/** A lantern's art middle on screen (dp in a [w] x [h] view) at [t]: its spot in its [box] seen by the camera at [cam], [z], plus its small drift. */
internal fun lanternScreen(box: FloatArray, art: Size, t: Float, seed: Int, cam: Offset, z: Float, w: Float, h: Float): Offset {
    val home = Offset((box[0] + box[2]) / 2f, box[1] + LANTERN_DRIFT + art.height / 2f)
    val drift = lanternAt(box, art, t, seed) - home // a few dp, never scaled
    return Offset(w / 2f, h / 2f) + (home - cam) * z + drift
}

internal const val GOAL_LABEL_W = 118f // dp: a goal's whole name wraps to this width
private const val GOAL_GAP = 4f // dp from a moon down to its name
internal const val LANTERN_LABEL_W = 92f // dp: a lantern's name, two lines at most
private const val LANTERN_GAP = 3f // dp from a lantern down to its name
private const val LANTERN_DRIFT = 3f // dp a lantern drifts each way: its box keeps room for it

/** A lantern's middle (dp) in its box at [t] seconds: a slow drift of [LANTERN_DRIFT] at most each way, its own pace by [seed]. */
internal fun lanternAt(box: FloatArray, art: Size, t: Float, seed: Int): Offset {
    val ph = (seed and 0xFFFF) / 65536f * TAU
    return Offset((box[0] + box[2]) / 2f + sin(t * 0.21f + ph) * (LANTERN_DRIFT - 1f), box[1] + LANTERN_DRIFT + art.height / 2f + sin(t * 0.37f + ph * 1.3f) * LANTERN_DRIFT)
}

@Composable
private fun WorldCanvas(h: Float, modifier: Modifier, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) =
    Canvas(Modifier.fillMaxWidth().height(h.dp).then(modifier), onDraw = draw)
