package app.vesper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.sp
import app.vesper.DailyStat
import app.vesper.STATS_COUNT_DAYS
import app.vesper.data.DailyLog
import app.vesper.data.Idea
import app.vesper.dailyStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.sin

/** The card's count: "2 / 4", lit of all. */
internal fun dailyCount(items: List<Idea>) = "${items.count { it.done }} / ${items.size}"
/** The daily brain's brightness on the map: dim while an item is undone, fully lit when all are done or there are none. */
internal fun dailyBrightness(items: List<Idea>) = if (items.any { !it.done }) DAILY_DIM else 1f
internal const val DAILY_DIM = 0.45f
/** A stats row's count: "pills 11 of 14 days" (the name is beside it). */
internal fun statsCount(s: DailyStat) = "${s.doneRecent} of $STATS_COUNT_DAYS days"

private val CardPurple = Color(0xFF9C84FF)
private val NameInk = Color(0xFFE6DCFF)
private val NameDim = Color(0xFF8F82A8)

// A pixel candle, 9 x 20: flame rows on top (lit only), then the wick and the wax. W white flame core, F lilac flame, K the wick,
// T the wax's lit top, B the wax, S its shaded side, D a drip.
private val CANDLE_ROWS = listOf(
    "....F....",
    "...FF....",
    "...FWF...",
    "..FWWF...",
    "..FWWWF..",
    "..FWWWF..",
    "...FWF...",
    "....K....",
    ".TTTTTTT.",
    ".BDBBBBS.",
    ".BDBBBBS.",
    ".BDBBBBS.",
    ".BBBBBBS.",
    ".BBBBBBS.",
    ".BBBBBBS.",
    ".BBBBBBS.",
    ".BBBBBBS.",
    ".BBBBBBS.",
    "SSSSSSSSS",
    ".SSSSSSS.",
)
internal const val CANDLE_W = 9
internal const val CANDLE_H = 20
private const val FLAME_ROWS = 7
private const val CANDLE_FX_S = 0.4f // how long the light-up flare or the smoke wisp lasts

/**
 * The candle's pixels as (x, y, rgb), [lit] or not, at flicker [frame] (0..2: the flame's tip leans left, stands, leans right). An
 * unlit candle is dark purple wax with a cold wick and no flame; a lit one has lighter wax and a white and lilac flame.
 */
internal fun candlePixels(lit: Boolean, frame: Int = 1): List<Triple<Int, Int, Int>> {
    val out = ArrayList<Triple<Int, Int, Int>>()
    CANDLE_ROWS.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch ->
            if (ch == '.' || (!lit && y < FLAME_ROWS)) return@forEachIndexed
            val xx = if (lit && y < 2) x + (frame - 1) else x // the tip sways
            val rgb = when (ch) {
                'W' -> 0xFFFFFF; 'F' -> 0xCDB8FF
                'K' -> if (lit) 0xFFFFFF else 0x2A1A48
                'T' -> if (lit) 0xF3EEFF else 0x5A3E8A
                'B' -> if (lit) 0xD6C8FF else 0x4A3478
                'S' -> if (lit) 0x9C84FF else 0x2A1A48
                'D' -> if (lit) 0xF3EEFF else 0x5A3E8A
                else -> return@forEachIndexed
            }
            out.add(Triple(xx, y, rgb))
        }
    }
    return out
}

/**
 * The daily brain opened: a card grown from [from] (px in the root) with a row of candles, one per item, its name under it. A tap
 * lights an unlit candle or snuffs a lit one ([onToggle]); a long press gives rename and delete ([onItemMenu], with where it was
 * pressed, px in the root; held and moved instead, the candle is dragged to a new place and [onReorder] gets all the uids in the new order);
 * "+ add" adds one; "stats" shows the last weeks from [log]. Back, CLOSE, a tap outside or [closeSignal]
 * closes it (back leaves the stats first).
 */
