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
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.vesper.NoteSort
import app.vesper.NotesViewModel
import app.vesper.TasksViewModel
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
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

/** Pixel-art round add button: a glowing sigil with a slowly turning dotted ring. Drawn on a 41x41 grid of 2dp pixels (the 58dp button plus room for the glow and the press ring). */
@Composable
fun SigilFab(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f),
        label = "sigilScale",
    )
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(140),
        label = "sigilPress",
    )
    val time = rememberRotTime()
    val ringStart = remember { mutableFloatStateOf(-10f) }
    LaunchedEffect(pressed) { if (pressed) ringStart.floatValue = time.floatValue }
    val pix = remember { Pix(SIGIL_GRID, SIGIL_GRID) }
    val dst = remember { android.graphics.Rect() }
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = false; isFilterBitmap = false } }
    Box(
        modifier
            .size(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
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
        Canvas(Modifier.requiredSize((SIGIL_GRID * 2).dp)) {
            drawSigil(pix, time.floatValue, press, ringStart.floatValue)
            drawIntoCanvas {
                dst.set(0, 0, size.width.toInt(), size.height.toInt())
                it.nativeCanvas.drawBitmap(pix.bmp, null, dst, paint)
            }
        }
    }
}

private const val SIGIL_GRID = 41

private fun drawSigil(pix: Pix, t: Float, press: Float, ringStart: Float) {
    val c = SIGIL_GRID / 2 + 0.5f
    pix.clear()
    pix.glow(c, c, 17f, 0x7540B8, 0.35f + 0.15f * sin(t * 1.6f) + 0.4f * press)

    // Disc with a 1px rim.
    val r = 9.5f
    val lo = (c - r - 1f).toInt()
    val hi = (c + r + 1f).toInt()
    for (y in lo..hi) {
        for (x in lo..hi) {
            val d = hypot(x + 0.5f - c, y + 0.5f - c)
            if (d <= r) pix.dot(x, y, 1, 1, if (d > r - 1f) 0x5A3E7A else 0x120A1A)
        }
    }

    // Dotted ring.
    val shift = floor(t * 3f).toInt()
    for (i in 0 until 40) {
        if ((i + shift) % 4 == 0) continue
        val a = i / 40f * 2f * PI.toFloat() + t * 0.6f
        pix.dot(floor(c + cos(a) * 12f).toInt(), floor(c + sin(a) * 12f).toInt(), 1, 1, if (i % 5 == 0) 0xD45AAB else 0x8A6AD0)
    }

    // Plus.
    val pressedLook = press > 0.5f
    val arm = if (pressedLook) 0xFFD0F0 else 0xC9B0FF
    val ci = c.toInt()
    pix.dot(ci - 5, ci, 11, 1, arm)
    pix.dot(ci, ci - 5, 1, 11, arm)
    pix.dot(ci, ci, 1, 1, if (pressedLook) 0xFFD0F0 else 0xE6DCFF)

    // Ring released by a press.
    val age = t - ringStart
    if (age in 0f..0.6f) {
        val k = age / 0.6f
        val rr = 10f + 8f * k
        for (i in 0 until 16) {
            val a = i / 16f * 2f * PI.toFloat()
            pix.dot(floor(c + cos(a) * rr).toInt(), floor(c + sin(a) * rr).toInt(), 1, 1, 0xD45AAB, 1f - k)
        }
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
    var burnId by remember { mutableStateOf<Long?>(null) }
    val vm: NotesViewModel = viewModel()
    val sort by vm.sort.collectAsState()
    val manual = sort == NoteSort.MANUAL
    val sortLabel = when (sort) { NoteSort.EDITED -> "recent"; NoteSort.ADDED -> "newest"; NoteSort.MANUAL -> "manual" }
    // Local copy of the order while a page is being dragged; reset whenever the database list changes.
    var order by remember(notes) { mutableStateOf(notes.orEmpty()) }
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val shown = if (manual) order else notes.orEmpty()
    // A confirmed burn must always delete, even if the user leaves Notes mid-animation.
    val latestDelete by rememberUpdatedState(onDelete)
    val latestBurnId by rememberUpdatedState(burnId)
    DisposableEffect(Unit) {
        onDispose { latestBurnId?.let { latestDelete(it) } }
    }
    val time = rememberRotTime()
    val pxSize = rememberRotPxSize()
    val spacingPx = with(LocalDensity.current) { 4.dp.toPx() }

    // Feed the list scroll to the living wall behind for parallax.
    LaunchedEffect(listState, spacingPx) {
        snapshotFlow {
            val vis = listState.layoutInfo.visibleItemsInfo
            if (vis.isEmpty()) 0f
            else {
                val avg = vis.sumOf { it.size }.toFloat() / vis.size + spacingPx
                listState.firstVisibleItemIndex * avg + listState.firstVisibleItemScrollOffset
            }
        }.collect { NotesWallScroll.offsetPx.floatValue = it }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = maxWidth.value / 360f
        val pageWidth = maxWidth * (256f / 360f)
        Column(Modifier.fillMaxSize()) {
            if (notes == null || notes.isEmpty()) TitleWithSort("Scrolls", sortLabel, vm::cycleSort)
            else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 2.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "title") { TitleWithSort("Scrolls", sortLabel, vm::cycleSort) }
                    itemsIndexed(shown, key = { _, n -> n.id }) { index, note ->
                        val isDragged = dragId == note.id
                        NotePage(
                            note = note,
                            index = index,
                            pageWidth = pageWidth,
                            left = (if (index % 2 == 0) 26f else 74f).times(scale).dp,
                            time = time,
                            pxSize = pxSize,
                            held = pendingDelete == note.id,
                            burning = burnId == note.id,
                            modifier = if (isDragged) Modifier.zIndex(1f).graphicsLayer { translationY = dragDy }
                            else Modifier.animateItem(fadeInSpec = tween(220), fadeOutSpec = tween(180)),
                            onLongClick = { pendingDelete = note.id },
                            onSwipeDelete = { pendingDelete = note.id },
                            onBurned = { burnId = null; onDelete(note.id) },
                            dragHandle = if (!manual) null else Modifier.pointerInput(note.id) {
                                detectDragGestures(
                                    onDragStart = { dragId = note.id; dragDy = 0f },
                                    onDragEnd = { dragId = null; dragDy = 0f; vm.move(order.map { it.id }) },
                                    onDragCancel = { dragId = null; dragDy = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragDy += amount.y
                                    val items = listState.layoutInfo.visibleItemsInfo
                                    val cur = items.firstOrNull { it.key == note.id } ?: return@detectDragGestures
                                    val centre = cur.offset + dragDy + cur.size / 2f
                                    val hit = items.firstOrNull { it.key != note.id && it.key is Long && centre >= it.offset && centre < it.offset + it.size }
                                        ?: return@detectDragGestures
                                    val from = order.indexOfFirst { it.id == note.id }
                                    val to = order.indexOfFirst { it.id == hit.key }
                                    if (from < 0 || to < 0) return@detectDragGestures
                                    // The dragged page takes the other's slot; keep it under the finger.
                                    val newOffset = if (to > from) hit.offset + hit.size - cur.size else hit.offset
                                    dragDy += cur.offset - newOffset
                                    order = order.toMutableList().apply { add(to, removeAt(from)) }
                                }
                            },
                        ) { onOpen(note.id) }
                    }
                }
            }
        }
        SigilFab("New scroll", onNew, Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp))
    }

    pendingDelete?.let { id ->
        PixelDialog(onDismiss = { pendingDelete = null }) {
            Text(
                "Burn this page?",
                style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 0.5.sp, color = Px.Lavender),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Gone for good. No undo.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = Px.TextDim),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("keep", { pendingDelete = null })
                PixelButton("burn", {
                    pendingDelete = null
                    burnId = id
                }, primary = true)
            }
        }
    }
}

