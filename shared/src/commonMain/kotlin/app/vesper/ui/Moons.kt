package app.vesper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vesper.data.DayFocus
import app.vesper.data.Goal
import app.vesper.data.Lobe
import app.vesper.DayKind
import app.vesper.DayRecord
import app.vesper.dayRecord
import app.vesper.data.DailyLog
import app.vesper.data.Idea
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// The Moons tab: a month calendar where every day wears its real moon phase; a tapped day shows its Daily record.

private const val MoonsBase = 0x06030C
internal val MoonsText = Color(0xFFC9BCD9)
private val MoonsOutside = Color(0xFF3A2C48)
private val MoonsToday = Color(0xFFE2C8FF)
internal val MoonsDim = Color(0xFF6F6080)
internal val MoonsLabel = Color(0xFF7A6A8C)
private val MoonsMonth = Color(0xFFA99AB9)
internal val MoonsArrow = Color(0xFFB69AFF)
internal val MoonsBarBg = Color(0xFF0E0814)
internal val MoonsSelBg = Color(0xFF1C1128)
internal val MoonsSelLine = Color(0xFF7540B8)
internal val MoonsSheetBg = Color(0xFF120A19)
internal val MoonsSheetLine = Color(0xFF2A1A38)
internal val MoonsMenuBg = Color(0xFF160D1F)
internal val MoonsMenuLine = Color(0xFF3A2450)
internal val MoonsInk = Color(0xFFEDE4F7)


private val CHEV_L = listOf("...X", "..X.", ".X..", "X...", ".X..", "..X.", "...X")
private val CHEV_R = listOf("X...", ".X..", "..X.", "...X", "..X.", ".X..", "X...")
private val zone: ZoneId get() = ZoneId.systemDefault()

// ---------------------------------------------------------------------------------------------
// Moon phase
// ---------------------------------------------------------------------------------------------

private const val SYNODIC = 29.530588853
private const val NEW_MOON_MS = 947182440000L // 2000-01-06 18:14 UTC

private fun moonFrac(day: LocalDate): Double {
    val ms = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val days = (ms - NEW_MOON_MS) / 86400000.0
    return (((days % SYNODIC) + SYNODIC) % SYNODIC) / SYNODIC
}

// Crater centres (unit-disc coords) and radii; each shows only where the moon is lit.
private val CRATERS = floatArrayOf(0.28f, 0.28f, 0.16f, 0.5f, -0.1f, 0.13f, -0.1f, 0.58f, 0.12f, -0.45f, 0.3f, 0.1f, 0.12f, -0.62f, 0.09f)

