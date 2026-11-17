package app.vesper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.AlarmLogic
import app.vesper.AlarmProblem
import app.vesper.data.Alarm
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/** Everything the Moons alarms section needs; null on platforms that cannot ring alarms, which hides the section. */
class AlarmUi(
    val alarms: List<Alarm>,
    val history: List<Alarm>,
    val problem: AlarmProblem?,
    val onFix: () -> Unit,
    val onSave: (Alarm) -> Unit,
    val onDelete: (Long) -> Unit,
    val onEnabled: (Alarm, Boolean) -> Unit,
    val onClearHistory: () -> Unit,
)

private const val HISTORY_SHOWN = 20
private val alarmZone: ZoneId get() = ZoneId.systemDefault()
private val DIM_TEXT = Color(0xFF5F5070)
private val ARROW_UP = listOf("...XX...", "..XXXX..", ".XX..XX.", "XX....XX")
private val ARROW_DOWN = listOf("XX....XX", ".XX..XX.", "..XXXX..", "...XX...")

private fun dayText(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "today"
    today.plusDays(1) -> "tomorrow"
    else -> day.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.ENGLISH) + " " + day.dayOfMonth + " " +
        day.month.getDisplayName(DateTextStyle.SHORT, Locale.ENGLISH)
}

/** The alarms card under the Moons calendar: once, repeating, history and the add button. */
@Composable
internal fun AlarmsCard(ui: AlarmUi) {
    val today = LocalDate.now(alarmZone)
    // id 0 = a new alarm; null = closed.
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val once = ui.alarms.filter { it.repeatDays and AlarmLogic.WEEK == 0 }
        .sortedWith(compareBy({ it.oneTimeDate ?: Long.MAX_VALUE }, { it.hour }, { it.minute }))
    val repeating = ui.alarms.filter { it.repeatDays and AlarmLogic.WEEK != 0 }

    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(MoonsSheetBg)
            .border(2.dp, MoonsSheetLine, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ALARMS", Modifier.weight(1f), style = TextStyle(fontFamily = TitleFont, fontSize = 26.sp, color = Px.Lavender))
            PixelButton("+ ALARM", { editing = Alarm(hour = 7, minute = 0) })
        }
        ui.problem?.let { p ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (p == AlarmProblem.Exact) "alarms need the exact alarm permission." else "alarms need notifications turned on.",
                    Modifier.weight(1f),
                    style = TextStyle(fontFamily = PageFont, fontSize = 18.sp, lineHeight = 19.sp, color = Px.Lavender),
                )
                MenuRowInline("allow", MoonsInk, ui.onFix)
            }
        }
        if (once.isEmpty() && repeating.isEmpty()) {
            Text(
                "no alarms.",
                Modifier.padding(vertical = 8.dp),
                style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = DIM_TEXT),
            )
        }
        if (once.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SheetLabel("ONCE")
            for (a in once) {
                val date = LocalDate.ofEpochDay(a.oneTimeDate ?: AlarmLogic.defaultDate(a.hour, a.minute, System.currentTimeMillis(), alarmZone))
                AlarmRow(a, dayText(date, today), null) { editing = a }
            }
        }
        if (repeating.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SheetLabel("REPEATING")
            for (a in repeating) {
                AlarmRow(a, AlarmLogic.daysLabel(a.repeatDays), { ui.onEnabled(a, it) }) { editing = a }
            }
        }
        if (ui.history.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SheetLabel("HISTORY") }
                MenuRowInline("clear", MoonsDim) { clearing = true }
            }
            for (a in ui.history.take(HISTORY_SHOWN)) {
                val rang = a.firedAt?.let { LocalDate.ofInstant(Instant.ofEpochMilli(it), alarmZone) }
                Row(Modifier.fillMaxWidth().alpha(0.5f).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(AlarmLogic.timeLabel(a.hour, a.minute), style = TextStyle(fontFamily = PageFont, fontSize = 26.sp, color = MoonsInk))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        listOf(a.label, rang?.let { dayText(it, today) }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" - "),
                        style = TextStyle(fontFamily = PageFont, fontSize = 18.sp, color = MoonsDim),
                    )
                }
            }
        }
    }

    editing?.let { a ->
        AlarmEditor(a, today, onDismiss = { editing = null }, onSave = { editing = null; ui.onSave(it) }, onDelete = { editing = null; ui.onDelete(it) })
    }
    if (clearing) {
        PixelDialog({ clearing = false }) {
            Text("CLEAR HISTORY?", style = TextStyle(fontFamily = PageFont, fontSize = 24.sp, color = Px.Lavender))
            Spacer(Modifier.height(8.dp))
            Text("Gone for good. No undo.", style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = Px.TextDim))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { clearing = false })
                PixelButton("CLEAR", { clearing = false; ui.onClearHistory() }, primary = true)
            }
        }
    }
}

@Composable
private fun AlarmRow(a: Alarm, sub: String, onSwitch: ((Boolean) -> Unit)?, onClick: () -> Unit) {
    val off = onSwitch != null && !a.enabled
    Row(
        Modifier.fillMaxWidth().alpha(if (off) 0.5f else 1f).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(AlarmLogic.timeLabel(a.hour, a.minute), style = TextStyle(fontFamily = PageFont, fontSize = 40.sp, color = MoonsInk))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (a.label.isNotBlank()) Text(a.label, style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, lineHeight = 21.sp, color = MoonsInk))
            Text(sub, style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, lineHeight = 18.sp, color = MoonsDim))
        }
        if (onSwitch != null) PixelSwitch(a.enabled, onSwitch)
    }
}

