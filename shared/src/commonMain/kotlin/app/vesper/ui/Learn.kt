package app.vesper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vesper.LearnViewModel
import app.vesper.data.Goal
import app.vesper.data.Step
import app.vesper.res.Res
import app.vesper.res.brain_main
import org.jetbrains.compose.resources.imageResource
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The learn brain: goals as moons round a small second brain, each goal a path of steps.

// ---------------------------------------------------------------------------------------------
// Rules (pure, tested in LearnTest)
// ---------------------------------------------------------------------------------------------

internal const val DAY_MS = 86_400_000L

/** The learn brain's ring radius in world units: how deep a fly-through into it zooms. */
internal const val LEARN_R = 12f
/** The learn brain's core sprite scale on the main map (the main brain's is 0.64). */
internal const val LEARN_SCALE = 0.24f

/** Done steps over all steps; 0 with none. */
internal fun goalProgress(steps: List<Step>): Float = if (steps.isEmpty()) 0f else steps.count { it.done } / steps.size.toFloat()

/** The last time anyone worked on [goal]: made or renamed it, or made, edited or ticked one of its [steps]. */
internal fun goalTouchedAt(goal: Goal, steps: List<Step>): Long = max(goal.updatedAt, steps.maxOfOrNull { it.updatedAt } ?: 0L)

/** 1 while touched within 7 days, then fading evenly to 0.45 over the next three weeks, never dimmer. */
internal fun goalBrightness(touchedAt: Long, now: Long): Float =
    1f - 0.55f * (((now - touchedAt) / DAY_MS.toFloat() - 7f) / 21f).coerceIn(0f, 1f)

/** [goalId]'s steps in path order. */
internal fun pathOf(steps: List<Step>, goalId: Long): List<Step> = steps.filter { it.goalId == goalId }.sortedWith(compareBy({ it.position }, { it.id }))

/** Undone steps whose day is [today] or earlier: oldest day first, then in goal order and path order. Steps of unknown goals are left out. */
internal fun dueSteps(goals: List<Goal>, steps: List<Step>, today: Long): List<Step> {
    val rank = goals.withIndex().associate { it.value.id to it.index }
    return steps.filter { !it.done && (it.day ?: Long.MAX_VALUE) <= today && it.goalId in rank }
        .sortedWith(compareBy({ it.day }, { rank[it.goalId] }, { it.position }, { it.id }))
}

internal fun isOverdue(step: Step, today: Long) = !step.done && (step.day ?: Long.MAX_VALUE) < today

/** "today", "tomorrow", or a short date like "Nov 20". */
internal fun dayLabel(day: Long, today: Long): String = when (day) {
    today -> "today"
    today + 1 -> "tomorrow"
    else -> LocalDate.ofEpochDay(day).let { it.month.getDisplayName(DateTextStyle.SHORT, Locale.ENGLISH) + " " + it.dayOfMonth }
}

/**
 * Where the learn brain sits on the main map: lower right of the core, below the today strip's line under it and right of the
 * heart's nerve, as far as it can get from every portal (world [portals]) and the name under each.
 */
internal fun learnSpot(portals: List<Offset>): Offset {
    var best = Offset.Zero
    var score = Float.NEGATIVE_INFINITY
    for (deg in 30..85 step 5) for (r in floatArrayOf(56f, 62f, 68f, 74f)) {
        val a = Math.toRadians(deg.toDouble())
        val x = (cos(a) * r).toFloat(); val y = (sin(a) * r).toFloat()
        var s = min(y - 52f, x - 16f)
        for (p in portals) s = min(s, min(hypot(x - p.x, y - p.y) - 30f, hypot(x - p.x, y - p.y - 24f) - 22f))
        if (s > score) { score = s; best = Offset(x, y) }
    }
    return best
}

// ---------------------------------------------------------------------------------------------
// The learn view: the learn brain with its goals circling it
// ---------------------------------------------------------------------------------------------

private val MoonInk = Color(0xFFE6DCFF)
private const val MOON_N = 14 // a goal moon, low-res px across
private const val MOON_STEPS = 16