private fun mixRgb(a: Int, b: Int, t: Double): Int {
    fun ch(sh: Int) = (((a shr sh) and 255) * (1 - t) + ((b shr sh) and 255) * t).toInt()
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

internal fun moonBitmap(day: LocalDate, n: Int, lit: Int, hi: Int, crater: Int, dark: Int = 0x1A0F26): ImageBitmap = moonPhase(moonFrac(day), n, lit, hi, crater, dark)

/**
 * The moon at [frac] of its cycle: 0 new, 0.25 half (waxing), 0.5 full. Crisp pixels: the lit side has a bright limb ([hi]) and
 * crater pixels; the dark side keeps a faint rim so a new moon still reads as a disc.
 */
internal fun moonPhase(frac: Double, n: Int, lit: Int, hi: Int, crater: Int, dark: Int = 0x1A0F26): ImageBitmap {
    val t = cos(frac * 2 * PI)
    val px = IntArray(n * n)
    val rad = n / 2.0 - 0.5
    val limb = (1 - 1.1 / rad).let { it * it }
    val rim = mixRgb(dark, lit, 0.3)
    for (y in 0 until n) for (x in 0 until n) {
        val dx = (x + 0.5 - n / 2.0) / rad
        val dy = (y + 0.5 - n / 2.0) / rad
        val d2 = dx * dx + dy * dy
        if (d2 > 1.05) continue
        val w = sqrt(max(0.0, 1 - dy * dy))
        val isLit = if (frac < 0.5) dx > t * w else dx < -t * w
        var c = when {
            !isLit -> if (d2 > limb) rim else dark
            d2 > limb -> hi
            else -> lit
        }
        // a crater is at least one pixel across, so even the 12 px moons show them
        if (isLit && d2 <= limb) for (k in 0 until CRATERS.size / 3) {
            if (hypot(dx - CRATERS[k * 3], dy - CRATERS[k * 3 + 1]) <= max(CRATERS[k * 3 + 2].toDouble(), 0.7 / rad)) c = crater
        }
        px[y * n + x] = 0xFF000000.toInt() or c
    }
    return imageBitmapOf(px, n, n)
}

internal fun dayMoon(day: LocalDate, n: Int) = moonBitmap(day, n, 0xC9B0FF, 0xE6DCFF, 0x8F6BD0)

internal fun cloudBitmap(wc: Int, hc: Int, seed: Int): ImageBitmap {
    val px = IntArray(wc * hc)
    for (y in 0 until hc) for (x in 0 until wc) for (i in 0 until 5) {
        val ex = (x + 0.5 - wc * (i + 0.5) / 5) / (wc / 5 + 2)
        val ey = (y + 0.5 - hc) / (hc * (0.4 + 0.55 * hf(seed, i)))
        if (ex * ex + ey * ey <= 1.0) {
            px[y * wc + x] = if (y > hc * 0.7) 0xE6140A1E.toInt() else 0xE61A0F26.toInt()
            break
        }
    }
    return imageBitmapOf(px, wc, hc)
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

@Composable
fun MoonsScreen(
    items: List<Idea>,
    log: List<DailyLog>,
    doneOn: suspend (LocalDate) -> List<String>,
    onToggle: (Idea, Boolean, LocalDate, () -> Unit) -> Unit,
    resetSignal: Int,
) {
    val time = rememberRotTime()
    val pxSize = rememberRotPxSize()
    val density = LocalDensity.current
    val today = LocalDate.now(zone)

    var selected by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var monthIdx by rememberSaveable { mutableStateOf(today.year * 12 + today.monthValue - 1) }
    val month = YearMonth.of(monthIdx / 12, monthIdx % 12 + 1)
    val selDate = LocalDate.ofEpochDay(selected)

    // Re-tapping the tab jumps to today (the value at entry is ignored).
    val seenReset = remember { intArrayOf(resetSignal) }
    LaunchedEffect(resetSignal) {
        if (seenReset[0] != resetSignal) {
            seenReset[0] = resetSignal
            val t = LocalDate.now(zone)
            selected = t.toEpochDay()
            monthIdx = t.year * 12 + t.monthValue - 1
        }
    }

    // The selected day's record: the uids done that day (re-read whenever the log changes, e.g. a candle lit on the Brain tab).
    var doneUids by remember { mutableStateOf<List<String>?>(null) }
    // bumped after a tap's write, so a day older than the observed log window still refreshes
    var wrote by remember { mutableStateOf(0) }
    LaunchedEffect(selected, log, wrote) { doneUids = try { doneOn(selDate) } catch (e: Exception) { emptyList() } }
    val record = remember(items, doneUids, selected) { doneUids?.let { dayRecord(items, it, selDate, today) } }
    // Days where every Daily item was done, from the recent log (the Brain tab keeps 28 days of it).
    val doneByDay = remember(log) { log.filter { it.done }.groupBy({ it.date }, { it.itemUid }) }

    var picking by remember { mutableStateOf(false) }
    // the day-record sheet fades in (200 ms) each time another day is picked
    val sheetA = remember { Animatable(1f) }
    LaunchedEffect(selected) { sheetA.snapTo(0.3f); sheetA.animateTo(1f, tween(200)) }

    val goMonth = rememberUpdatedState { delta: Int -> monthIdx += delta }

    Box(Modifier.fillMaxSize()) {
        MoonsBackdrop(time, pxSize, today)
        Column(Modifier.fillMaxSize()) {
            MoonsHeader(month, onPrev = { goMonth.value(-1) }, onNext = { goMonth.value(1) }, onPick = { picking = true })
            val gridPart: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    for (d in listOf("M", "T", "W", "T", "F", "S", "S")) {
                        Text(
                            d, Modifier.weight(1f), textAlign = TextAlign.Center,
                            style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, color = MoonsDim),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                val swipePx = with(density) { 60.dp.toPx() }
                // the month slides in from the side it lies on (a quarter of the width, 200 ms) while the old one slides off and fades
                AnimatedContent(
                    targetState = monthIdx,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(200)) { it * dir / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(tween(200)) { -it * dir / 4 } + fadeOut(tween(120)))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .pointerInput(Unit) {
                            var acc = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { acc = 0f },
                                onDragCancel = { acc = 0f },
                                onDragEnd = {
                                    if (acc > swipePx) goMonth.value(-1) else if (acc < -swipePx) goMonth.value(1)
                                    acc = 0f
                                },
                            ) { _, dx -> acc += dx }
                        },
                    label = "month",
                ) { idx ->
                    val month = YearMonth.of(idx / 12, idx % 12 + 1)
                    val gridStart = remember(idx) {
                        val first = month.atDay(1)
                        first.minusDays((first.dayOfWeek.value - 1).toLong())
                    }
                    val moons = remember(gridStart) { Array(42) { dayMoon(gridStart.plusDays(it.toLong()), MOON_CELL_PX) } }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (row in 0 until 6) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (col in 0 until 7) {
                                val i = row * 7 + col
                                val date = gridStart.plusDays(i.toLong())
                                val ed = date.toEpochDay()
                                DayCell(
                                    date = date,
                                    inMonth = date.month == month.month && date.year == month.year,
                                    isToday = date == today,
                                    isSelected = ed == selected,
                                    moon = moons[i],
                                    allDone = dayRecord(items, doneByDay[date.toString()].orEmpty(), date, today).allDone,
                                    time = time,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    selected = ed
                                    monthIdx = date.year * 12 + date.monthValue - 1
                                }
                            }
                        }
                    }
                    }
                }
            }
            val sheetPart: @Composable () -> Unit = {
                val sheetShape = PixelShape(4.dp)
                Column(
                    Modifier
                        .graphicsLayer { alpha = sheetA.value }
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .clip(sheetShape)
                        .background(MoonsSheetBg)
                        .border(2.dp, MoonsSheetLine, sheetShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        selDate.dayOfWeek.getDisplayName(DateTextStyle.FULL, Locale.ENGLISH) + ", " + selDate.dayOfMonth,
                        style = TextStyle(fontFamily = TitleFont, fontSize = 26.sp, color = Px.Lavender),
                    )
                    Text(
                        when (selDate) {
                            today -> "today"
                            today.plusDays(1) -> "tomorrow"
                            today.minusDays(1) -> "yesterday"
                            else -> monthTitle(YearMonth.from(selDate))
                        },
                        style = TextStyle(fontFamily = PageFont, fontSize = 16.sp, color = MoonsDim),
                    )
                    Spacer(Modifier.height(12.dp))
                    SheetLabel("DAILY")
                    Spacer(Modifier.height(4.dp))
                    DailyRecordList(record, canTap = !selDate.isAfter(today)) { item, lit -> onToggle(item, lit, selDate) { wrote++ } }
                }
            }
            if (LocalWide.current) {
                // Wide: calendar left, selected-day sheet right.
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Column(Modifier.weight(1f).widthIn(max = 520.dp).verticalScroll(rememberScrollState())) { gridPart() }
                    Column(Modifier.weight(1f).widthIn(max = 520.dp).verticalScroll(rememberScrollState())) { sheetPart() }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    gridPart()
                    Spacer(Modifier.height(12.dp))
                    sheetPart()
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (picking) {
        MonthPicker(monthIdx, onDismiss = { picking = false }) { monthIdx = it; picking = false }
    }
}