@Composable
private fun PixelSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val track = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(52.dp, 28.dp)
            .clip(track)
            .background(if (on) Px.Primary else MoonsBarBg)
            .border(2.dp, if (on) Px.Lavender else MoonsMenuLine, track)
            .semantics { role = Role.Switch; contentDescription = if (on) "on" else "off" }
            .clickable(role = Role.Switch) { onChange(!on) }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).background(if (on) Px.Text else MoonsDim))
    }
}

/** One time unit with a big number and tap (or hold) arrows above and below. */
@Composable
private fun TimeColumn(value: Int, name: String, onStep: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepButton(ARROW_UP, "$name up") { onStep(1) }
        Text(
            value.toString().padStart(2, '0'),
            Modifier.padding(vertical = 2.dp),
            style = TextStyle(fontFamily = PageFont, fontSize = 72.sp, lineHeight = 72.sp, color = MoonsInk),
        )
        StepButton(ARROW_DOWN, "$name down") { onStep(-1) }
    }
}

@Composable
private fun StepButton(grid: List<String>, desc: String, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(84.dp, 44.dp)
            .clip(shape)
            .background(MoonsBarBg)
            .border(2.dp, MoonsMenuLine, shape)
            .semantics { contentDescription = desc; role = Role.Button; onClick { step(); true } }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    step()
                    coroutineScope {
                        val repeat = launch { delay(400); while (true) { step(); delay(70) } }
                        tryAwaitRelease()
                        repeat.cancel()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) { PixelIcon(grid, MoonsArrow, 3.dp) }
}

@Composable
private fun AlarmEditor(a: Alarm, today: LocalDate, onDismiss: () -> Unit, onSave: (Alarm) -> Unit, onDelete: (Long) -> Unit) {
    var hour by remember(a.id) { mutableStateOf(a.hour) }
    var minute by remember(a.id) { mutableStateOf(a.minute) }
    var label by remember(a.id) { mutableStateOf(a.label) }
    var mask by remember(a.id) { mutableStateOf(a.repeatDays and AlarmLogic.WEEK) }
    var picked by remember(a.id) { mutableStateOf(a.oneTimeDate) } // null = the next time hour:minute comes
    var pickingDay by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val once = mask == 0
    val date = picked ?: AlarmLogic.defaultDate(hour, minute, System.currentTimeMillis(), alarmZone)

    PixelDialog(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(if (a.id == 0L) "NEW ALARM" else "EDIT ALARM", style = TextStyle(fontFamily = PageFont, fontSize = 24.sp, color = Px.Lavender))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TimeColumn(hour, "hour") { hour = (hour + it + 24) % 24 }
                Text(":", Modifier.padding(horizontal = 8.dp), style = TextStyle(fontFamily = PageFont, fontSize = 72.sp, color = MoonsDim))
                TimeColumn(minute, "minute") { minute = (minute + it + 60) % 60 }
            }
            Spacer(Modifier.height(10.dp))
            val fieldShape = RoundedCornerShape(10.dp)
            BasicTextField(
                value = label,
                onValueChange = { label = it.take(40) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = MoonsInk),
                cursorBrush = SolidColor(Px.Lavender),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box {
                        if (label.isEmpty()) Text("label", style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = DIM_TEXT))
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(fieldShape)
                    .background(Color(0xFF08040C))
                    .border(2.dp, Color(0xFF5A3E7A), fieldShape)
                    .padding(12.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((i, d) in listOf("M", "T", "W", "T", "F", "S", "S").withIndex()) {
                    val on = mask and (1 shl i) != 0
                    val chip = RoundedCornerShape(6.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(chip)
                            .background(if (on) Px.Primary else MoonsBarBg)
                            .border(2.dp, if (on) Px.Lavender else MoonsMenuLine, chip)
                            .semantics { contentDescription = AlarmLogic.daysLabel(1 shl i) + if (on) " on" else " off" }
                            .clickable(role = Role.Checkbox) { mask = mask xor (1 shl i) },
                        contentAlignment = Alignment.Center,
                    ) { Text(d, style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = if (on) Px.Text else MoonsDim)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (once) "rings " + dayText(LocalDate.ofEpochDay(date), today) else AlarmLogic.daysLabel(mask),
                    Modifier.weight(1f),
                    style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = MoonsText),
                )
                if (once) MenuRowInline("change day", MoonsInk) { pickingDay = true }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (a.id != 0L) {
                    Box(Modifier.weight(1f)) { MenuRowInline("delete", MoonsDim) { deleting = true } }
                }
                PixelButton("CANCEL", onDismiss)
                PixelButton(
                    "SAVE",
                    {
                        val now = System.currentTimeMillis()
                        // A one-time alarm picked for a moment already gone lands on the next time it comes instead.
                        val day = if (picked != null && LocalDate.ofEpochDay(date).atTime(hour, minute).atZone(alarmZone).toInstant().toEpochMilli() <= now) AlarmLogic.defaultDate(hour, minute, now, alarmZone) else date
                        onSave(a.copy(hour = hour, minute = minute, label = label.trim(), repeatDays = mask, enabled = true, oneTimeDate = if (once) day else null))
                    },
                    primary = true,
                )
            }
        }
    }

    if (pickingDay) DayPicker(date, onDismiss = { pickingDay = false }) { picked = it; pickingDay = false }
    if (deleting) {
        PixelDialog({ deleting = false }) {
            Text("DELETE ALARM?", style = TextStyle(fontFamily = PageFont, fontSize = 24.sp, color = Px.Lavender))
            Spacer(Modifier.height(8.dp))
            Text("Gone for good. No undo.", style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = Px.TextDim))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { deleting = false })
                PixelButton("DELETE", { deleting = false; onDelete(a.id) }, primary = true)
            }
        }
    }
}