private val EmberPink = Color(0xFFD45AAB)
private val EmberHot = Color(0xFFFFB0E0)
private val EmberViolet = Color(0xFF9A6BE0)
private val AshDark = Color(0xFF5A4A6A)
private val AshDarker = Color(0xFF3A2A48)
private const val BURN_MS = 900
private const val PAGE_TOP_ROWS = 9

/** Glow and flickering embers on the page's right edge while it is dragged left. */
private fun DrawScope.drawSwipeEdge(k: Float, t: Float, px: Float, rightInset: Float) {
    if (k <= 0f) return
    val edge = size.width - rightInset
    val strip = 10f * px
    drawRect(
        Brush.horizontalGradient(
            listOf(Color.Transparent, EmberPink.copy(alpha = 0.5f * k)),
            startX = edge - strip,
            endX = edge,
        ),
        topLeft = Offset(edge - strip, 0f),
        size = Size(strip, size.height),
        blendMode = BlendMode.Plus,
    )
    val rows = (size.height / px).toInt()
    if (rows <= PAGE_TOP_ROWS) return
    val bucket = floor(t * 12f).toInt()
    for (i in 0 until 8) {
        val r = PAGE_TOP_ROWS + (hf(i, bucket) * (rows - PAGE_TOP_ROWS)).toInt()
        val dx = 1 + (hf(i + 31, bucket) * 3f).toInt()
        val col = when (i % 3) { 0 -> EmberPink; 1 -> EmberHot; else -> EmberViolet }
        drawRect(col.copy(alpha = min(1f, k + 0.2f)), Offset(edge - dx * px, r * px), Size(px, px))
    }
}