/** The day sheet's body: lit candles for the items done that day, unlit for the rest; a line when there is nothing to show. */
@Composable
private fun DailyRecordList(r: DayRecord?, canTap: Boolean, onTap: (Idea, Boolean) -> Unit) {
    val note = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = Color(0xFF5F5070))
    when {
        r == null -> {}
        r.kind == DayKind.Future -> Text("a day still to come.", Modifier.padding(vertical = 4.dp), style = note)
        r.kind == DayKind.Empty -> Text("no daily record.", Modifier.padding(vertical = 4.dp), style = note)
        else -> {
            Text(
                "${r.done.size + r.gone} of ${r.done.size + r.undone.size + r.gone} lit",
                Modifier.padding(bottom = 4.dp),
                style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = if (r.allDone) Px.Lavender else MoonsText),
            )
            for (i in r.done) CandleRow(i.title, lit = true, tap = if (canTap) ({ onTap(i, false) }) else null)
            for (i in r.undone) CandleRow(i.title, lit = false, tap = if (canTap) ({ onTap(i, true) }) else null)
            if (r.gone > 0) Text("+ ${r.gone} since removed", Modifier.padding(top = 4.dp), style = note.copy(fontSize = 17.sp))
        }
    }
}

@Composable
private fun CandleRow(title: String, lit: Boolean, tap: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (tap == null) Modifier else Modifier.clickable(role = Role.Checkbox, onClick = tap).semantics { stateDescription = if (lit) "lit" else "unlit" })
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CandleGlyph(lit)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            Modifier.weight(1f),
            style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, lineHeight = 22.sp, color = if (lit) MoonsInk else Color(0xFF5F5070)),
        )
    }
}