@Composable
internal fun LearnView(goals: List<Goal>, steps: List<Step>, topInset: Dp, onTapBrain: (Offset) -> Unit, onOpenGoal: (Long) -> Unit) {
    val density = LocalDensity.current.density
    val pxSize = rememberRotPxSize()
    val core = imageResource(Res.drawable.brain_main)
    val pix = remember { Pix() }
    val paint = remember { pixPaint() }
    val dither = remember { makeDitherPaint(0.17f) }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val font = PageFont
    val pixelFont = PixelFont
    val time = rememberRotTime()
    // progress and brightness per goal; brightness by the day, so a goal dims while the view stays open only after a data change
    val info = remember(goals, steps) {
        val now = System.currentTimeMillis()
        goals.map { g -> pathOf(steps, g.id).let { s -> goalProgress(s) to goalBrightness(goalTouchedAt(g, s), now) } }
    }
    val moons = remember { Array(MOON_STEPS + 1) { moonPhase(0.5 * it / MOON_STEPS, MOON_N, 0xC9B0FF, 0xE6DCFF, 0x8F6BD0, 0x2A1A44) } } // phases 0..100 %, reusing the Moons tab renderer
    val moonPaint = remember { pixPaint() }
    val spots = remember { arrayOf(FloatArray(0)) } // moon centres (px) as drawn last frame, then the brain's centre
    Canvas(
        Modifier.fillMaxSize().pointerInput(goals) {
            detectTapGestures { tap ->
                val s = spots[0]
                if (s.size < 2) return@detectTapGestures
                val n = s.size / 2 - 1
                val near = (0 until n).minByOrNull { hypot(s[it * 2] - tap.x, s[it * 2 + 1] - tap.y) }
                val centre = Offset(s[n * 2], s[n * 2 + 1])
                if (near != null && near < goals.size && hypot(s[near * 2] - tap.x, s[near * 2 + 1] - tap.y) <= 30.dp.toPx()) onOpenGoal(goals[near].id)
                else if ((centre - tap).getDistance() <= 44.dp.toPx()) onTapBrain(centre)
            }
        },
    ) {
        val t = time.floatValue
        val w = ceil(size.width / pxSize).toInt(); val h = ceil(size.height / pxSize).toInt()
        if (w <= 0 || h <= 0) return@Canvas
        pix.resize(w, h)
        pix.fill(0x07030B)
        val top = topInset.toPx() / pxSize
        val cx = w / 2f; val cy = top + (h - top) / 2f
        for (i in 0 until 5) pix.glow(cx + sin(t * 0.05f + i * 1.3f) * 60f, cy + cos(t * 0.04f + i * 1.7f) * 90f, 70f, if (i % 2 == 1) 0x2A1A6A else 0x3A1A7A, 0.3f)
        for (i in 0 until 90) {
            val a = 0.15f + 0.45f * max(0f, sin(t * 1.2f + hf(i, 5) * 6.28f))
            pix.dot((hf(i, 11) * w).toInt(), (hf(i, 29) * h).toInt(), 1, 1, 0xCDB8FF, a)
        }

        // orbits: up to three dotted rings, the goals shared round them
        val n = goals.size
        val rings = min(n, 3)
        val maxR = min(w / 2f - 14f, (h - top) / 2f - 16f)
        val base = 48f
        val gap = ((maxR - base) / 2f).coerceIn(9f, 24f)
        for (k in 0 until rings) {
            val r = base + k * gap
            val count = (TAU * r / 3f).toInt()
            for (q in 0 until count) {
                val an = q * TAU / count + t * 0.01f
                if (q % 2 == 0) pix.dot(rotRnd(cx + cos(an) * r), rotRnd(cy + sin(an) * r * ORBIT_Y), 1, 1, 0x4A3478, 0.8f)
            }
        }

        // the learn brain
        pix.glow(cx, cy, 48f, 0x7A4ACF, 0.3f + 0.15f * sin(t * 1.1f))
        pix.glow(cx, cy, 34f, 0xB69AFF, 0.25f)
        for ((r, dir, rgb) in listOf(Triple(30f, 2f, 0xB69AFF), Triple(35f, -1.5f, 0x8A6CF0))) {
            val count = (TAU * r / 2.5f).toInt()
            for (q in 0 until count) if (q % 4 != 0) {
                val an = q * TAU / count + t * dir * 0.05f
                pix.dot(rotRnd(cx + cos(an) * r), rotRnd(cy + sin(an) * r), 1, 1, rgb, 0.8f)
            }
        }
        val sw = core.width * 0.64f; val sh = core.height * 0.64f
        val l = rotRnd(cx - sw / 2f); val tp = rotRnd(cy - sh / 2f)
        pix.canvas.save(); pix.canvas.translate(cx, cy); pix.canvas.scale(-1f, 1f); pix.canvas.translate(-cx, -cy) // the learn brain is the main one mirrored
        pix.canvas.drawImageRect(core, IntOffset.Zero, IntSize(core.width, core.height), IntOffset(l, tp), IntSize(rotRnd(cx + sw / 2f) - l, rotRnd(cy + sh / 2f) - tp), paint)
        pix.canvas.restore()
        pix.canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), dither)

        // the moons: a disc, a dark ring with the done part lit, dimmer when left alone
        val out = FloatArray(n * 2 + 2)
        for (i in 0 until n) {
            val k = i % rings
            val j = i / rings
            val onRing = (n - k + rings - 1) / rings
            val r = base + k * gap
            val an = t * TAU / (260f + 90f * k) + j * TAU / onRing + k * 2.4f // rings start apart, so moons don't line up
            val mx = cx + cos(an) * r; val my = cy + sin(an) * r * ORBIT_Y
            val (prog, b) = info.getOrElse(i) { 0f to 1f }
            pix.glow(mx, my, 13f, 0x8A6CF0, 0.35f * b)
            val dots = 64
            for (q in 0 until dots) { // a thin ring: bright for the done part
                val f = (q + 0.5f) / dots
                val an2 = -TAU / 4f + f * TAU
                pix.dot(rotRnd(mx + cos(an2) * 9.5f), rotRnd(my + sin(an2) * 9.5f), 1, 1, if (f < prog) 0xB69AFF else 0x4A3478, 0.7f * b)
            }
            // the phase is the progress: new moon at 0, half at 50 %, full at 100 %, waxing
            moonPaint.alpha = b
            val mb = moons[(prog * MOON_STEPS).roundToInt().coerceIn(0, MOON_STEPS)]
            pix.canvas.drawImageRect(mb, IntOffset.Zero, IntSize(MOON_N, MOON_N), IntOffset(rotRnd(mx - MOON_N / 2f), rotRnd(my - MOON_N / 2f)), IntSize(MOON_N, MOON_N), moonPaint)
            out[i * 2] = mx * pxSize; out[i * 2 + 1] = my * pxSize
        }
        out[n * 2] = cx * pxSize; out[n * 2 + 1] = cy * pxSize
        spots[0] = out
        drawIntoCanvas { pix.blit(it, 0, 0, pxSize.toInt()) }

        // names under the moons, readable at any brightness
        for (i in 0 until n) {
            val b = info.getOrElse(i) { 0f to 1f }.second
            val tl = measurer.measure(
                goals[i].name, TextStyle(fontFamily = font, fontSize = 18.sp, lineHeight = 19.sp, color = MoonInk.copy(alpha = b)),
                maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (120f * density).toInt()),
            )
            drawText(tl, topLeft = Offset(out[i * 2] - tl.size.width / 2f, out[i * 2 + 1] + 12f * pxSize))
        }
        if (n == 0) {
            val hint = measurer.measure("TAP THE BRAIN TO ADD A GOAL", TextStyle(fontFamily = pixelFont, fontSize = 9.sp, color = Px.TextDim))
            drawText(hint, topLeft = Offset(cx * pxSize - hint.size.width / 2f, (cy + 48f) * pxSize))
        }
    }
}

