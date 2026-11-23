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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.data.Idea
import app.vesper.ui.Px
import kotlinx.coroutines.launch

/** The card's title: "daily · 2 of 4". */
internal fun dailyTitle(items: List<Idea>) = "daily · ${items.count { it.done }} of ${items.size}"
/** The daily brain's brightness on the map: dim while any item is undone, fully lit when all are done or there are none. */
internal fun dailyBrightness(items: List<Idea>) = if (items.any { !it.done }) DAILY_DIM else 1f
internal const val DAILY_DIM = 0.45f
/** The "remind at" chip's words for [hour] (null = off). */
internal fun remindLabel(hour: Int?) = if (hour == null) "remind: off" else "remind at ${hour.toString().padStart(2, '0')}:00"

private val CardPurple = Color(0xFF9C84FF)
private val RowInk = Color(0xFFE6DCFF)
private val RowDone = Color(0xFF8F82A8)

/**
 * The daily brain opened: a small checklist card grown from [from] (px in the root). A tap on a row ticks or unticks it; a long press
 * gives rename and delete ([onItemMenu], with where it was pressed, px in the root); "+ add" adds one. [remindAt] and [onRemind]
 * drive the "remind at" chip, which is left out where there is no reminder ([onRemind] null: desktop). Back, CLOSE, a tap outside or
 * [closeSignal] closes it.
 */
@Composable
internal fun DailyCard(
    items: List<Idea>,
    from: Offset,
    closeSignal: Int,
    onToggle: (Idea) -> Unit,
    onItemMenu: (Idea, Offset) -> Unit,
    onAdd: () -> Unit,
    remindAt: Int?,
    onRemind: (() -> Unit)?,
    onClosed: () -> Unit,
) {
    val p = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Sounds.play(Sfx.Open); p.animateTo(1f, tween(300, easing = CubicBezierEasing(0.34f, 1.35f, 0.64f, 1f))) }
    fun close() {
        if (closing) return
        closing = true
        Sounds.play(Sfx.Close)
        scope.launch { p.animateTo(0f, tween(220, easing = FastOutSlowInEasing)); onClosed() }
    }
    var seenSignal by remember { mutableIntStateOf(closeSignal) }
    LaunchedEffect(closeSignal) { if (closeSignal != seenSignal) { seenSignal = closeSignal; close() } }
    BackHandler(true, ::close)
    val shape = RoundedCornerShape(14.dp)
    val font = PageFont

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
                        dailyTitle(items), Modifier.weight(1f).semantics { contentDescription = dailyTitle(items) },
                        style = TextStyle(fontFamily = font, fontSize = 30.sp, color = Px.Lavender, shadow = Shadow(Px.Lavender.copy(alpha = 0.6f), blurRadius = 12f)),
                    )
                    Text("CLOSE", Modifier.clickable(onClick = ::close).padding(10.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender))
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                    for (i in items) DailyRow(i, font, { onToggle(i) }, { at -> onItemMenu(i, at) })
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAdd).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+ add", style = TextStyle(fontFamily = font, fontSize = 22.sp, color = Px.TextDim))
                    }
                }
                if (onRemind != null) {
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp), horizontalArrangement = Arrangement.End) {
                        val pill = RoundedCornerShape(50)
                        Text(
                            remindLabel(remindAt),
                            Modifier.clip(pill).border(1.dp, Px.Border, pill).clickable(role = Role.Button, onClick = onRemind).padding(horizontal = 12.dp, vertical = 8.dp),
                            style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim),
                        )
                    }
                }
            }
        }
    }
}

/** One item: a pixel checkbox and its name; a tap ticks it, a long press asks rename or delete. */
@Composable
private fun DailyRow(item: Idea, font: androidx.compose.ui.text.font.FontFamily, onTap: () -> Unit, onLong: (Offset) -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val name = ideaLabel(item).ifBlank { "..." }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .semantics(mergeDescendants = true) { role = Role.Checkbox; contentDescription = name; stateDescription = if (item.done) "done" else "not done" }
            .pointerInput(item.id) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLong(origin + it) }) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelCheck(item.done)
        Spacer(Modifier.width(12.dp))
        Text(name, style = TextStyle(fontFamily = font, fontSize = 22.sp, color = if (item.done) RowDone else RowInk))
    }
}

private val BOX_ROWS = listOf(
    "XXXXXXXXX", "X.......X", "X.......X", "X.......X", "X.......X", "X.......X", "X.......X", "X.......X", "XXXXXXXXX",
)
private val TICK_ROWS = listOf(
    "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXoX", "XXXXXXoXX", "XoXXXoXXX", "XXoXoXXXX", "XXXoXXXXX", "XXXXXXXXX", "XXXXXXXXX",
)

/** A 9x9 pixel checkbox: an empty lavender box, or a filled one with a dark tick. */
@Composable
private fun PixelCheck(done: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val u = kotlin.math.floor(size.width / 9f).coerceAtLeast(1f)
        val off = (size.width - 9 * u) / 2f
        (if (done) TICK_ROWS else BOX_ROWS).forEachIndexed { y, r ->
            r.forEachIndexed { x, ch ->
                if (ch != '.') drawRect(if (ch == 'o') Color(0xFF14091F) else Px.Lavender, Offset(off + x * u, off + y * u), Size(u, u))
            }
        }
    }
}