/** The Daily candle, 2dp pixels (whole device pixels), lit or not. */
@Composable
private fun CandleGlyph(lit: Boolean) {
    val density = LocalDensity.current
    val px = with(density) { 2.dp.toPx().roundToInt().coerceAtLeast(1) }
    val pixels = remember(lit) { candlePixels(lit, 1) }
    Canvas(Modifier.size(with(density) { (CANDLE_W * px).toDp() }, with(density) { (CANDLE_H * px).toDp() })) {
        for ((x, y, rgb) in pixels) drawRect(Color(0xFF000000.toInt() or rgb), Offset((x * px).toFloat(), (y * px).toFloat()), Size(px.toFloat(), px.toFloat()))
    }
}

private fun monthTitle(m: YearMonth): String =
    m.month.getDisplayName(DateTextStyle.FULL, Locale.ENGLISH) + " " + m.year

@Composable
internal fun SheetLabel(text: String) {
    Text(text, style = TextStyle(fontFamily = PageFont, fontSize = 15.sp, letterSpacing = 1.sp, color = MoonsLabel))
}

@Composable
private fun MoonsHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit, onPick: () -> Unit) {
    // the title, then the month picker under it, so the picker stays clear of the big moon at the top right
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 6.dp, top = 20.dp, bottom = 10.dp)) {
        Text(
            "Moons",
            style = TextStyle(
                fontFamily = TitleFont,
                fontSize = 40.sp,
                color = Px.Lavender,
                shadow = Shadow(Color(0xFF441F6E), Offset(3f, 3f), 0f),
            ),
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArrowButton(CHEV_L, "Previous month", onPrev)
            val shape = PixelShape(3.dp)
            Box(
                Modifier
                    .height(40.dp)
                    .clip(shape)
                    .background(MoonsBarBg)
                    .border(2.dp, MoonsMenuLine, shape)
                    .clickable(role = Role.Button, onClick = onPick)
                    .semantics { contentDescription = "Pick month" }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    monthTitle(month),
                    maxLines = 1,
                    softWrap = false,
                    style = TextStyle(fontFamily = PageFont, fontSize = 24.sp, color = Px.Text),
                )
            }
            ArrowButton(CHEV_R, "Next month", onNext)
        }
    }
}