/** Orbits are a little flattened, like the main map's ring. */
private const val ORBIT_Y = 0.86f

/** Top strip of the learn view: the trail back to the map, its name, how many goals. */
@Composable
internal fun LearnHeader(count: Int, modifier: Modifier, onBack: () -> Unit) {
    Column(modifier.fillMaxWidth().background(Px.Bg.copy(alpha = 0.86f))) {
        Row(Modifier.fillMaxWidth().height(HEADER_H - 2.dp).padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "< BRAIN >", Modifier.clickable(onClick = onBack).padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                        style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender),
                    )
                    Text("learn", style = TextStyle(fontFamily = TitleFont, fontSize = 22.sp, color = Px.Lavender, shadow = Shadow(Px.Lavender.copy(alpha = 0.6f), blurRadius = 10f)))
                }
                Text(if (count == 1) "1 goal" else "$count goals", style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim))
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
    }
}

// ---------------------------------------------------------------------------------------------
// Today strip (main map, under the main brain)
// ---------------------------------------------------------------------------------------------

/** One line under the main brain: the first due step ("today · Suliko · Panduri") and how many more are due. Follows the camera. */
@Composable
internal fun TodayStrip(due: List<Step>, goals: List<Goal>, today: Long, cam: BrainCamera, pxSize: Float, view: IntSize, onOpen: (Step) -> Unit) {
    val first = due.first()
    val goal = goals.firstOrNull { it.id == first.goalId }?.name.orEmpty()
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = if (isOverdue(first, today)) MoonInk else Px.Lavender)) { append(dayLabel(first.day ?: today, today)) }
        append(" · ${first.title} · $goal")
    }
    val pill = RoundedCornerShape(50)
    Layout(
        content = {
            Row(
                Modifier.clip(pill).background(Color(0xE6140B26)).border(1.dp, Px.Border, pill)
                    .clickable(role = Role.Button) { onOpen(first) }.padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = PageFont, fontSize = 18.sp, lineHeight = 19.sp, color = Px.TextDim),
                )
                if (due.size > 1) Text("  +${due.size - 1}", style = TextStyle(fontFamily = PageFont, fontSize = 18.sp, lineHeight = 19.sp, color = Px.Lavender))
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { ms, c ->
        val u = pxSize * cam.scale
        val ns = nodeScale(cam.scale)
        val ox = ceil(view.width / pxSize) * pxSize / 2f; val oy = ceil(view.height / pxSize) * pxSize / 2f
        val cx = -cam.x * u + ox
        val y = (24f * ns - cam.y) * u + oy + 4.dp.toPx() // just under the core sprite
        // the learn brain sits below this line (see [learnSpot]), so the strip may be wide
        val maxW = min(300.dp.toPx(), c.maxWidth - 16.dp.toPx()).toInt().coerceAtLeast(0)
        val p = ms[0].measure(Constraints(maxWidth = maxW))
        layout(c.maxWidth, c.maxHeight) {
            val m = 8.dp.toPx()
            val x = (cx - p.width / 2f).coerceIn(m, max(m, c.maxWidth - p.width - m))
            p.place(x.roundToInt(), y.roundToInt())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Goal page: the path of steps
// ---------------------------------------------------------------------------------------------

private val RING9 = listOf("..XXXXX..", ".X.....X.", "X.......X", "X.......X", "X.......X", "X.......X", "X.......X", ".X.....X.", "..XXXXX..")
private val DISC9 = listOf("..XXXXX..", ".XXXXXXX.", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", ".XXXXXXX.", "..XXXXX..")
private val CORE9 = listOf("..XXXXX..", ".X.....X.", "X.......X", "X..ooo..X", "X..ooo..X", "X..ooo..X", "X.......X", ".X.....X.", "..XXXXX..")
private val StepDone = Color(0xFFD6C8FF)
private val StepLater = Color(0xFF5F5070)
private val PathLine = Color(0xFF4A3478)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoalPage(goal: Goal, path: List<Step>, focusStep: Long, vm: LearnViewModel, onBack: () -> Unit) {
    val today = LocalDate.now().toEpochDay()
    val time = rememberRotTime()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Step?>(null) }
    var picking by remember { mutableStateOf<Step?>(null) }
    val list = rememberLazyListState()
    val flash = remember { Animatable(0f) }
    LaunchedEffect(goal.id, focusStep) {
        val i = path.indexOfFirst { it.id == focusStep }
        if (i < 0) return@LaunchedEffect
        list.scrollToItem(i)
        flash.snapTo(1f)
        flash.animateTo(0f, tween(1400))
    }
    val current = path.indexOfFirst { !it.done }

    Column(
        Modifier.fillMaxSize().background(Px.Bg)
            .pointerInput(Unit) { detectTapGestures { } }, // the learn view underneath gets nothing
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(HEADER_H - 2.dp).padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "< LEARN", Modifier.clickable(onClick = onBack).padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
                            style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender),
                        )
                        Text(
                            goal.name, Modifier.weight(1f, fill = false).clickable(role = Role.Button) { menu = true }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = TitleFont, fontSize = 22.sp, color = Px.Lavender, shadow = Shadow(Px.Lavender.copy(alpha = 0.6f), blurRadius = 10f)),
                        )
                    }
                    Text("${path.count { it.done }} of ${path.size} done", style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim))
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 560.dp).fillMaxHeight(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 40.dp)) {
                itemsIndexed(path, key = { _, s -> s.id }) { i, s ->
                    val state = if (s.done) 0 else if (i == current) 1 else 2
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                            .background(if (s.id == focusStep) Color(0xFF2A1A40).copy(alpha = 0.8f * flash.value) else Color.Transparent, RoundedCornerShape(8.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // the path: a dotted line through every circle, from the first step to "+ step"
                        Box(Modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                val x = size.width / 2f
                                val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                                if (i > 0) drawLine(PathLine, Offset(x, 0f), Offset(x, size.height / 2f - 10.dp.toPx()), 2.dp.toPx(), pathEffect = dash)
                                drawLine(PathLine, Offset(x, size.height / 2f + 10.dp.toPx()), Offset(x, size.height), 2.dp.toPx(), pathEffect = dash)
                            }
                            Box(
                                Modifier.size(44.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox) {
                                    Sounds.play(Sfx.Tick)
                                    vm.setStepDone(s, !s.done)
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                when (state) {
                                    0 -> PixelArt(DISC9, mapOf('X' to StepDone), 2.dp)
                                    1 -> PixelArt(CORE9, mapOf('X' to Px.Lavender, 'o' to Px.Lavender.copy(alpha = 0.5f)), 2.dp, Modifier.graphicsLayer {
                                        val p = 0.5f + 0.5f * sin(time.floatValue * 2.4f)
                                        alpha = 0.6f + 0.4f * p; scaleX = 1f + 0.08f * p; scaleY = scaleX
                                    })
                                    else -> PixelArt(RING9, mapOf('X' to StepLater), 2.dp)
                                }
                            }
                        }
                        Text(
                            s.title, Modifier.weight(1f).clickable(role = Role.Button) { editing = s }.padding(vertical = 12.dp),
                            style = TextStyle(
                                fontFamily = PageFont, fontSize = 21.sp, lineHeight = 22.sp,
                                color = when (state) { 0 -> Color(0xFFB8A8D0); 1 -> Px.Text; else -> Color(0xFF8A7A9C) },
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        DayChip(s.day, today, overdue = isOverdue(s, today), dim = s.done) { picking = s }
                    }
                }
                item(key = "add") {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.width(44.dp).fillMaxHeight()) {
                            if (path.isNotEmpty()) drawLine(PathLine, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height / 2f - 6.dp.toPx()), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
                        }
                        Text(
                            "+ step", Modifier.clickable(role = Role.Button) { adding = true }.padding(vertical = 10.dp, horizontal = 4.dp),
                            style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, color = Px.Lavender),
                        )
                    }
                }
            }
        }
    }

    if (menu) {
        Dialog(onDismissRequest = { menu = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(220.dp).pixelCard(fill = Px.Elevated, border = Px.Primary, notch = 4.dp).padding(vertical = 6.dp)) {
                for ((label, act) in listOf("rename" to { renaming = true }, "delete" to { deleting = true })) {
                    Text(
                        label, Modifier.fillMaxWidth().clickable(role = Role.Button) { menu = false; act() }.padding(horizontal = 18.dp, vertical = 10.dp),
                        style = TextStyle(fontFamily = PageFont, fontSize = 21.sp, color = if (label == "delete") Px.Lavender else Px.Text),
                    )
                }
            }
        }
    }
    if (renaming) NameDialog("RENAME GOAL", "Name", initial = goal.name, submit = "SAVE", onDismiss = { renaming = false }, onSubmit = { vm.renameGoal(goal.id, it) })
    if (deleting) {
        PixelDialog({ deleting = false }) {
            Text("DELETE ${goal.name.uppercase()}?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, lineHeight = 18.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text("Its steps go with it, forever. No undo.", style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { deleting = false })
                PixelButton("DELETE", { deleting = false; vm.deleteGoal(goal.id) }, primary = true)
            }
        }
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        fun submit() { vm.addStep(goal.id, text); adding = false }
        PixelDialog({ adding = false }) {
            Text("NEW STEP", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
            Spacer(Modifier.height(16.dp))
            PixelField(text, { text = it.take(80) }, "What comes next?", onDone = ::submit, cursor = Px.Lavender)
            Spacer(Modifier.height(18.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("CANCEL", { adding = false })
                PixelButton("ADD", ::submit, primary = true)
            }
        }
    }
    picking?.let { s -> DayPicker(s.day, onDismiss = { picking = null }) { d -> picking = null; vm.setStepDay(s, d) } }
    editing?.let { s -> StepCard(s, today, vm, onClose = { editing = null }) }
}

/** The day on a step's right: "today", "tomorrow", "Nov 20", or "+ day". Overdue is a brighter lavender, never red. */
@Composable
private fun DayChip(day: Long?, today: Long, overdue: Boolean, dim: Boolean, onClick: () -> Unit) {
    val pill = RoundedCornerShape(50)
    val (ink, line) = when {
        day == null -> Px.TextDim.copy(alpha = 0.6f) to Px.Border
        dim -> StepLater to Px.Border
        overdue -> MoonInk to Color(0xFF9C84FF)
        day == today -> Px.Lavender to Px.Lavender.copy(alpha = 0.7f)
        else -> Px.TextDim to Px.Border
    }
    Text(
        if (day == null) "+ day" else dayLabel(day, today),
        Modifier.clip(pill).border(1.dp, line, pill).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        maxLines = 1,
        style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, lineHeight = 18.sp, color = ink),
    )
}

