package app.vesper.ui

import androidx.compose.foundation.Canvas
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

/** dp under the lanterns kept for the tasks brain (its middle sits [LANTERN_BRAIN_Y] dp above the world's bottom). */
internal const val LANTERN_BRAIN_ZONE = 140f
internal const val LANTERN_BRAIN_Y = 72f

/**
 * The lantern sky [w] x [viewH] dp for tasks with [seeds] (from their uids, in the order they came), each lantern's box [box] dp
 * (lantern and name). The tasks brain sits low in the middle; the sky above it is cut into cells, and each lantern takes, of its own
 * seeded cells that are still free, the one farthest from the lanterns before it, at a seeded spot inside it, so they never overlap
 * and spread over the whole sky. A lantern's spot depends only on its uid and the lanterns that came before it, so a new task never
 * moves an old one (until the sky must grow). The sky grows taller (and scrolls) when the screen has too few cells.
 */
internal fun lanternLayout(seeds: List<Int>, w: Float, viewH: Float, box: Size): WorldLayout {
    // the sky is cut into cells a lantern fits in with room to drift; each lantern takes a free cell and a seeded spot inside it
    val g = BOX_GAP + 1f
    val cols = max(1, ((w - 2f * WORLD_M) / (box.width + g)).toInt())
    val fitRows = ((viewH - LANTERN_BRAIN_ZONE - WORLD_M) / (box.height + g)).toInt()
    val rows = max(max(1, fitRows), (seeds.size + cols - 1) / cols)
    val cw = (w - 2f * WORLD_M) / cols
    val ch = max(box.height + g, (viewH - LANTERN_BRAIN_ZONE - WORLD_M) / rows)
    val h = max(viewH, WORLD_M + rows * ch + LANTERN_BRAIN_ZONE)
    val cells = cols * rows
    val taken = BooleanArray(cells)
    val centres = ArrayList<Offset>()
    val boxes = ArrayList<FloatArray>()
    fun centre(c: Int) = Offset(WORLD_M + (c % cols + 0.5f) * cw, WORLD_M + (c / cols + 0.5f) * ch)
    for (seed in seeds) {
        val r = Rng(seed * 31 + 17)
        // of a few seeded cells that are free, the one farthest from the lanterns before it, so they spread over the whole sky
        var cell = -1; var bestD = -1f
        repeat(24) {
            val c = (r.next() * cells).toInt().coerceIn(0, cells - 1)
            if (taken[c]) return@repeat
            val d = centres.minOfOrNull { (it - centre(c)).getDistance() } ?: Float.MAX_VALUE
            if (d > bestD) { cell = c; bestD = d }
        }
        if (cell < 0) { val start = (r.next() * cells).toInt(); cell = (0 until cells).map { (start + it) % cells }.first { !taken[it] } }
        taken[cell] = true
        centres.add(centre(cell))
        val x = WORLD_M + (cell % cols) * cw + g / 2f + r.next() * max(0f, cw - box.width - g)
        val y = WORLD_M + (cell / cols) * ch + g / 2f + r.next() * max(0f, ch - box.height - g)
        boxes.add(floatArrayOf(x, y, x + box.width, y + box.height))
    }
    return WorldLayout(boxes, Offset(w / 2f, h - LANTERN_BRAIN_Y), h)
}

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
private fun Pix.worldBrain(core: ImageBitmap, cx: Float, cy: Float, scale: Float, r: Float, t: Float, flip: Boolean, paint: androidx.compose.ui.graphics.Paint) {
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
            if (count.isNotEmpty()) Text(count, Modifier.padding(start = 10.dp, top = 6.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim))
            extra()
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
    }
}

/** A world's scrolling canvas [h] dp tall under its header; [startAtBottom] scrolls to the bottom first (the lantern sky). */
@Composable
private fun WorldPage(
    title: String, count: String, onBack: () -> Unit, startAtBottom: Boolean,
    content: @Composable (w: Float, viewH: Float, origin: () -> Offset) -> Unit,
) {
    BackHandler(true, onBack)
    Column(Modifier.fillMaxSize().background(Color(0xFF07030B))) {
        WorldHeader(title, count, onBack)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val w = maxWidth.value; val viewH = maxHeight.value
            val scroll = rememberScrollState()
            var origin by remember { mutableStateOf(Offset.Zero) }
            if (startAtBottom) LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
            Box(Modifier.fillMaxSize().verticalScroll(scroll).onGloballyPositioned { origin = it.positionInRoot() }) {
                content(w, viewH) { origin }
            }
        }
    }
}