/** Burn-away: a ragged front sweeps right to left. Must run inside an offscreen layer so Clear punches through to the wall. */
private fun DrawScope.drawBurn(progress: Float, t: Float, px: Float, seed: Int) {
    val k = progress * progress * (3f - 2f * progress)
    val wLr = size.width / px
    val rows = ceil(size.height / px).toInt()
    fun fx(r: Int): Float = (wLr + 3f) - (wLr + 6f) * k + sin(r * 0.7f + t * 9f) * 2f

    for (r in 0 until rows) {
        val x = max(0f, fx(r) * px)
        if (x < size.width) {
            drawRect(Color.Black, Offset(x, r * px), Size(size.width - x, px), blendMode = BlendMode.Clear)
        }
    }
    val bucket = floor(t * 16f).toInt()
    for (r in PAGE_TOP_ROWS + 1 until rows) {
        val xi = floor(fx(r)).toInt()
        if (xi <= 0 || xi * px >= size.width + px * 4f) continue
        drawRect(Color(0xFF1A0A14), Offset((xi - 1) * px, r * px), Size(px, px))
        if (xi >= 2) drawRect(Color(0xFF2A1220), Offset((xi - 2) * px, r * px), Size(px, px))
        if (xi >= 3) drawRect(Color(0xFF3A1A2A).copy(alpha = 0.7f), Offset((xi - 3) * px, r * px), Size(px, px))
        val h = hf(r * 7 + seed, bucket)
        if (h < 0.4f) {
            val col = if (h < 0.15f) EmberPink else if (h < 0.28f) EmberHot else EmberViolet
            drawRect(col, Offset(xi * px, r * px), Size(px, px))
        }
    }
    for (i in 0 until 28) {
        val age = (progress * 2.4f + hf(i, seed)) % 1f
        val r0 = PAGE_TOP_ROWS + (hf(i, seed + 5) * (rows - PAGE_TOP_ROWS)).toInt()
        val x = fx(r0) + age * 6f
        val y = r0 - age * 18f
        if (x < 0f || x * px >= size.width || y < 0f) continue
        drawRect(
            (if (i % 2 == 0) AshDark else AshDarker).copy(alpha = (1f - age) * 0.9f),
            Offset(floor(x) * px, floor(y) * px),
            Size(px, px),
        )
    }
}

