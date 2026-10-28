package app.vesper.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.R
import app.vesper.data.Note
import app.vesper.data.Task
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Deep dungeon backdrop: violet gradient, tiled stone wall, torch haze, scanlines, stars, vignette. */
@Composable
fun PixelBackdrop(modifier: Modifier = Modifier) {
    val stars = listOf(
        0.08f to 0.12f, 0.31f to 0.06f, 0.62f to 0.09f, 0.90f to 0.15f,
        0.18f to 0.34f, 0.76f to 0.40f, 0.05f to 0.58f, 0.52f to 0.52f,
        0.93f to 0.66f, 0.27f to 0.76f, 0.70f to 0.82f, 0.12f to 0.93f, 0.45f to 0.95f,
    )
    val ambient = rememberInfiniteTransition(label = "ambient")
    // Slow clock for twinkling stars and rising embers.
    val drift by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
        label = "drift",
    )
    // Unsteady torch light.
    val flicker by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "flicker",
    )
    val wall = ImageBitmap.imageResource(R.drawable.wall_tile)
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF1D0F2E),
                0.42f to Px.Bg,
                1f to Color(0xFF06020B),
            )
        )

        // Stone wall, tiled by hand so the brick scale stays chunky and undistorted.
        val tile = 176.dp.toPx().toInt().coerceAtLeast(1)
        var ty = 0
        while (ty < h) {
            var tx = 0
            while (tx < w) {
                drawImage(
                    image = wall,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(wall.width, wall.height),
                    dstOffset = IntOffset(tx, ty),
                    dstSize = IntSize(tile, tile),
                    alpha = 0.22f,
                    filterQuality = FilterQuality.None,
                )
                tx += tile
            }
            ty += tile
        }

        // Torch haze behind the content, breathing.
        drawRect(
            Brush.radialGradient(
                colors = listOf(Px.Primary.copy(alpha = 0.20f + 0.12f * flicker), Color.Transparent),
                center = Offset(w * 0.5f, h * 0.38f),
                radius = h * (0.53f + 0.04f * flicker),
            )
        )

        // Faint scanlines give the flat fill a bit of CRT tooth.
        val step = 3.dp.toPx()
        val lineH = 1.dp.toPx()
        var y = 0f
        while (y < h) {
            drawRect(Color.Black.copy(alpha = 0.10f), Offset(0f, y), Size(w, lineH))
            y += step
        }

        val s = 3.dp.toPx()
        stars.forEachIndexed { i, (fx, fy) ->
            val twinkle = 0.55f + 0.45f * sin((drift + i * 0.083f) * 2f * PI.toFloat())
            val base = if (i % 4 == 0) 0.16f else 0.20f
            val c = (if (i % 4 == 0) Px.Magenta else Px.Lavender).copy(alpha = base * twinkle)
            val x = (fx * w / s).toInt() * s
            val sy = (fy * h / s).toInt() * s
            drawRect(c, Offset(x, sy), Size(s, s))
            if (i % 3 == 0) {
                drawRect(c, Offset(x - s, sy), Size(s, s)); drawRect(c, Offset(x + s, sy), Size(s, s))
                drawRect(c, Offset(x, sy - s), Size(s, s)); drawRect(c, Offset(x, sy + s), Size(s, s))
            }
        }

        // Embers drifting up out of the dark, fading as they rise.
        val ember = 2.dp.toPx()
        repeat(9) { i ->
            val phase = (drift + i / 9f) % 1f
            val ey = h * (1.02f - phase * 1.10f)
            val sway = sin((phase * 3.1f + i) * PI.toFloat() * 2f) * 14.dp.toPx()
            val ex = w * (0.10f + 0.80f * ((i * 0.371f) % 1f)) + sway
            val fade = sin(phase * PI.toFloat()).coerceIn(0f, 1f) * 0.55f
            val col = (if (i % 3 == 0) Px.Magenta else Px.Lavender).copy(alpha = fade)
            drawRect(col, Offset(ex.roundToInt() / s * s, ey.roundToInt() / s * s), Size(ember, ember))
        }

        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.Transparent, Color(0xFF05010C).copy(alpha = 0.90f)),
                center = Offset(w * 0.5f, h * 0.42f),
                radius = h * 0.78f,
            )
        )
    }
}

