package app.vesper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.data.Idea
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// The main brain's sky: every note one star with its name, in one even, borderless spread (pure, tested in BrainCardTest)
// ---------------------------------------------------------------------------------------------

/** The laid-out sky: one star per note, in dp from the sky's top-left; each carries a title up to [titleW] wide below it; [h] dp tall. */
internal class Sky(val stars: List<Offset>, val titleW: Float, val h: Float)

internal const val STAR_REACH = 10f // dp a star reaches round its spot: its twinkle and drift
internal const val STAR_TITLE_GAP = 12f // dp from a star's spot down to its title
internal const val STAR_TITLE_H = 36f // dp a title takes: two lines
private const val CELL_W = 80f
private const val MAX_CELL_W = 120f
private const val CELL_H = 72f
private const val MIN_W = 140f
private const val SKY_M = 16f

/**
 * The sky [w] dp wide for notes with one seed each (from the uid, so a star sits the same on every device), in the order given
 * (row by row). Equal cells fill the width; a star swings a little off its cell and every other column hangs a little lower, so
 * it reads as stars, not a grid; titles are cut to keep clear of the neighbours' swing. A long sky scrolls.
 */
internal fun skyLayout(seeds: IntArray, w: Float): Sky {
    val avail = max(MIN_W, w - 2f * SKY_M)
    val n = max(1, seeds.size)
    val cols = max(1, (avail / CELL_W).toInt())
    val cw = min(avail / cols, MAX_CELL_W)
    val left = SKY_M + (avail - cw * cols) / 2f
    val top = SKY_M + STAR_REACH
    val stagger = if (cols > 1) CELL_H * 0.35f else 0f
    val swing = min(12f, cw * 0.15f)
    val stars = seeds.mapIndexed { j, seed ->
        val r = Rng(seed); val c = j % cols
        Offset(left + cw * (c + 0.5f) + (r.next() - 0.5f) * swing, top + (j / cols) * CELL_H + (c % 2) * stagger + (r.next() - 0.5f) * 10f)
    }
    val rows = (n + cols - 1) / cols
    return Sky(stars, cw - swing - 4f, top + (rows - 1) * CELL_H + stagger + 5f + STAR_TITLE_GAP + STAR_TITLE_H + SKY_M)
}

// ---------------------------------------------------------------------------------------------
// The card
// ---------------------------------------------------------------------------------------------

private val CardPurple = Color(0xFF9C84FF)

/**
 * The main brain opened: a card over almost the whole view, grown from [from] (px in the root) like a note's card, the map
 * faint behind it. Inside, every note of [notes] is a gently twinkling, drifting star with its name, newest first. A star (or
 * its title) opens its note; the title the main brain's menu ([onTitle]). Back, CLOSE, a tap outside or [closeSignal] (the Brain
 * tab tapped again) shrinks it away, then [onClosed].
 */
@Composable
internal fun BrainCard(
    notes: List<Idea>,
    from: Offset,
    closeSignal: Int,
    onTitle: (Offset) -> Unit,
    onOpenIdea: (Idea, Offset) -> Unit,
    onClosed: () -> Unit,
    /** The card's name: the main brain's notes, or the pinned ones. */
    title: String = "main brain",
) {
    val p = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Sounds.play(Sfx.Open); p.animateTo(1f, tween(320, easing = CubicBezierEasing(0.34f, 1.35f, 0.64f, 1f))) }
    fun close() {
        if (closing) return
        closing = true
        Sounds.play(Sfx.Close)
        scope.launch { p.animateTo(0f, tween(240, easing = FastOutSlowInEasing)); onClosed() }
    }
    var seenSignal by remember { mutableIntStateOf(closeSignal) }
    LaunchedEffect(closeSignal) { if (closeSignal != seenSignal) { seenSignal = closeSignal; close() } }
    BackHandler(true, ::close)

    val count = notes.size
    val shape = RoundedCornerShape(18.dp)
    var titleAt by remember { mutableStateOf(Offset.Zero) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(Unit) { detectTapGestures { close() } },
        )
        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 1400.dp).fillMaxSize()
                    .growFrom(from, { p.value })
                    .clip(shape).background(Color(0xF20D0618)).border(1.5.dp, CardPurple, shape)
                    .pointerInput(Unit) { detectTapGestures { } }, // taps on the card itself never reach the map behind
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        Modifier.onGloballyPositioned { titleAt = it.boundsInRoot().center }.clickable { onTitle(titleAt) }.padding(vertical = 4.dp),
                        style = TextStyle(fontFamily = TitleFont, fontSize = 30.sp, color = Px.Lavender, shadow = Shadow(Px.Lavender.copy(alpha = 0.6f), blurRadius = 12f)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(if (count == 1) "1 note" else "$count notes", Modifier.weight(1f), style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim))
                    Text("CLOSE", Modifier.clickable(onClick = ::close).padding(10.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender))
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    if (count == 0) {
                        Text(
                            "NO NOTES YET. TAP \"${title.uppercase()}\" FOR + NOTE", Modifier.align(Alignment.Center).padding(24.dp),
                            style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim, textAlign = TextAlign.Center),
                        )
                    } else {
                        SkyView(notes, maxWidth.value, constraints.maxHeight, onOpenIdea)
                    }
                }
            }
        }
    }
}

