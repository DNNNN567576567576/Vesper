package app.vesper.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import app.vesper.data.Lobe
import app.vesper.data.Plan
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

// The Moons tab: a month calendar where every day wears its real moon phase, with Brain focus and plans per day.

private const val MoonsBase = 0x06030C
private val MoonsText = Color(0xFFC9BCD9)
private val MoonsOutside = Color(0xFF3A2C48)
private val MoonsToday = Color(0xFFFFC8EF)
private val MoonsDim = Color(0xFF6F6080)
private val MoonsLabel = Color(0xFF7A6A8C)
private val MoonsMonth = Color(0xFFA99AB9)
private val MoonsArrow = Color(0xFFB69AFF)
private val MoonsSelBg = Color(0xFF1C1128)
private val MoonsSelLine = Color(0xFF7540B8)
private val MoonsSheetBg = Color(0xFF120A19)
private val MoonsSheetLine = Color(0xFF2A1A38)
private val MoonsBarBg = Color(0xFF0E0814)
private val MoonsBarLine = Color(0xFF241532)
private val MoonsMenuBg = Color(0xFF160D1F)
private val MoonsMenuLine = Color(0xFF3A2450)
private val MoonsInk = Color(0xFFEDE4F7)
private val MoonsDelete = Color(0xFFE07AB0)
private val PlanDot = Color(0xFFF0E8FA)

/** 12x11 nav icon for the Moons tab: a crescent with a tiny twinkle. */
internal val MoonsNavGrid = listOf(
    "....ooo.....", "..oooo......", ".ooo........", ".oo.....w...", "ooo....wXw..", "ooo.....w...",
    "ooo.........", ".oo.........", ".ooo........", "..oooo......", "....ooo.....",
)
internal val MoonsNavActive = mapOf('o' to Color(0xFFB69AFF), 'X' to Color(0xFFD45AAB), 'w' to Color(0xFFF0E8FA))
internal val MoonsNavInactive = mapOf('o' to Color(0xFF563C78), 'X' to Color(0xFF7A3668), 'w' to Color(0xFF8A7A9A))

private val CHEV_L = listOf("...X", "..X.", ".X..", "X...", ".X..", "..X.", "...X")
private val CHEV_R = listOf("X...", ".X..", "..X.", "...X", "..X.", ".X..", "X...")
private val PLUS = listOf("...XX...", "...XX...", "...XX...", "XXXXXXXX", "XXXXXXXX", "...XX...", "...XX...", "...XX...")
private val RING = listOf(
    "..XXXXX..", ".X.....X.", "X.......X", "X.......X", "X.......X",
    "X.......X", "X.......X", ".X.....X.", "..XXXXX..",
)
private val RING_DONE: List<String> = run {
    val g = RING.map { it.toCharArray() }
    for ((x, y) in listOf(2 to 4, 3 to 5, 4 to 6, 5 to 5, 6 to 4, 6 to 3)) g[y][x] = 'C'
    g.map { String(it) }
}
private val RingOpen = mapOf('X' to Color(0xFFB69AFF))
private val RingShut = mapOf('X' to Color(0xFF5F5070), 'C' to Color(0xFFD45AAB))

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
private val CRATERS = floatArrayOf(0.28f, 0.28f, 0.1f, 0.5f, -0.1f, 0.09f, -0.1f, 0.58f, 0.1f)