/** Chunky dotted divider used under the masthead. */
@Composable
private fun DottedRule(modifier: Modifier = Modifier, color: Color = Px.Border) {
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val s = 5.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawRect(color, Offset(x, 0f), Size(s / 2f, size.height))
            x += s
        }
    }
}

@Composable
fun ScreenTitle(text: String) {
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = TextStyle(
                    fontFamily = PixelFont,
                    fontSize = 24.sp,
                    color = Px.Lavender,
                    shadow = Shadow(Color(0xFF6E1F5A), Offset(3f, 3f), 0f),
                ),
            )
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        DottedRule()
        Spacer(Modifier.height(12.dp))
    }
}

/** Square plus button, bottom-right, with a hard pixel bevel and a breathing halo. */
@Composable
fun PixelFab(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f),
        label = "fabPress",
    )
    val glow by rememberInfiniteTransition(label = "fabGlow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    Box(
        modifier
            .size(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        colors = listOf(Px.Primary.copy(alpha = 0.26f + 0.16f * glow), Color.Transparent),
                        center = center,
                        radius = size.maxDimension * (0.78f + 0.16f * glow),
                    )
                )
            }
            .pixelCard(fill = Px.Primary, border = Px.Lavender, notch = 5.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 2.dp.toPx()
            val t = 1.5.dp.toPx()
            drawRect(Px.Lavender.copy(alpha = 0.40f), Offset(inset, inset), Size(size.width - inset * 2f, t))
            drawRect(Color.Black.copy(alpha = 0.45f), Offset(inset, size.height - inset - t), Size(size.width - inset * 2f, t))
        }
        PixelIcon(Icons.Plus, Px.Text, pixel = 4.dp)
    }
}

@Composable
fun PixelButton(text: String, onClick: () -> Unit, primary: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .pixelCard(
                fill = if (primary) Px.Primary else Px.Elevated,
                border = if (primary) Px.Lavender else Px.Border,
                shadow = false,
                notch = 3.dp,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, color = Px.Text))
    }
}