/** A carved square button with a pixel chevron or arrow in it. */
@Composable
private fun ArrowButton(grid: List<String>, description: String, onClick: () -> Unit, pixel: Dp = 3.dp) {
    val shape = PixelShape(3.dp)
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .background(MoonsBarBg)
            .border(2.dp, MoonsMenuLine, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        PixelIcon(grid, MoonsArrow, pixel = pixel)
    }
}

private val SPARK_X = floatArrayOf(0.14f, 0.84f, 0.08f, 0.92f, 0.8f)
private val SPARK_Y = floatArrayOf(0.2f, 0.12f, 0.55f, 0.5f, 0.82f)
private val SPARK_PERIOD = floatArrayOf(1.2f, 0.8f, 1.6f, 1.0f, 1.4f)
private val Spark = Color(0xFFF0E8FA)
private val TodayBg = Color(0xFF24123A)

/** The day-cell moons are this many pixels across, shown at a whole number of device pixels per moon pixel. */
private const val MOON_CELL_PX = 12

/** Today's corner mark: a lit diamond, 3 x 3 pixels. */
private val TODAY_MARK = listOf(".X.", "XXX", ".X.")

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    moon: ImageBitmap,
    allDone: Boolean,
    time: FloatState,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = PixelShape(3.dp)
    val density = LocalDensity.current
    val scale = with(density) { (24.dp.toPx() / MOON_CELL_PX).toInt().coerceAtLeast(1) }
    val moonDp = with(density) { (scale * MOON_CELL_PX).toDp() }
    val pick = remember { Animatable(0f) } // a glow that fades off the day just picked (350 ms)
    LaunchedEffect(isSelected) { if (isSelected) { pick.snapTo(1f); pick.animateTo(0f, tween(350)) } }
    Box(
        modifier
            .height(56.dp)
            .clip(shape)
            .background(if (isToday) TodayBg else if (isSelected) MoonsSelBg else Color.Transparent)
            // today keeps a solid lavender frame even when selected; sparkles mark the selection
            .then(if (isToday) Modifier.border(2.dp, Px.Lavender, shape) else if (isSelected) Modifier.border(2.dp, MoonsSelLine, shape) else Modifier)
            .then(
                if (!isSelected) Modifier else Modifier.drawWithContent {
                    drawContent()
                    if (pick.value > 0f) drawRect(Color(0xFFD6C8FF).copy(alpha = 0.4f * pick.value))
                    val t = time.floatValue
                    for (k in 0 until 5) {
                        val a = 0.15f + 0.85f * max(0f, sin(t * 6.2832f / SPARK_PERIOD[k] + k * 1.7f))
                        val d = (if (k % 2 == 0) 3.dp else 2.dp).toPx()
                        drawRect(Spark.copy(alpha = a), Offset(SPARK_X[k] * size.width, SPARK_Y[k] * size.height), Size(d, d))
                    }
                },
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (isToday) PixelIcon(TODAY_MARK, Px.Lavender, 2.dp, Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 3.dp)) {
            Text(
                date.dayOfMonth.toString(),
                style = TextStyle(
                    fontFamily = PageFont, fontSize = 17.sp, lineHeight = 16.sp,
                    color = if (isToday) Px.Text else if (inMonth) MoonsText else MoonsOutside,
                ),
            )
            Image(
                moon, null,
                Modifier.padding(top = 2.dp).size(moonDp).alpha(if (inMonth) 1f else 0.45f),
                filterQuality = FilterQuality.None,
            )
            // every Daily item done that day: a small lit pip under the moon
            if (allDone) Box(Modifier.padding(top = 3.dp).size(8.dp, 3.dp).background(if (inMonth) Px.Lavender else MoonsDim))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Month picker ("night sky")
// ---------------------------------------------------------------------------------------------

private const val MOON_LIT = 0xE2C8FF
private const val MOON_HI = 0xFFF0FF
private const val MOON_CRATER = 0xAE8AD8

@Composable
private fun MonthPicker(monthIdx: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val time = rememberRotTime()
    var year by remember { mutableStateOf(monthIdx / 12) }
    val moons = remember(year, monthIdx) {
        Array(12) {
            val d = LocalDate.of(year, it + 1, 15)
            (if (year * 12 + it == monthIdx) moonBitmap(d, 12, MOON_LIT, MOON_HI, MOON_CRATER) else dayMoon(d, 12))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF0C0618), Color(0xFF1A0B30))))
                .border(1.dp, Color(0xFF3A2456), shape),
        ) {
            Canvas(Modifier.matchParentSize()) {
                val t = time.floatValue
                val d = 2.dp.toPx()
                for (i in 0 until 24) {
                    val a = 0.2f + 0.7f * max(0f, sin(t * 1.4f + hf(i, 5) * 6.28f))
                    drawRect(Color(0xFFCDB8FF).copy(alpha = a), Offset(hf(i, 11) * size.width, hf(i, 29) * size.height), Size(d, d))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArrowButton(CHEV_L, "Previous year", { year-- }, pixel = 3.dp)
                    Text(
                        year.toString(),
                        Modifier.padding(horizontal = 18.dp),
                        style = TextStyle(
                            fontFamily = TitleFont, fontSize = 32.sp, color = Px.Lavender,
                            shadow = Shadow(Color(0xAAB69AFF), Offset.Zero, 18f),
                        ),
                    )
                    ArrowButton(CHEV_R, "Next year", { year++ }, pixel = 3.dp)
                }
                Spacer(Modifier.height(8.dp))
                for (r in 0 until 4) {
                    Row(Modifier.fillMaxWidth()) {
                        for (c in 0 until 3) {
                            val m = r * 3 + c
                            val cur = year * 12 + m == monthIdx
                            val cell = RoundedCornerShape(10.dp)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(2.dp)
                                    .clip(cell)
                                    .then(if (cur) Modifier.background(Color(0xFF2A1640)) else Modifier)
                                    .clickable(role = Role.Button) { onPick(year * 12 + m) }
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier.size(29.dp).then(
                                        if (!cur) Modifier else Modifier.drawBehind {
                                            val a = 0.2f + 0.14f * sin(time.floatValue * 3f)
                                            drawCircle(Color(0xFFE2C8FF).copy(alpha = a * 0.6f), size.minDimension * 0.95f)
                                            drawCircle(Color(0xFFE2C8FF).copy(alpha = a), size.minDimension * 0.65f)
                                        },
                                    ),
                                ) {
                                    Image(moons[m], null, Modifier.size(29.dp), filterQuality = FilterQuality.None)
                                }
                                Text(
                                    java.time.Month.of(m + 1).getDisplayName(DateTextStyle.FULL, Locale.ENGLISH),
                                    maxLines = 1,
                                    softWrap = false,
                                    style = TextStyle(fontFamily = TitleFont, fontSize = 20.sp, color = if (cur) MoonsToday else MoonsText),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A compact month of days to pick one from, or "no day". Opens on [day]'s month (or this one). */
@Composable
internal fun DayPicker(day: Long?, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val today = LocalDate.now(zone)
    val start = day?.let { LocalDate.ofEpochDay(it) } ?: today
    var monthIdx by remember { mutableStateOf(start.year * 12 + start.monthValue - 1) }
    val month = YearMonth.of(monthIdx / 12, monthIdx % 12 + 1)
    val gridStart = month.atDay(1).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(MoonsMenuBg)
                .border(2.dp, MoonsMenuLine, shape)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArrowButton(CHEV_L, "Previous month", { monthIdx-- })
                Text(monthTitle(month), Modifier.weight(1f), textAlign = TextAlign.Center, style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = MoonsMonth))
                ArrowButton(CHEV_R, "Next month", { monthIdx++ })
            }
            Row(Modifier.fillMaxWidth()) {
                for (d in listOf("M", "T", "W", "T", "F", "S", "S")) {
                    Text(d, Modifier.weight(1f), textAlign = TextAlign.Center, style = TextStyle(fontFamily = PageFont, fontSize = 16.sp, color = MoonsDim))
                }
            }
            for (row in 0 until 6) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val date = gridStart.plusDays((row * 7 + col).toLong())
                        val ed = date.toEpochDay()
                        val cell = RoundedCornerShape(8.dp)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(38.dp)
                                .padding(1.dp)
                                .clip(cell)
                                .background(if (ed == day) MoonsSelBg else Color.Transparent)
                                .then(if (ed == day) Modifier.border(2.dp, MoonsSelLine, cell) else if (date == today) Modifier.border(1.dp, Px.Lavender.copy(alpha = 0.45f), cell) else Modifier)
                                .clickable(role = Role.Button) { onPick(ed) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                style = TextStyle(
                                    fontFamily = PageFont, fontSize = 18.sp,
                                    color = if (date == today) Px.Lavender else if (date.month == month.month) MoonsText else MoonsOutside,
                                ),
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                MenuRowInline("no day", MoonsInk) { onPick(null) }
                MenuRowInline("cancel", MoonsDim, onDismiss)
            }
        }
    }
}

@Composable
internal fun MenuRowInline(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = color),
    )
}