/**
 * The learn world: the learn brain in the middle with every goal a moon round it, each with its whole name. A double-tap on the brain
 * adds a goal ([onAdd]); a tap on a moon or its name ([onGoal], with where it sits, px in the root) offers rename and delete.
 */
@Composable
internal fun LearnWorld(goals: List<Goal>, onBack: () -> Unit, onAdd: () -> Unit, onGoal: (Goal, Offset) -> Unit) {
    WorldPage("learn", if (goals.size == 1) "1 goal" else "${goals.size} goals", onBack, startAtBottom = false) { w, viewH, origin ->
        val density = LocalDensity.current.density
        val u = rememberRotPxSize()
        val core = imageResource(Res.drawable.brain_main)
        val pix = remember { Pix() }
        val paint = remember { pixPaint() }
        val dither = remember { makeDitherPaint(0.17f) }
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val font = PageFont; val pixelFont = PixelFont
        val time = rememberRotTime()
        val lowDp = u / density // dp per low-res pixel
        val moon = Size(11f * 2f * lowDp, 11f * 2f * lowDp)
        val names = remember(goals, density, font) {
            goals.map { g ->
                measurer.measure(g.name, TextStyle(fontFamily = font, fontSize = 17.sp, lineHeight = 17.sp, color = WorldInk, textAlign = TextAlign.Center), constraints = Constraints(maxWidth = (GOAL_LABEL_W * density).toInt()))
            }
        }
        val lay = remember(names, w, viewH) { learnWorldLayout(names.map { Size(it.size.width / density, it.size.height / density) }, w, viewH, moon, WORLD_BRAIN_R * lowDp + 8f, GOAL_GAP) }
        WorldCanvas(lay.h, Modifier.pointerInput(lay) {
            detectTapGestures(
                onDoubleTap = { p -> if ((p / density - lay.brain).getDistance() <= WORLD_BRAIN_R * lowDp + 16f) onAdd() },
                onTap = { p -> boxAt(lay.boxes, p / density).takeIf { it >= 0 }?.let { i -> onGoal(goals[i], origin() + artAt(lay.boxes[i], moon) * density) } },
            )
        }) {
            val t = time.floatValue
            pix.resize(ceil(size.width / u).toInt(), ceil(size.height / u).toInt())
            pix.nightSky(t, 41, (pix.w * pix.h / 300).coerceAtMost(240))
            val bc = lay.brain * density / u
            pix.worldBrain(core, bc.x, bc.y, WORLD_BRAIN_SCALE, WORLD_BRAIN_R, t, true, paint)
            lay.boxes.forEach { b ->
                val m = artAt(b, moon) * density / u
                pix.glow(m.x, m.y, 16f, 0x8F7BFF, 0.3f)
                pix.goalMoon(rotRnd(m.x), rotRnd(m.y), 2, 1f)
            }
            pix.canvas.drawRect(0f, 0f, pix.w.toFloat(), pix.h.toFloat(), dither)
            drawIntoCanvas { pix.blit(it, 0, 0, u.toInt()) }
            lay.boxes.forEachIndexed { i, b ->
                val tl = names[i]
                drawText(tl, topLeft = Offset((b[0] + b[2]) / 2f * density - tl.size.width / 2f, (b[1] + moon.height + GOAL_GAP) * density))
            }
            if (goals.isEmpty()) hint(measurer, pixelFont, "DOUBLE-TAP THE BRAIN TO ADD A GOAL", lay.brain.y + WORLD_BRAIN_R * lowDp + 18f, density)
        }
    }
}

/**
 * The tasks world: a calm night sky, the tasks brain low in the middle, every task a softly drifting lantern with its name. A tap on a
 * lantern opens its card ([onOpen], with where it sits, px in the root); a double-tap on the brain starts a new task ([onAdd]).
 */
