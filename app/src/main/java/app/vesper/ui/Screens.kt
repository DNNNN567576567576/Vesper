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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.vesper.R
import app.vesper.data.Note
import app.vesper.data.Task
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin


@Composable
fun ScreenTitle(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp),
        style = TextStyle(
            fontFamily = TitleFont,
            fontSize = 40.sp,
            color = Px.Lavender,
            shadow = Shadow(Color(0xFF6E1F5A), Offset(3f, 3f), 0f),
        ),
    )
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
                fontFamily = PageFont,
                fontSize = 28.sp,
                lineHeight = 30.sp,
                color = Px.Text,
                shadow = Shadow(Color(0xFF53194A), Offset(2f, 2f), 0f),
            ),
        )
        if (hint != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                hint,
                style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = Px.TextDim.copy(alpha = 0.85f)),
            )
        }
    }
}


// ---------------------------------------------------------------------------------------------
// Notes: pinned rotting pages
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
    val time = rememberRotTime()
    val pxSize = rememberRotPxSize()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = maxWidth.value / 360f
        val pageWidth = maxWidth * (256f / 360f)
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Notes")
            when {
                notes == null -> Unit
                notes.isEmpty() -> EmptyState("Your pages await.", hint = "Tap the plus to begin.")
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 2.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(notes, key = { _, n -> n.id }) { index, note ->
                        NotePage(
                            note = note,
                            index = index,
                            pageWidth = pageWidth,
                            left = (if (index % 2 == 0) 26f else 74f).times(scale).dp,
                            time = time,
                            pxSize = pxSize,
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
private fun NotePage(
    note: Note,
    index: Int,
    pageWidth: Dp,
    left: Dp,
    time: FloatState,
    pxSize: Float,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
) {
    val label = note.title.trim().ifEmpty { "Untitled page" }
    val snippet = remember(note.content) {
        note.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 1100f),
        label = "pagePress",
    )
    val px = pxSize.toInt()
    val density = LocalDensity.current
    val pinZone = with(density) { (9 * px).toDp() }
    val shadowPad = with(density) { (PAPER_SHADOW_PX * px).toDp() }
    val pin = remember { Pix(PIN_W, PIN_H) }
    val pinOffset = if (index % 2 == 0) -14 else 10
    val swayPx = with(density) { 3.dp.toPx() }
    val id = note.id

    Box(
        modifier
            .padding(start = left)
            .width(pageWidth)
            .graphicsLayer {
                translationX = sin(time.floatValue * 0.8f + index * 1.3f) * swayPx
                scaleX = press
                scaleY = press
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        // Paper is drawn under the text, once per size, seeded by the note id.
        Column(
            Modifier
                .padding(top = pinZone)
                .fillMaxWidth()
                .drawWithCache {
                    val paper = Pix(ceil(size.width / px).toInt(), ceil(size.height / px).toInt())
                    renderPaper(paper, id)
                    onDrawBehind { drawIntoCanvas { paper.blit(it.nativeCanvas, 0, 0, px) } }
                }
                .padding(start = 18.dp, end = 18.dp + shadowPad, top = 22.dp, bottom = 20.dp + shadowPad),
        ) {
            Text(
                label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = PageFont, fontSize = 21.sp, lineHeight = 22.sp, color = Color(0xFF1E0F28)),
            )
            if (snippet != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    snippet,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = PageFont, fontSize = 18.sp, lineHeight = 19.sp, color = Color(0xFF4A3458)),
                )
            }
        }
        // The glowing pin sits above the text. Its head is 2 low-res px below the paper's top edge.
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(pinZone)
                .drawBehind {
                    drawPin(pin, time.floatValue, index)
                    drawIntoCanvas {
                        pin.blit(
                            it.nativeCanvas,
                            (size.width / 2f).toInt() + (pinOffset - PIN_CX) * px,
                            (2 + 9 - PIN_CY) * px,
                            px,
                        )
                    }
                },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Tasks: purple candles
// ---------------------------------------------------------------------------------------------

@Composable
fun TasksScreen(
    tasks: List<Task>?,
    listState: LazyListState,
    onComplete: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val time = rememberRotTime()
    val pxSize = rememberRotPxSize()
    val completing = remember { mutableStateMapOf<Long, Float>() }
    val fired = remember { HashSet<Long>() }
    val latestComplete by rememberUpdatedState(onComplete)
    val scope = rememberCoroutineScope()
    val candles = remember { CandleRenderer() }

    // Leaving the tab mid-animation must not lose the completion.
    DisposableEffect(Unit) {
        onDispose { completing.keys.toList().forEach { if (fired.add(it)) latestComplete(it) } }
    }

    val begin: (Long) -> Unit = { id ->
        if (!completing.containsKey(id)) {
            completing[id] = time.floatValue
            scope.launch {
                delay(1200)
                if (fired.add(id)) latestComplete(id)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Tasks")
            when {
                tasks == null -> Unit
                tasks.isEmpty() -> EmptyState("No quests remain.", hint = "Tap the plus to add one.")
                else -> Box(Modifier.fillMaxSize()) {
                    // Pools of light, ledges and candles live in one layer behind the rows (text never overlaps them).
                    Canvas(Modifier.fillMaxSize()) {
                        val t = time.floatValue
                        val w = ceil(size.width / pxSize).toInt()
                        val h = ceil(size.height / pxSize).toInt()
                        if (w <= 0 || h <= 0) return@Canvas
                        candles.begin(w, h)
                        val info0 = listState.layoutInfo
                        val items = info0.visibleItemsInfo
                        // Item offsets are measured from the content start; shift by the top padding to get canvas y.
                        val pad = -info0.viewportStartOffset
                        for (i in 0 until items.size) {
                            val info = items[i]
                            val id = info.key as? Long ?: continue
                            val ly = ((info.offset + pad + info.size) / pxSize).roundToInt() - 4
                            val start = completing[id]
                            candles.row(id, ly, w, t, if (start == null) -1f else t - start)
                        }
                        drawIntoCanvas { candles.pix.blit(it.nativeCanvas, 0, 0, pxSize.toInt()) }
                    }
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = 22.dp, bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(tasks, key = { it.id }) { task ->
                            TaskRow(
                                task = task,
                                completing = completing,
                                time = time,
                                modifier = Modifier.animateItem(placementSpec = null, fadeInSpec = tween(200), fadeOutSpec = tween(160)),
                                onTap = { begin(task.id) },
                            )
                        }
                    }
                }
            }
        }
        PixelFab("Add task", onAdd, Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp))
    }
}

@Composable
private fun TaskRow(
    task: Task,
    completing: Map<Long, Float>,
    time: FloatState,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val id = task.id
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val start = completing[id]
                alpha = if (start == null) 1f else rotRowFade(time.floatValue - start)
            }
            .heightIn(min = 54.dp)
            .height(IntrinsicSize.Min),
    ) {
        // Tap target over the candle.
        Box(
            Modifier
                .width(70.dp)
                .fillMaxHeight()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Checkbox,
                    onClickLabel = "Complete and remove task",
                    onClick = {
                        if (!completing.containsKey(id)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTap()
                        }
                    },
                ),
        )
        Text(
            task.text,
            modifier = Modifier.weight(1f).padding(top = 12.dp, bottom = 16.dp, end = 18.dp),
            style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, lineHeight = 24.sp, color = Px.Text),
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
            NavTab(
                icon = 0,
                grid = Icons.Brain,
                label = "Brain",
                active = selected == 2,
                onClick = { onSelect(2) },
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
    grid: List<String>? = null,
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
        if (grid != null) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                PixelArt(
                    grid, mapOf('X' to Px.Magenta, 'o' to Px.Lavender), 3.dp,
                    Modifier.graphicsLayer {
                        val k = 0.84f + 0.16f * navScale
                        scaleX = k
                        scaleY = k
                        alpha = if (active) 1f else 0.38f
                    },
                )
            }
        } else {
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
        }
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