private fun moonBitmap(day: LocalDate, n: Int, lit: Int, hi: Int, crater: Int, dark: Int = 0x1A0F26): Bitmap {
    val frac = moonFrac(day)
    val t = cos(frac * 2 * PI)
    val px = IntArray(n * n)
    val rad = n / 2.0 - 0.5
    for (y in 0 until n) for (x in 0 until n) {
        val dx = (x + 0.5 - n / 2.0) / rad
        val dy = (y + 0.5 - n / 2.0) / rad
        if (dx * dx + dy * dy > 1.05) continue
        val w = sqrt(max(0.0, 1 - dy * dy))
        val isLit = if (frac < 0.5) dx > t * w else dx < -t * w
        var c = when {
            !isLit -> dark
            dx < -0.3 || dy < -0.3 -> hi
            else -> lit
        }
        if (isLit) for (k in 0 until 3) {
            if (hypot(dx - CRATERS[k * 3], dy - CRATERS[k * 3 + 1]) <= CRATERS[k * 3 + 2]) c = crater
        }
        px[y * n + x] = 0xFF000000.toInt() or c
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
}

private fun dayMoon(day: LocalDate, n: Int) = moonBitmap(day, n, 0xC9B0FF, 0xE6DCFF, 0x8F6BD0)

private fun cloudBitmap(wc: Int, hc: Int, seed: Int): Bitmap {
    val px = IntArray(wc * hc)
    for (y in 0 until hc) for (x in 0 until wc) for (i in 0 until 5) {
        val ex = (x + 0.5 - wc * (i + 0.5) / 5) / (wc / 5 + 2)
        val ey = (y + 0.5 - hc) / (hc * (0.4 + 0.55 * hf(seed, i)))
        if (ex * ex + ey * ey <= 1.0) {
            px[y * wc + x] = if (y > hc * 0.7) 0xE6140A1E.toInt() else 0xE61A0F26.toInt()
            break
        }
    }
    return Bitmap.createBitmap(px, wc, hc, Bitmap.Config.ARGB_8888)
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoonsScreen(
    plans: List<Plan>?,
    focus: List<DayFocus>?,
    lobes: List<Lobe>?,
    resetSignal: Int,
    draft: String,
    onDraft: (String) -> Unit,
    onAddPlan: (Long) -> Unit,
    onSetDone: (Plan, Boolean) -> Unit,
    onEditPlan: (Plan, String) -> Unit,
    onDeletePlan: (Long) -> Unit,
    onToggleFocus: (Long, Long, Boolean) -> Unit,
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

    val plansByDay = remember(plans) { plans.orEmpty().groupBy { it.day } }

    var picking by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Plan?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Plan?>(null) }
    var deleting by remember { mutableStateOf<Plan?>(null) }

    // The bottom nav bar stays under the keyboard, so only lift the screen by what the keyboard covers above it.
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navTotal = with(density) { 74.dp.roundToPx() } + WindowInsets.navigationBars.getBottom(density)
    val lift = with(density) { (imeBottom - navTotal).coerceAtLeast(0).toDp() }

    val goMonth = rememberUpdatedState { delta: Int -> monthIdx += delta }

    Box(Modifier.fillMaxSize()) {
        MoonsBackdrop(time, pxSize, today)
        BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = lift)) {
            Column(Modifier.fillMaxSize()) {
                MoonsHeader(month, onPrev = { goMonth.value(-1) }, onNext = { goMonth.value(1) }, onPick = { picking = true })
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        for (d in listOf("M", "T", "W", "T", "F", "S", "S")) {
                            Text(
                                d, Modifier.weight(1f), textAlign = TextAlign.Center,
                                style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, color = MoonsDim),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val gridStart = remember(monthIdx) {
                        val first = month.atDay(1)
                        first.minusDays((first.dayOfWeek.value - 1).toLong())
                    }
                    val moons = remember(gridStart) { Array(42) { dayMoon(gridStart.plusDays(it.toLong()), 11).asImageBitmap() } }
                    val swipePx = with(density) { 60.dp.toPx() }
                    Column(
                        Modifier
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
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
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
                                        unfinished = plansByDay[ed]?.any { !it.done } == true,
                                        time = time,
                                        modifier = Modifier.weight(1f),
                                        onDoubleClick = { selected = ed; adding = true },
                                    ) {
                                        selected = ed
                                        monthIdx = date.year * 12 + date.monthValue - 1
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    val sheetShape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier
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
                        SheetLabel("PLANS")
                        Spacer(Modifier.height(4.dp))
                        val dayPlans = plansByDay[selected].orEmpty()
                        if (dayPlans.isEmpty()) {
                            Text(
                                "nothing planned.",
                                modifier = Modifier.padding(vertical = 4.dp),
                                style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = Color(0xFF5F5070)),
                            )
                        }
                        for (p in dayPlans) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onSetDone(p, !p.done) },
                                        onLongClick = { menuFor = p },
                                    )
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (p.done) PixelArt(RING_DONE, RingShut, 2.dp) else PixelArt(RING, RingOpen, 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    p.text,
                                    modifier = Modifier.weight(1f),
                                    style = TextStyle(
                                        fontFamily = PageFont, fontSize = 20.sp, lineHeight = 22.sp,
                                        color = if (p.done) Color(0xFF5F5070) else MoonsInk,
                                        textDecoration = if (p.done) TextDecoration.LineThrough else null,
                                    ),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (picking) {
        MonthPicker(monthIdx, onDismiss = { picking = false }) { monthIdx = it; picking = false }
    }

    menuFor?.let { p ->
        Dialog(onDismissRequest = { menuFor = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .width(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MoonsMenuBg)
                    .border(2.dp, MoonsMenuLine, RoundedCornerShape(10.dp))
                    .padding(vertical = 6.dp),
            ) {
                MenuRow("edit", MoonsInk) { menuFor = null; editing = p }
                MenuRow("delete", MoonsDelete) { menuFor = null; deleting = p }
            }
        }
    }

    if (adding) {
        PixelDialog({ adding = false }) {
            Text("ADD PLAN", style = TextStyle(fontFamily = PageFont, fontSize = 24.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            val shape = RoundedCornerShape(10.dp)
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                textStyle = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = MoonsInk),
                cursorBrush = SolidColor(Px.Lavender),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 220.dp)
                    .clip(shape)
                    .background(Color(0xFF08040C))
                    .border(2.dp, Color(0xFF5A3E7A), shape)
                    .padding(12.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("CANCEL", { adding = false })
                PixelButton("ADD", { if (draft.isNotBlank()) onAddPlan(selected); adding = false }, primary = true)
            }
        }
    }

    editing?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.text) }
        PixelDialog({ editing = null }) {
            Text("EDIT PLAN", style = TextStyle(fontFamily = PageFont, fontSize = 24.sp, color = Px.Lavender))
            Spacer(Modifier.height(10.dp))
            val shape = RoundedCornerShape(10.dp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = MoonsInk),
                cursorBrush = SolidColor(Px.Lavender),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 220.dp)
                    .clip(shape)
                    .background(Color(0xFF08040C))
                    .border(2.dp, Color(0xFF5A3E7A), shape)
                    .padding(12.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("CANCEL", { editing = null })
                PixelButton("SAVE", { editing = null; onEditPlan(p, text) }, primary = true)
            }
        }
    }

    deleting?.let { p ->
        PixelDialog(onDismiss = { deleting = null }) {
            Text("DELETE PLAN?", style = TextStyle(fontFamily = PageFont, fontSize = 24.sp, color = Px.Lavender))
            Spacer(Modifier.height(8.dp))
            Text("Gone for good. No undo.", style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = Px.TextDim))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { deleting = null })
                PixelButton("DELETE", { deleting = null; onDeletePlan(p.id) }, primary = true)
            }
        }
    }
}