/** Screen title with a tiny sort toggle on the right. */
@Composable
private fun TitleWithSort(title: String, label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        ScreenTitle(title)
        Text(
            "sort: $label",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .clickable(role = Role.Button, onClickLabel = "change sort order", onClick = onClick)
                .padding(start = 12.dp, end = 22.dp, top = 8.dp, bottom = 14.dp),
            style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim.copy(alpha = 0.7f)),
        )
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
    held: Boolean,
    burning: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    onSwipeDelete: () -> Unit,
    onBurned: () -> Unit,
    dragHandle: Modifier? = null,
    onClick: () -> Unit,
) {
    val label = note.title.trim().ifEmpty { "Untitled page" }
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

    val scope = rememberCoroutineScope()
    val dragPx = remember { mutableFloatStateOf(0f) }
    val burnStart = remember { mutableFloatStateOf(-1f) }
    val springJob = remember { arrayOfNulls<Job>(1) }
    val latestSwipe by rememberUpdatedState(onSwipeDelete)
    val latestBurned by rememberUpdatedState(onBurned)
    val revealPx = with(density) { 110.dp.toPx() }
    val commitPx = with(density) { 120.dp.toPx() }
    val flingPx = with(density) { 900.dp.toPx() }
    val leftPx = with(density) { left.toPx() }
    val pageWpx = with(density) { pageWidth.toPx() }
    val gapPx = with(density) { 14.dp.toPx() }
    val shadowPx = (PAPER_SHADOW_PX * px).toFloat()

    fun springBack() {
        springJob[0]?.cancel()
        springJob[0] = scope.launch {
            animate(dragPx.floatValue, 0f, animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f)) { v, _ ->
                dragPx.floatValue = v
            }
        }
    }

    // Dialog dismissed ("keep" or tapped away): the page springs back.
    LaunchedEffect(held) {
        if (!held && !burning && dragPx.floatValue != 0f) springBack()
    }
    LaunchedEffect(burning) {
        if (burning) {
            springJob[0]?.cancel()
            burnStart.floatValue = time.floatValue
            delay(BURN_MS.toLong())
            latestBurned()
        }
    }

    Box(modifier.fillMaxWidth()) {
        // "burn it" waits behind the page and fades in with the drag.
        Text(
            "burn it",
            softWrap = false,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    alpha = (-dragPx.floatValue / revealPx).coerceIn(0f, 1f)
                    translationX = leftPx + pageWpx + gapPx + dragPx.floatValue
                },
            style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = EmberPink),
        )
        Box(
            Modifier
                .padding(start = left)
                .width(pageWidth)
                .graphicsLayer {
                    translationX = sin(time.floatValue * 0.8f + index * 1.3f) * swayPx + dragPx.floatValue
                    scaleX = press
                    scaleY = press
                    compositingStrategy = if (burning) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .drawWithContent {
                    drawContent()
                    val t = time.floatValue
                    if (burning) {
                        val bs = burnStart.floatValue
                        if (bs >= 0f) drawBurn(((t - bs) / (BURN_MS / 1000f)).coerceIn(0f, 1f), t, pxSize, id.toInt())
                    } else {
                        drawSwipeEdge((-dragPx.floatValue / revealPx).coerceIn(0f, 1f), t, pxSize, shadowPx)
                    }
                }
                .combinedClickable(
                    enabled = !burning,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .pointerInput(burning) {
                    if (burning) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPointerInputChange(down)
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            springJob[0]?.cancel()
                            dragPx.floatValue = (dragPx.floatValue + over).coerceIn(-size.width.toFloat(), 0f)
                        }
                        if (slop != null) {
                            tracker.addPointerInputChange(slop)
                            val finished = horizontalDrag(slop.id) { change ->
                                tracker.addPointerInputChange(change)
                                dragPx.floatValue = (dragPx.floatValue + change.positionChange().x)
                                    .coerceIn(-size.width.toFloat(), 0f)
                                change.consume()
                            }
                            val vx = tracker.calculateVelocity().x
                            val d = dragPx.floatValue
                            if (finished && (d <= -commitPx || (vx <= -flingPx && d <= -commitPx / 3f))) latestSwipe()
                            else springBack()
                        }
                    }
                },
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
                    style = TextStyle(fontFamily = PageFont, fontSize = 21.sp, lineHeight = 22.sp, color = Color(0xFF140A1C)),
                )
            }
            if (dragHandle != null) {
                // Long-press already means "burn", so manual reordering uses this grip instead.
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = shadowPad)
                        .size(44.dp)
                        .then(dragHandle),
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    repeat(3) { Box(Modifier.width(16.dp).height(3.dp).background(Color(0xFF140A1C).copy(alpha = 0.55f))) }
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
}