/** The scrolling sky inside the card: a star and title for each of [notes]. [viewH] px is its window. */
@Composable
private fun SkyView(notes: List<Idea>, w: Float, viewH: Int, onOpenIdea: (Idea, Offset) -> Unit) {
    val density = LocalDensity.current.density
    val u = rememberRotPxSize()
    val time = rememberRotTime()
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val pageFont = PageFont // composable getter, read outside remember
    val sky = remember(notes, w) { skyLayout(IntArray(notes.size) { notes[it].uid.hashCode() }, w) }
    val titles = remember(sky, density) {
        notes.map { i ->
            measurer.measure(
                ideaLabel(i).ifBlank { "..." },
                TextStyle(fontFamily = pageFont, fontSize = 15.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, color = if (i.done) Color(0xFF6F6880) else Color(0xFFE6DCFF).copy(alpha = 0.88f)),
                overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = (sky.titleW * density).toInt().coerceAtLeast(20)),
            )
        }
    }
    val scroll = rememberScrollState()
    var origin by remember { mutableStateOf(Offset.Zero) } // the sky's top-left in the root

    Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Canvas(
            Modifier.fillMaxWidth().height(sky.h.dp)
                .onGloballyPositioned { origin = it.positionInRoot() }
                .pointerInput(sky, titles) {
                    detectTapGestures { tap ->
                        val d = density
                        // a star, or its title
                        var best = -1; var bestD = 22.dp.toPx()
                        sky.stars.forEachIndexed { j, s ->
                            val at = Offset(s.x * d, s.y * d)
                            val dist = (at - tap).getDistance()
                            val tl = titles[j].size
                            val inTitle = tap.x in at.x - tl.width / 2f..at.x + tl.width / 2f && tap.y in at.y + STAR_TITLE_GAP * d..at.y + STAR_TITLE_GAP * d + tl.height
                            if (dist <= bestD || (best < 0 && inTitle)) { best = j; bestD = min(bestD, dist) }
                        }
                        if (best >= 0) { val s = sky.stars[best]; onOpenIdea(notes[best], origin + Offset(s.x * d, s.y * d)) }
                    }
                },
        ) {
            val t = time.value
            val top = scroll.value.toFloat() - 60.dp.toPx(); val bottom = scroll.value + viewH + 60.dp.toPx()
            fun snap(v: Float) = round(v / u) * u
            // faint dust between the stars
            val dustN = min(400, (size.width * size.height / (90f * 90f * density * density)).toInt())
            val r = Rng(77)
            for (i in 0 until dustN) {
                val x = r.next() * size.width; val y = r.next() * size.height; val ph = r.next() * TAU
                if (y < top || y > bottom) continue
                drawRect(Color(0xFFCDB8FF).copy(alpha = 0.06f + 0.16f * max(0f, sin(t * 0.9f + ph))), Offset(snap(x), snap(y)), Size(u, u))
            }
            sky.stars.forEachIndexed { j, s ->
                val y = s.y * density
                if (y < top || y > bottom) return@forEachIndexed
                val x = s.x * density
                val idea = notes[j]
                val ph = (idea.uid.hashCode() and 0xFFFF) / 65536f * TAU
                val sx = snap(x + sin(t * 0.23f + ph) * u - u / 2f); val sy = snap(y + cos(t * 0.19f + ph * 1.7f) * u - u / 2f)
                star(sx, sy, u, 0.5f + 0.5f * sin(t * 1.3f + ph), idea.done)
                val tl = titles[j]
                drawText(tl, topLeft = Offset(x - tl.size.width / 2f, y + STAR_TITLE_GAP * density))
            }
        }
    }
}

/** A pixel star with its top-left at ([x], [y]), [u] px a pixel: a white heart, lavender arms that grow as it twinkles ([tw]), a soft glow. Done: one grey pixel. */
private fun DrawScope.star(x: Float, y: Float, u: Float, tw: Float, done: Boolean) {
    if (done) { drawRect(Color(0xFF6F6880), Offset(x, y), Size(u, u)); return }
    drawCircle(Color(0xFF9C84FF).copy(alpha = 0.10f + 0.10f * tw), 5f * u, Offset(x + u / 2f, y + u / 2f))
    drawRect(Color.White, Offset(x, y), Size(u, u))
    val arm = if (tw > 0.7f) 3 else 2
    for (k in 1..arm) {
        val c = Color(0xFFD6C8FF).copy(alpha = (0.75f + 0.25f * tw) * (1f - k / (arm + 1f)))
        drawRect(c, Offset(x - k * u, y), Size(u, u)); drawRect(c, Offset(x + k * u, y), Size(u, u))
        drawRect(c, Offset(x, y - k * u), Size(u, u)); drawRect(c, Offset(x, y + k * u), Size(u, u))
    }
}