private fun monthTitle(m: YearMonth): String =
    m.month.getDisplayName(DateTextStyle.FULL, Locale.ENGLISH) + " " + m.year

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = TextStyle(fontFamily = PageFont, fontSize = 15.sp, letterSpacing = 1.sp, color = MoonsLabel))
}

@Composable
private fun MenuRow(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        style = TextStyle(fontFamily = PageFont, fontSize = 21.sp, color = color),
    )
}

@Composable
private fun MoonsHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 6.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Moons",
            style = TextStyle(
                fontFamily = TitleFont,
                fontSize = 40.sp,
                color = Px.Lavender,
                shadow = Shadow(Color(0xFF6E1F5A), Offset(3f, 3f), 0f),
            ),
        )
        Spacer(Modifier.weight(1f))
        ArrowButton(CHEV_L, "Previous month", onPrev, Alignment.CenterEnd)
        Text(
            monthTitle(month),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onPick)
                .semantics { contentDescription = "Pick month" }
                .padding(vertical = 12.dp),
            style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = MoonsMonth),
        )
        ArrowButton(CHEV_R, "Next month", onNext, Alignment.CenterStart)
    }
}

@Composable
private fun ArrowButton(
    grid: List<String>, description: String, onClick: () -> Unit,
    align: Alignment = Alignment.Center, pixel: Dp = 2.dp,
) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = align,
    ) {
        PixelIcon(grid, MoonsArrow, pixel = pixel)
    }
}