@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val bob by rememberInfiniteTransition(label = "mascotBob").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    Column(
        modifier.fillMaxSize().padding(bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(250.dp)) {
                drawCircle(
                    Brush.radialGradient(
                        colors = listOf(Px.Primary.copy(alpha = 0.26f + 0.12f * bob), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    )
                )
            }
            Image(
                bitmap = ImageBitmap.imageResource(R.drawable.mascot_pixel),
                contentDescription = "Mascot Guardian",
                modifier = Modifier.offset(y = (-7 * bob).dp).size(172.dp),
                filterQuality = FilterQuality.None,
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            message,
            style = TextStyle(
                fontFamily = PixelFont,
                fontSize = 12.sp,
                lineHeight = 22.sp,
                color = Px.Text,
                shadow = Shadow(Color(0xFF53194A), Offset(2f, 2f), 0f),
            ),
        )
        if (hint != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                hint,
                style = TextStyle(fontSize = 13.sp, letterSpacing = 0.4.sp, color = Px.TextDim.copy(alpha = 0.7f)),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Notes
// ---------------------------------------------------------------------------------------------

@Composable
fun NotesScreen(
    notes: List<Note>?,
    listState: LazyListState,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Notes")
            when {
                notes == null -> Unit
                notes.isEmpty() -> EmptyState("Your pages await.", hint = "Tap the plus to begin.")
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 18.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            modifier = Modifier.animateItem(fadeInSpec = tween(220), fadeOutSpec = tween(180)),
                            onLongClick = { pendingDelete = note.id },
                        ) { onOpen(note.id) }
                    }
                }
            }
        }
        PixelFab("New note", onNew, Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp))
    }

    pendingDelete?.let { id ->
        PixelDialog(onDismiss = { pendingDelete = null }) {
            Text(
                "DELETE PAGE?",
                style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 0.5.sp, color = Px.Lavender),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Gone for good. No trash, no undo.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = Px.TextDim),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { pendingDelete = null })
                PixelButton("DELETE", {
                    pendingDelete = null
                    onDelete(id)
                }, primary = true)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: Note,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
) {
    val label = note.title.trim().ifEmpty { "Untitled page" }
    val accentColor = when ((note.id % 3).toInt()) {
        0 -> Px.Magenta
        1 -> Px.Primary
        else -> Px.Lavender
    }
    val accentIcon = when ((note.id % 3).toInt()) {
        0 -> Icons.Gem
        1 -> Icons.Rune
        else -> Icons.Star
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 1100f),
        label = "cardPress",
    )
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(IntrinsicSize.Min)
            .pixelCard(fill = Px.Elevated)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accentColor))
        Row(
            Modifier.weight(1f).padding(start = 14.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .pixelCard(fill = Px.Surface, border = accentColor.copy(alpha = 0.5f), shadow = false, notch = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                PixelIcon(accentIcon, accentColor, pixel = 2.dp)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, lineHeight = 20.sp, color = Px.Text),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tasks
// ---------------------------------------------------------------------------------------------

@Composable
fun TasksScreen(
    tasks: List<Task>?,
    listState: LazyListState,
    onComplete: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Tasks")
            when {
                tasks == null -> Unit
                tasks.isEmpty() -> EmptyState("No quests remain.", hint = "Tap the plus to add one.")
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 18.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(task, Modifier.animateItem(fadeInSpec = tween(200), fadeOutSpec = tween(160))) { onComplete(task.id) }
                    }
                }
            }
        }
        PixelFab("Add task", onAdd, Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp))
    }
}

@Composable
private fun TaskRow(task: Task, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .pixelCard(fill = Px.Elevated, shadow = false)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 56dp touch target around the 24dp pixel checkbox.
        Box(
            Modifier
                .size(56.dp)
                .clickable(
                    interactionSource = MutableInteractionSource(),
                    indication = null,
                    role = Role.Checkbox,
                    onClickLabel = "Complete and remove task",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDone()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            PixelArt(Icons.Ring, mapOf('X' to Px.Primary, 'o' to Px.Surface), pixel = 3.dp)
        }
        Text(
            task.text,
            modifier = Modifier.weight(1f).padding(vertical = 16.dp).padding(end = 4.dp),
            style = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, color = Px.Text),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom navigation
// ---------------------------------------------------------------------------------------------

@Composable
fun PixelNavBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Px.Surface)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(72.dp)) {
            NavTab(
                icon = R.drawable.grimoire_tab,
                label = "Notes",
                active = selected == 0,
                onClick = { onSelect(0) },
                modifier = Modifier.weight(1f),
            )
            NavTab(
                icon = R.drawable.scroll_tab,
                label = "Tasks",
                active = selected == 1,
                onClick = { onSelect(1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NavTab(
    icon: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val navScale by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 640f),
        label = "navScale",
    )
    Column(
        modifier
            .fillMaxSize()
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            bitmap = ImageBitmap.imageResource(icon),
            contentDescription = null,
            modifier = Modifier
                .size(34.dp)
                .graphicsLayer {
                    val k = 0.84f + 0.16f * navScale
                    scaleX = k
                    scaleY = k
                    alpha = if (active) 1f else 0.38f
                },
            filterQuality = FilterQuality.None,
        )
        Text(
            label,
            modifier = Modifier.padding(top = 5.dp),
            style = TextStyle(
                fontFamily = PixelFont,
                fontSize = 9.sp,
                letterSpacing = 0.5.sp,
                color = if (active) Px.Lavender else Px.TextDim.copy(alpha = 0.8f),
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            ),
        )
    }
}