// ---------------------------------------------------------------------------------------------
// Background (deep violet, big phase moon, drifting clouds, twinkling stars)
// ---------------------------------------------------------------------------------------------

private val CLOUD_Y = intArrayOf(16, 34, 50)
private val CLOUD_V = floatArrayOf(1.6f, 1.0f, 2.0f) // low-res px per second
private val CLOUD_OFF = floatArrayOf(0.1f, 0.5f, 0.8f)

@Composable
private fun MoonsBackdrop(time: FloatState, pxSize: Float, today: LocalDate) {
    val pix = remember { Pix(1, 1) }
    val px = pxSize.toInt().coerceAtLeast(1)
    val bigMoon = remember(today) { dayMoon(today, 34) }
    val clouds = remember { arrayOf(cloudBitmap(44, 14, 1), cloudBitmap(60, 18, 2), cloudBitmap(50, 15, 3)) }
    Canvas(Modifier.fillMaxSize()) {
        val t = time.floatValue
        val w = ceil(size.width / px).toInt()
        val h = ceil(size.height / px).toInt()
        if (w <= 0 || h <= 0) return@Canvas
        drawMoonsBackdrop(pix, t, w, h, bigMoon, clouds)
        drawIntoCanvas { pix.blit(it, 0, 0, px) }
    }
}