// ---------------------------------------------------------------------------------------------
// Tasks: purple candles
// ---------------------------------------------------------------------------------------------

@Composable
fun TasksScreen(
    tasks: List<Task>?,
    snuffed: List<Task>,
    listState: LazyListState,
    onComplete: (Long) -> Unit,
    onRelight: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val time = rememberRotTime()
    val pxSize = rememberRotPxSize()
    val completing = remember { mutableStateMapOf<Long, Float>() }
    val fired = remember { HashSet<Long>() }
    val latestComplete by rememberUpdatedState(onComplete)
    val scope = rememberCoroutineScope()
    val candles = remember { CandleRenderer() }
    val relighting = remember { mutableStateMapOf<Long, Float>() }
    val relit = remember { HashSet<Long>() }
    val latestRelight by rememberUpdatedState(onRelight)
    var open by rememberSaveable { mutableStateOf(false) }
    val vm: TasksViewModel = viewModel()
    val newestFirst by vm.newestFirst.collectAsState()

    // Leaving the tab mid-animation must not lose the completion.
    DisposableEffect(Unit) {
        onDispose {
            completing.keys.toList().forEach { if (fired.add(it)) latestComplete(it) }
            relighting.keys.toList().forEach { if (relit.add(it)) latestRelight(it) }
        }
    }

    // Forget rows the database has moved, so a relit task can be snuffed (and relit) again.
    LaunchedEffect(tasks, snuffed) {
        if (tasks == null) return@LaunchedEffect
        completing.keys.removeAll { id -> tasks.none { it.id == id } }
        fired.retainAll(completing.keys)
        relighting.keys.removeAll { id -> snuffed.none { it.id == id } }
        relit.retainAll(relighting.keys)
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

    val beginRelight: (Long) -> Unit = { id ->
        if (!relighting.containsKey(id)) {
            relighting[id] = time.floatValue
            scope.launch {
                delay(600)
                if (relit.add(id)) latestRelight(id)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                tasks == null -> ScreenTitle("Flames")
                tasks.isEmpty() && snuffed.isEmpty() -> {
                    ScreenTitle("Flames")
                    EmptyState("No quests remain.", hint = "Tap the plus to add one.")
                }
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
                            // Keys: lit task = id, snuffed task = -id, plus the toggle (0) and the empty-state item.
                            if (id > 0L) {
                                val start = completing[id]
                                candles.row(id, ly, w, t, if (start == null) -1f else t - start)
                            } else if (id < 0L && id != Long.MIN_VALUE) {
                                val start = relighting[-id]
                                candles.stub(-id, ly, w, t, if (start == null) -1f else t - start)
                            }
                        }
                        drawIntoCanvas { candles.pix.blit(it.nativeCanvas, 0, 0, pxSize.toInt()) }
                    }
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // String key: the candle canvas only reads Long keys, so it skips the title.
                        item(key = "title") {
                            Box(Modifier.padding(bottom = 6.dp)) {
                                TitleWithSort("Flames", if (newestFirst) "newest first" else "oldest first", vm::toggleOrder)
                            }
                        }
                        if (tasks.isEmpty()) item(key = Long.MIN_VALUE) {
                            Box(Modifier.fillMaxWidth().height(380.dp)) { EmptyState("No quests remain.", hint = "Tap the plus to add one.") }
                        }
                        items(tasks, key = { it.id }) { task ->
                            TaskRow(
                                task = task,
                                completing = completing,
                                time = time,
                                modifier = Modifier.animateItem(placementSpec = null, fadeInSpec = tween(200), fadeOutSpec = tween(160)),
                                onTap = { begin(task.id) },
                            )
                        }
                        if (snuffed.isNotEmpty()) {
                            item(key = 0L) { SnuffedToggle(open, time, pxSize) { open = !open } }
                            if (open) items(snuffed, key = { -it.id }) { task ->
                                SnuffedRow(
                                    task = task,
                                    relighting = relighting,
                                    modifier = Modifier.animateItem(placementSpec = null, fadeInSpec = tween(200), fadeOutSpec = tween(160)),
                                    onTap = { beginRelight(task.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
        SigilFab("Add flame", onAdd, Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp))
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
                    onClickLabel = "Complete and remove flame",
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

/** Opens and closes the ash ledge. The sigil is a slowly turning dotted ring around a dusky ember (not a plus). */
@Composable
private fun SnuffedToggle(open: Boolean, time: FloatState, pxSize: Float, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = if (open) "hide snuffed flames" else "show snuffed flames", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(70.dp).height(44.dp)) {
            val a0 = time.floatValue * 0.4f
            val cx = 17
            val cy = (size.height / 2f / pxSize).roundToInt()
            for (k in 0 until 10) {
                val a = a0 + k * (2f * PI.toFloat() / 10f)
                val x = cx + (cos(a) * 4.5f).roundToInt()
                val y = cy + (sin(a) * 4.5f).roundToInt()
                drawRect(Px.Primary, Offset(x * pxSize, y * pxSize), Size(pxSize, pxSize))
            }
            drawRect(Color(0xFF5A2A48), Offset(cx * pxSize, cy * pxSize), Size(pxSize, pxSize))
        }
        Text(
            "snuffed",
            modifier = Modifier.padding(end = 18.dp),
            style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.TextDim),
        )
    }
}

@Composable
private fun SnuffedRow(
    task: Task,
    relighting: Map<Long, Float>,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val id = task.id
    Row(modifier.fillMaxWidth().heightIn(min = 54.dp).height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(70.dp)
                .fillMaxHeight()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Relight flame",
                    onClick = {
                        if (!relighting.containsKey(id)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTap()
                        }
                    },
                ),
        )
        Column(Modifier.weight(1f).padding(top = 12.dp, bottom = 16.dp, end = 18.dp)) {
            Text(task.text, style = TextStyle(fontFamily = PageFont, fontSize = 19.sp, lineHeight = 21.sp, color = Color(0xFF6A4A9A).copy(alpha = 0.45f)))
        }
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
                label = "Scrolls",
                active = selected == 0,
                onClick = { onSelect(0) },
                modifier = Modifier.weight(1f),
            )
            NavTab(
                icon = R.drawable.scroll_tab,
                label = "Flames",
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
            NavTab(
                icon = 0,
                grid = ShrineNavGrid,
                activePalette = ShrineNavActive,
                inactivePalette = ShrineNavInactive,
                label = "Grimoire",
                active = selected == 3,
                onClick = { onSelect(3) },
                modifier = Modifier.weight(1f),
            )
            NavTab(
                icon = 0,
                grid = MoonsNavGrid,
                activePalette = MoonsNavActive,
                inactivePalette = MoonsNavInactive,
                label = "Moons",
                active = selected == 4,
                onClick = { onSelect(4) },
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
    activePalette: Map<Char, Color>? = null,
    inactivePalette: Map<Char, Color>? = null,
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
                    grid,
                    if (activePalette != null && inactivePalette != null) {
                        if (active) activePalette else inactivePalette
                    } else mapOf('X' to Px.Magenta, 'o' to Px.Lavender),
                    if (activePalette != null) 2.dp else 3.dp,
                    Modifier.graphicsLayer {
                        val k = 0.84f + 0.16f * navScale
                        scaleX = k
                        scaleY = k
                        alpha = if (active || activePalette != null) 1f else 0.38f
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
                fontSize = 8.sp,
                letterSpacing = 0.5.sp,
                color = if (active) Px.Lavender else Px.TextDim.copy(alpha = 0.8f),
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            ),
        )
    }
}