/** A step's small card: title, note, day, delete. Saves on SAVE only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(step: Step, today: Long, vm: LearnViewModel, onClose: () -> Unit) {
    var title by remember(step.id) { mutableStateOf(step.title) }
    var note by remember(step.id) { mutableStateOf(step.note) }
    var day by remember(step.id) { mutableStateOf(step.day) }
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    PixelDialog(onClose) {
        Text("STEP", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(14.dp))
        PixelField(title, { title = it.take(80) }, "Title", cursor = Px.Lavender)
        Spacer(Modifier.height(10.dp))
        PixelField(note, { note = it }, "Note", singleLine = false, minHeight = 90.dp, cursor = Px.Lavender, modifier = Modifier.heightIn(max = 220.dp))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DAY", Modifier.padding(end = 10.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.TextDim))
            DayChip(day, today, overdue = !step.done && (day ?: Long.MAX_VALUE) < today, dim = false) { picking = true }
        }
        Spacer(Modifier.height(18.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("DELETE", { deleting = true })
            PixelButton("CANCEL", onClose)
            PixelButton("SAVE", { vm.editStep(step, title, note, day); onClose() }, primary = true)
        }
    }
    if (picking) DayPicker(day, onDismiss = { picking = false }) { day = it; picking = false }
    if (deleting) {
        PixelDialog({ deleting = false }) {
            Text("DELETE STEP?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            Text("Gone for good. No undo.", style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { deleting = false })
                PixelButton("DELETE", { deleting = false; vm.deleteStep(step.id); onClose() }, primary = true)
            }
        }
    }
}
