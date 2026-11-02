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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.text.style.TextOverflow
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
private val MoonsSelBg = Color(0xFF1C1128)
private val MoonsSelLine = Color(0xFF7540B8)
private val MoonsSheetBg = Color(0xFF120A19)
private val MoonsSheetLine = Color(0xFF2A1A38)
private val MoonsChipBg = Color(0xFF0C0712)
private val MoonsBarBg = Color(0xFF0E0814)
private val MoonsBarLine = Color(0xFF241532)
private val MoonsMenuBg = Color(0xFF160D1F)
private val MoonsMenuLine = Color(0xFF3A2450)
private val MoonsInk = Color(0xFFEDE4F7)
private val MoonsDelete = Color(0xFFE07AB0)
private val PlanDot = Color(0xFFF0E8FA)

/** 14x14 nav icon for the Moons tab. */
internal val MoonsNavGrid = listOf(
    "..X......X....", ".XXXXXXXXXXX..", "XHHHHHHHHHHHX.", "XHHHHHHHHHHHX.", "XCCCCCCCCCCCX.",
    "XCVCCVCCVCCCX.", "XCCCCCCCCCCCX.", "XCVCCMMCVCCCX.", "XCCCCMMCCCCCX.", "XCVCCVCCVCCCX.",
    "XCCCCCCCCCCCX.", ".XXXXXXXXXXX..", "..............", "..............",
)
internal val MoonsNavActive = mapOf(
    'X' to Color(0xFF100817), 'H' to Color(0xFFB69AFF), 'C' to Color(0xFF47296D),
    'M' to Color(0xFFD45AAB), 'P' to Color(0xFF7540B8), 'V' to Color(0xFFF0E8FA),
)
internal val MoonsNavInactive = mapOf(
    'X' to Color(0xFF100817), 'H' to Color(0xFF563C78), 'C' to Color(0xFF241432),
    'M' to Color(0xFF7A3668), 'P' to Color(0xFF39234C), 'V' to Color(0xFF8A7A9A),
)

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

private fun moonBitmap(day: LocalDate): Bitmap {
    val frac = moonFrac(day)
    val t = cos(frac * 2 * PI)
    val px = IntArray(100)
    for (y in 0 until 10) for (x in 0 until 10) {
        val dx = (x + 0.5 - 5) / 4.5
        val dy = (y + 0.5 - 5) / 4.5
        if (dx * dx + dy * dy > 1.05) continue
        val w = sqrt(max(0.0, 1 - dy * dy))
        val lit = if (frac < 0.5) dx > t * w else dx < -t * w
        px[y * 10 + x] = when {
            !lit -> 0xFF241432.toInt()
            dx < -0.3 || dy < -0.3 -> 0xFFE6DCFF.toInt()
            else -> 0xFFB69AFF.toInt()
        }
    }
    return Bitmap.createBitmap(px, 10, 10, Bitmap.Config.ARGB_8888)
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
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
    val focusByDay = remember(focus) { focus.orEmpty().groupBy({ it.day }, { it.lobeId }) }
    val lobeList = lobes.orEmpty()

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
        MoonsBackdrop(time, pxSize)
        BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = lift)) {
            Column(Modifier.fillMaxSize()) {
                MoonsHeader(month, onPrev = { goMonth.value(-1) }, onNext = { goMonth.value(1) })
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
                    val moons = remember(gridStart) { Array(42) { moonBitmap(gridStart.plusDays(it.toLong())).asImageBitmap() } }
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
                                    val dots = ArrayList<Color>(8)
                                    focusByDay[ed]?.let { ids -> lobeList.filter { it.id in ids }.forEach { dots.add(lobeColor(it)) } }
                                    if (plansByDay[ed]?.any { !it.done } == true) dots.add(PlanDot)
                                    DayCell(
                                        date = date,
                                        inMonth = date.month == month.month && date.year == month.year,
                                        isToday = date == today,
                                        isSelected = ed == selected,
                                        moon = moons[i],
                                        dots = dots,
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
                        SheetLabel("FOCUS FROM BRAIN")
                        Spacer(Modifier.height(6.dp))
                        if (lobeList.isEmpty()) {
                            Text(
                                "no lobes yet. grow one in Brain.",
                                style = TextStyle(fontFamily = PageFont, fontSize = 18.sp, color = Color(0xFF5F5070)),
                            )
                        } else {
                            val focused = focusByDay[selected].orEmpty()
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (l in lobeList) {
                                    val on = l.id in focused
                                    val c = lobeColor(l)
                                    val shape = RoundedCornerShape(10.dp)
                                    Text(
                                        l.name.uppercase(Locale.ENGLISH),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .alpha(if (on) 1f else 0.38f)
                                            .clip(shape)
                                            .background(MoonsChipBg)
                                            .border(2.dp, c, shape)
                                            .clickable(role = Role.Checkbox) { onToggleFocus(selected, l.id, !on) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = c),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
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
private fun MoonsHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
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
        ArrowButton(CHEV_L, "Previous month", onPrev)
        Text(
            monthTitle(month),
            maxLines = 1,
            softWrap = false,
            style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = MoonsMonth),
        )
        ArrowButton(CHEV_R, "Next month", onNext)
    }
}

@Composable
private fun ArrowButton(grid: List<String>, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        PixelIcon(grid, Px.Lavender, pixel = 3.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    moon: ImageBitmap,
    dots: List<Color>,
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
            .then(if (isSelected) Modifier.border(2.dp, MoonsSelLine, shape) else Modifier)
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
            val d = 4.dp.toPx()
            val step = 6.dp.toPx()
            val n = dots.size.coerceAtMost(7)
            val total = n * step - 2.dp.toPx()
            val x0 = (size.width - total) / 2f
            for (k in 0 until n) drawRect(dots[k], Offset(x0 + k * step, 0f), Size(d, d))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Background (deep violet, one glow, twinkling stars)
// ---------------------------------------------------------------------------------------------

@Composable
private fun MoonsBackdrop(time: FloatState, pxSize: Float) {
    val pix = remember { Pix(1, 1) }
    val px = pxSize.toInt().coerceAtLeast(1)
    Canvas(Modifier.fillMaxSize()) {
        val t = time.floatValue
        val w = ceil(size.width / px).toInt()
        val h = ceil(size.height / px).toInt()
        if (w <= 0 || h <= 0) return@Canvas
        pix.resize(w, h)
        pix.fill(MoonsBase)
        val s = density / pxSize // low-res px per dp
        pix.glow(300f * s, 80f * s, 280f * s, 0x462882, 0.35f, add = false)
        for (i in 0 until 90) {
            val a = 0.25f + 0.5f * max(0f, sin(t * 1.2f + hf(i, 5) * 6.28f))
            pix.dot((hf(i, 11) * w).toInt(), (hf(i, 29) * h).toInt(), 1, 1, if (i % 5 == 4) 0xD45AAB else 0xCDB8FF, a)
        }
        drawIntoCanvas { pix.blit(it.nativeCanvas, 0, 0, px) }
    }
}