private val SPARK_X = floatArrayOf(0.14f, 0.84f, 0.08f, 0.92f, 0.8f)
private val SPARK_Y = floatArrayOf(0.2f, 0.12f, 0.55f, 0.5f, 0.82f)
private val SPARK_PERIOD = floatArrayOf(1.2f, 0.8f, 1.6f, 1.0f, 1.4f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    moon: ImageBitmap,
    unfinished: Boolean,
    time: FloatState,
    modifier: Modifier,
    onDoubleClick: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (isSelected) MoonsSelBg else Color.Transparent)
            // today keeps a lavender frame even when selected; sparkles still mark the selection
            .then(if (isToday) Modifier.border(2.dp, Px.Lavender.copy(alpha = 0.45f), shape) else if (isSelected) Modifier.border(2.dp, MoonsSelLine, shape) else Modifier)
            .then(
                if (!isSelected) Modifier else Modifier.drawWithContent {
                    drawContent()
                    val t = time.floatValue
                    for (k in 0 until 5) {
                        val a = 0.15f + 0.85f * max(0f, sin(t * 6.2832f / SPARK_PERIOD[k] + k * 1.7f))
                        val d = (if (k % 2 == 0) 3.dp else 2.dp).toPx()
                        drawRect(PlanDot.copy(alpha = a), Offset(SPARK_X[k] * size.width, SPARK_Y[k] * size.height), Size(d, d))
                    }
                },
            )
            .tapOrDoubleTap(role = Role.Button, onDoubleTap = onDoubleClick, onTap = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = TextStyle(
                fontFamily = PageFont, fontSize = 17.sp, lineHeight = 16.sp,
                color = if (isToday) MoonsToday else if (inMonth) MoonsText else MoonsOutside,
            ),
        )
        Image(
            moon, null,
            Modifier.padding(top = 1.dp).size(20.dp).alpha(if (inMonth) 1f else 0.45f),
            filterQuality = FilterQuality.None,
        )
        Canvas(Modifier.padding(top = 2.dp).size(width = 44.dp, height = 4.dp)) {
            if (unfinished) {
                val d = 4.dp.toPx()
                drawRect(PlanDot, Offset((size.width - d) / 2f, 0f), Size(d, d))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Month picker ("night sky")
// ---------------------------------------------------------------------------------------------

private const val PINK_LIT = 0xFFC8EF
private const val PINK_HI = 0xFFF0FF
private const val PINK_CRATER = 0xD88AC0

@Composable
private fun MonthPicker(monthIdx: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val time = rememberRotTime()
    var year by remember { mutableStateOf(monthIdx / 12) }
    val moons = remember(year, monthIdx) {
        Array(12) {
            val d = LocalDate.of(year, it + 1, 15)
            (if (year * 12 + it == monthIdx) moonBitmap(d, 12, PINK_LIT, PINK_HI, PINK_CRATER) else dayMoon(d, 12)).asImageBitmap()
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
                                            drawCircle(Color(0xFFFFC8EF).copy(alpha = a * 0.6f), size.minDimension * 0.95f)
                                            drawCircle(Color(0xFFFFC8EF).copy(alpha = a), size.minDimension * 0.65f)
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
    val bigMoon = remember(today) { dayMoon(today, 56) }
    val clouds = remember { arrayOf(cloudBitmap(44, 14, 1), cloudBitmap(60, 18, 2), cloudBitmap(50, 15, 3)) }
    Canvas(Modifier.fillMaxSize()) {
        val t = time.floatValue
        val w = ceil(size.width / px).toInt()
        val h = ceil(size.height / px).toInt()
        if (w <= 0 || h <= 0) return@Canvas
        pix.resize(w, h)
        pix.fill(MoonsBase)
        val mx = w - 34
        val my = 40
        pix.glow(mx.toFloat(), my.toFloat(), 64f, 0x7540B8, 0.4f, add = false)
        for (i in 0 until 90) {
            val a = 0.25f + 0.5f * max(0f, sin(t * 1.2f + hf(i, 5) * 6.28f))
            pix.dot((hf(i, 11) * w).toInt(), (hf(i, 29) * h).toInt(), 1, 1, if (i % 5 == 4) 0xD45AAB else 0xCDB8FF, a)
        }
        pix.canvas.drawBitmap(bigMoon, (mx - 28).toFloat(), (my - 28).toFloat(), null)
        for (i in 0 until 3) {
            val c = clouds[i]
            val span = w + c.width
            val x = (t * CLOUD_V[i] + CLOUD_OFF[i] * span) % span - c.width
            pix.canvas.drawBitmap(c, rotRnd(x).toFloat(), CLOUD_Y[i].toFloat(), null)
        }
        drawIntoCanvas { pix.blit(it.nativeCanvas, 0, 0, px) }
    }
}