@Composable
internal fun LanternWorld(tasks: List<Idea>, onBack: () -> Unit, onAdd: (Offset) -> Unit, onOpen: (Idea, Offset) -> Unit) {
    WorldPage("tasks", if (tasks.size == 1) "1 lantern" else "${tasks.size} lanterns", onBack, startAtBottom = true) { w, viewH, origin ->
        val density = LocalDensity.current.density
        val u = rememberRotPxSize()
        val core = imageResource(Res.drawable.brain_main)
        val pix = remember { Pix() }
        val paint = remember { pixPaint() }
        val dither = remember { makeDitherPaint(0.17f) }
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val font = PageFont; val pixelFont = PixelFont
        val time = rememberRotTime()
        val lowDp = u / density
        val art = Size(LANTERN_W * lowDp, LANTERN_H * lowDp)
        val order = remember(tasks) { tasks.sortedWith(compareBy({ it.id }, { it.uid })) } // the order they came: a new one never moves an old one
        val names = remember(order, density, font) {
            order.map { tk ->
                measurer.measure(lanternName(tk), TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 16.sp, color = WorldInk, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (LANTERN_LABEL_W * density).toInt()))
            }
        }
        val box = Size(LANTERN_LABEL_W + 2f * LANTERN_DRIFT, art.height + LANTERN_GAP + 32f + 2f * LANTERN_DRIFT)
        val lay = remember(order, w, viewH, box) { lanternLayout(order.map { it.uid.hashCode() }, w, viewH, box) }
        WorldCanvas(lay.h, Modifier.pointerInput(lay, order) {
            detectTapGestures(
                onDoubleTap = { p -> if ((p / density - lay.brain).getDistance() <= WORLD_BRAIN_R * lowDp + 16f) onAdd(origin() + lay.brain * density) },
                onTap = { p -> boxAt(lay.boxes, p / density).takeIf { it >= 0 }?.let { i -> onOpen(order[i], origin() + lanternAt(lay.boxes[i], art, 0f, 0) * density) } },
            )
        }) {
            val t = time.floatValue
            pix.resize(ceil(size.width / u).toInt(), ceil(size.height / u).toInt())
            pix.nightSky(t, 73, (pix.w * pix.h / 200).coerceAtMost(400))
            val bc = lay.brain * density / u
            pix.worldBrain(core, bc.x, bc.y, WORLD_BRAIN_SCALE, WORLD_BRAIN_R, t, false, paint)
            val at = lay.boxes.indices.map { i -> lanternAt(lay.boxes[i], art, t, order[i].uid.hashCode()) }
            at.forEachIndexed { i, p ->
                val q = p * density / u
                pix.lantern(rotRnd(q.x), rotRnd(q.y), 1, 0.5f + 0.5f * sin(t * 2.3f + (order[i].uid.hashCode() and 0xFF) * 0.1f), 1f)
            }
            pix.canvas.drawRect(0f, 0f, pix.w.toFloat(), pix.h.toFloat(), dither)
            drawIntoCanvas { pix.blit(it, 0, 0, u.toInt()) }
            at.forEachIndexed { i, p ->
                val tl = names[i]
                drawText(tl, topLeft = Offset(p.x * density - tl.size.width / 2f, (p.y + art.height / 2f + LANTERN_GAP) * density))
            }
            if (tasks.isEmpty()) hint(measurer, pixelFont, "DOUBLE-TAP THE BRAIN FOR A NEW TASK", lay.brain.y - WORLD_BRAIN_R * lowDp - 28f, density)
        }
    }
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.hint(measurer: androidx.compose.ui.text.TextMeasurer, font: androidx.compose.ui.text.font.FontFamily, text: String, yDp: Float, density: Float) {
    val tl: TextLayoutResult = measurer.measure(text, TextStyle(fontFamily = font, fontSize = 8.sp, color = Px.TextDim, textAlign = TextAlign.Center), constraints = Constraints(maxWidth = (size.width - 32f * density).toInt().coerceAtLeast(1)))
    drawText(tl, topLeft = Offset(size.width / 2f - tl.size.width / 2f, yDp * density))
}