@Composable
internal fun DailyCard(
    items: List<Idea>,
    log: List<DailyLog>,
    from: Offset,
    closeSignal: Int,
    onToggle: (Idea) -> Unit,
    onItemMenu: (Idea, Offset) -> Unit,
    onReorder: (List<String>) -> Unit,
    onAdd: () -> Unit,
    onClosed: () -> Unit,
) {
    val p = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(300, easing = CubicBezierEasing(0.34f, 1.35f, 0.64f, 1f))) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { p.animateTo(0f, tween(220, easing = FastOutSlowInEasing)); onClosed() }
    }
    var seenSignal by remember { mutableIntStateOf(closeSignal) }
    LaunchedEffect(closeSignal) { if (closeSignal != seenSignal) { seenSignal = closeSignal; close() } }
    BackHandler(true) { if (showStats) showStats = false else close() }
    val shape = RoundedCornerShape(14.dp)
    val font = PageFont
    val time = rememberRotTime()
    // the order while a candle is being dragged (items follow only after it is saved); rects = where each candle sits (root px)
    val local = remember { items.toMutableStateList() }
    var dragUid by remember { mutableStateOf<String?>(null) }
    val rects = remember { HashMap<String, Rect>() }
    val settled = remember { booleanArrayOf(true) } // false from a swap until the dragged candle reports its new place, so it cannot swap back
    LaunchedEffect(items) { if (dragUid == null) { local.clear(); local.addAll(items) } }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { close() } })
        Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 380.dp).fillMaxWidth()
                    .growFrom(from, { p.value })
                    .clip(shape).background(Color(0xF20D0618)).border(1.5.dp, CardPurple, shape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(bottom = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (showStats) "stats" else "daily", Modifier.semantics { contentDescription = if (showStats) "daily stats" else "daily" },
                        style = TextStyle(fontFamily = TitleFont, fontSize = 30.sp, color = Px.Lavender, shadow = Shadow(Px.Lavender.copy(alpha = 0.6f), blurRadius = 12f)),
                    )
                    Text(
                        if (showStats) "last 4 weeks" else dailyCount(items), Modifier.weight(1f).padding(start = 12.dp, top = 6.dp),
                        style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.TextDim),
                    )
                    Text("CLOSE", Modifier.clickable(onClick = ::close).padding(10.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender))
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (showStats) {
                        val stats = remember(items, log) { dailyStats(items, log, java.time.LocalDate.now()) }
                        if (stats.isEmpty()) Text("NOTHING TO COUNT YET", Modifier.fillMaxWidth().padding(16.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim, textAlign = TextAlign.Center))
                        for (s in stats) StatsRow(s, font)
                    } else {
                        if (items.isEmpty()) Text("NO CANDLES YET. + ADD ONE", Modifier.fillMaxWidth().padding(16.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim, textAlign = TextAlign.Center))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (i in local) key(i.uid) {
                                Candle(
                                    i, font, { time.floatValue }, { onToggle(i) }, { at -> onItemMenu(i, at) },
                                    onRect = { rects[i.uid] = it; if (i.uid == dragUid) settled[0] = true },
                                    onDragStart = { dragUid = i.uid },
                                    onDragTo = { c ->
                                        val over = if (settled[0]) local.firstOrNull { it.uid != i.uid && rects[it.uid]?.contains(c) == true } else null
                                        if (over != null) { local.add(local.indexOfFirst { it.uid == over.uid }, local.removeAt(local.indexOfFirst { it.uid == i.uid })); settled[0] = false }
                                    },
                                    onDragEnd = { dragUid = null; settled[0] = true; onReorder(local.map { it.uid }) },
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (!showStats) Chip("+ add", onAdd) else Spacer(Modifier.width(1.dp))
                    Chip(if (showStats) "< candles" else "stats") { showStats = !showStats }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    val pill = RoundedCornerShape(50)
    Text(
        text,
        Modifier.clip(pill).border(1.dp, CardPurple, pill).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender),
    )
}

/**
 * One item: its candle and its name; a tap lights or snuffs it, a long press (released without moving) asks rename or delete, a long
 * press and a move drags it ([onDragTo] gets its centre in the root; the others slide to their new places).
 */
@Composable
private fun Candle(
    item: Idea, font: androidx.compose.ui.text.font.FontFamily, time: () -> Float, onTap: () -> Unit, onLong: (Offset) -> Unit,
    onRect: (Rect) -> Unit, onDragStart: () -> Unit, onDragTo: (Offset) -> Unit, onDragEnd: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var held by remember { mutableStateOf(false) }
    var grabFrom by remember { mutableStateOf(Offset.Zero) } // where the slot was when the drag began
    var grab by remember { mutableStateOf(Offset.Zero) } // how far the finger has moved since
    var lastAt by remember { mutableStateOf<Offset?>(null) }
    val slide = remember { Animatable(Offset.Zero, Offset.VectorConverter) } // the glide from the old slot to the new one
    val scope = rememberCoroutineScope()
    val name = ideaLabel(item).ifBlank { "..." }
    val still = remember(item.done) { candlePixels(item.done, 1) }
    val frames = remember(item.done) { if (item.done) listOf(candlePixels(true, 0), still, candlePixels(true, 2)) else listOf(still) }
    val seed = (item.uid.hashCode() and 0xFF) * 0.07f
    // lit or snuffed after the card opened: flare and sparks, or a wisp of smoke, for [CANDLE_FX_S]
    var flicker by remember { mutableStateOf(false) }
    var changeT by remember { mutableFloatStateOf(0f) }
    var seen by remember { mutableStateOf(item.done) }
    LaunchedEffect(item.done) {
        if (item.done == seen) return@LaunchedEffect
        seen = item.done; changeT = time(); flicker = true
        delay((CANDLE_FX_S * 1000f).toLong() + 40); flicker = false
    }
    Column(
        Modifier.width(76.dp)
            .onGloballyPositioned {
                val p = it.positionInRoot()
                origin = p; onRect(Rect(p, it.size.toSize()))
                val prev = lastAt; lastAt = p
                if (prev != null && prev != p && !held) scope.launch { slide.snapTo(slide.value + prev - p); slide.animateTo(Offset.Zero, tween(180)) }
            }
            .zIndex(if (held) 1f else 0f)
            // keyed on done too: the gesture block keeps its first onTap, whose item was still unlit, so a lit candle never snuffed
            .pointerInput(item.id, item.done) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val press = awaitLongPressOrCancellation(down.id)
                    if (press == null) { // lifted early = a tap; moved off = not ours
                        if (currentEvent.changes.any { it.id == down.id && !it.pressed }) onTap()
                        return@awaitEachGesture
                    }
                    var total = Offset.Zero
                    val ok = drag(press.id) { c ->
                        total += c.positionChange(); c.consume()
                        if (!held && total.getDistance() > viewConfiguration.touchSlop) { held = true; grabFrom = origin; onDragStart() }
                        if (held) { grab = total; onDragTo(grabFrom + total + Offset(size.width / 2f, size.height / 2f)) }
                    }
                    if (held) {
                        scope.launch { slide.snapTo(grabFrom + grab - origin); held = false; slide.animateTo(Offset.Zero, tween(180)) }
                        onDragEnd()
                    } else if (ok) onLong(origin + press.position)
                }
            }
            .graphicsLayer {
                val t = if (held) grabFrom + grab - origin else slide.value
                translationX = t.x; translationY = t.y
            }
            .semantics(mergeDescendants = true) { role = Role.Checkbox; contentDescription = name; stateDescription = if (item.done) "lit" else "unlit" }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(width = 48.dp, height = 84.dp)) {
            val px = floor(size.height / (CANDLE_H + 1)).coerceAtLeast(1f)
            val left = floor((size.width - CANDLE_W * px) / 2f); val top = floor(size.height - CANDLE_H * px)
            // 0..1 over the 400 ms after a tap lit or snuffed it; the clock is only read (and so only redraws) meanwhile
            val ap = if (flicker) ((time() - changeT) / CANDLE_FX_S).coerceIn(0f, 1f) else 1f
            if (item.done) {
                val t = time()
                val flare = hump(ap) // the flame flares up once
                val glowA = 0.32f + 0.08f * sin(t * 7f + seed) + 0.35f * flare
                val c = Offset(left + 4.5f * px, top + 4f * px)
                val gr = (9f + 6f * flare) * px
                drawCircle(Brush.radialGradient(listOf(Color(0xFFB69AFF).copy(alpha = glowA.coerceAtMost(1f)), Color.Transparent), c, gr), radius = gr, center = c)
                val f = frames[((t * 6f + seed).toInt() % 3 + 3) % 3]
                for ((x, y, rgb) in f) drawRect(Color(0xFF000000.toInt() or rgb), Offset(left + x * px, top + y * px), Size(px, px))
                if (flare > 0.3f) drawRect(Color.White.copy(alpha = flare), Offset(left + 4f * px, top - px), Size(px, px)) // the tip stretches up
                if (ap < 1f) for (i in 0 until 4) { // a few sparks rise from the flame and fade
                    val sx = left + (4f + (hf(i, 1) - 0.5f) * 5f) * px + sin(ap * 6f + i) * px
                    val sy = top + 2f * px - ap * (8f + hf(i, 2) * 8f) * px
                    drawRect((if (i % 2 == 0) Color.White else Color(0xFFCDB8FF)).copy(alpha = 1f - ap), Offset(floor(sx / px) * px, floor(sy / px) * px), Size(px, px))
                }
            } else {
                for ((x, y, rgb) in still) drawRect(Color(0xFF000000.toInt() or rgb), Offset(left + x * px, top + y * px), Size(px, px))
                if (ap < 1f) for (i in 0 until 4) { // a wisp of smoke curls up off the wick
                    val q = (ap + i * 0.08f).coerceAtMost(1f)
                    val sx = left + 4f * px + sin(q * 7f + i * 1.6f) * 1.5f * px
                    val sy = top + 6f * px - q * 12f * px
                    drawRect(Color(0xFF8F82A8).copy(alpha = 0.55f * (1f - q)), Offset(floor(sx / px) * px, floor(sy / px) * px), Size(px, px))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = font, fontSize = 18.sp, lineHeight = 18.sp, color = if (item.done) NameInk else NameDim, textAlign = TextAlign.Center),
        )
    }
}

/** A stats row: the item's name and "11 of 14 days", then 28 small squares, oldest first (lit = bright lilac, unlit = dark). */
@Composable
private fun StatsRow(s: DailyStat, font: androidx.compose.ui.text.font.FontFamily) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "${ideaLabel(s.item)} ${statsCount(s)}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ideaLabel(s.item).ifBlank { "..." }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = font, fontSize = 20.sp, color = NameInk))
            Text(statsCount(s), style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim))
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val n = s.days.size
            val cell = size.width / n
            val sq = floor(minOf(cell - 2f, size.height)).coerceAtLeast(1f)
            s.days.forEachIndexed { i, done ->
                drawRect(if (done) Color(0xFFD6C8FF) else Color(0xFF2A1A48), Offset(floor(i * cell), floor((size.height - sq) / 2f)), Size(sq, sq))
            }
        }
    }
}