// Sprites are drawn 1:1 at whole pixels, no filtering, no dither, like the old Paint-less drawBitmap(.., null).
private val moonPaint by lazy { hardPaint().apply { filterQuality = FilterQuality.None } }

/** Draws the whole backdrop into [pix] (resized to [w] x [h]). */
internal fun drawMoonsBackdrop(pix: Pix, t: Float, w: Int, h: Int, bigMoon: ImageBitmap, clouds: Array<ImageBitmap>) {
    pix.resize(w, h)
    pix.fill(MoonsBase)
    val mx = w - 24
    val my = 22
    pix.glow(mx.toFloat(), my.toFloat(), 64f, 0x7540B8, 0.4f, add = false)
    for (i in 0 until 90) {
        val a = 0.25f + 0.5f * max(0f, sin(t * 1.2f + hf(i, 5) * 6.28f))
        pix.dot((hf(i, 11) * w).toInt(), (hf(i, 29) * h).toInt(), 1, 1, if (i % 5 == 4) 0x935AD4 else 0xCDB8FF, a)
    }
    pix.canvas.drawImage(bigMoon, Offset((mx - bigMoon.width / 2).toFloat(), (my - bigMoon.height / 2).toFloat()), moonPaint)
    for (i in 0 until 3) {
        val c = clouds[i]
        val span = w + c.width
        val x = (t * CLOUD_V[i] + CLOUD_OFF[i] * span) % span - c.width
        pix.canvas.drawImage(c, Offset(rotRnd(x).toFloat(), CLOUD_Y[i].toFloat()), moonPaint)
    }
}
