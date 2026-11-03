package app.vesper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.PI
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vesper.ShrineMedia
import app.vesper.data.Post
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The Shrine tab: a private, chronological feed of burned-paper posts.

// ---- palette (from the approved mock) ----
private val ShrineBase = Color(0xFF08040C)
private val ShrineText = Color(0xFFEDE4F7)
private val ShrineDim = Color(0xFF6F6080)
private val ShrineTime = Color(0xFF7A6A8C)
private val ShrineDayInk = Color(0xFF9A84C8)
private val ShrineDayBg = Color(0xD91A0F22)
private val ShrineBarBg = Color(0xFF0E0814)
private val ShrineBarLine = Color(0xFF241532)
private val ShrineBtnBg = Color(0xFF160D1F)
private val ShrineBtnLine = Color(0xFF2A1A38)
private val ShrineMenuLine = Color(0xFF3A2450)
private val ShrineIconDim = Color(0xFF8A7A9A)
private val ShrineDelete = Color(0xFFE07AB0)

private val ATT = listOf(
    ".XXXXXXXX.", "X........X", "X.XX.....X", "X.XX.....X", "X......X.X",
    "X.....XXXX", "X..X.XXXXX", "X.XXXXXXXX", ".XXXXXXXX.", "..........",
)
private val SEND = listOf(
    "X.........", "XXX.......", "XXXXX.....", "XXXXXXX...", "XXXXXXXXX.",
    "XXXXXXX...", "XXXXX.....", "XXX.......", "X.........", "..........",
)
private val CROSS = listOf("X...X", ".X.X.", "..X..", ".X.X.", "X...X")

// ---------------------------------------------------------------------------------------------
// Feed model
// ---------------------------------------------------------------------------------------------

private sealed interface FeedItem { val key: String }
private class DayItem(val label: String, day: LocalDate) : FeedItem { override val key = "d$day" }
private class PostItem(val post: Post) : FeedItem { override val key = "p${post.id}" }

private val zone: ZoneId get() = ZoneId.systemDefault()
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val dayFmt = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
private val dayYearFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now(zone)
    return when {
        d.year == today.year -> d.format(dayFmt)
        else -> d.format(dayYearFmt)
    }
}

private fun timeOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(timeFmt)

/** Newest first (the list is laid out in reverse so it opens at the bottom). */
private fun buildFeed(posts: List<Post>): List<FeedItem> {
    val out = ArrayList<FeedItem>(posts.size * 2)
    var prev: LocalDate? = null
    for (p in posts) {
        val d = dayOf(p.createdAt)
        if (d != prev) out.add(DayItem(dayLabel(d), d))
        out.add(PostItem(p))
        prev = d
    }
    out.reverse()
    return out
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

@Composable
fun ShrineScreen(
    posts: List<Post>?,
    listState: LazyListState,
    draft: String,
    draftPhoto: String?,
    sending: Boolean,
    onDraft: (String) -> Unit,
    onDraftPhoto: (String?) -> Unit,
    onSend: () -> Unit,
    onEdit: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    media: ShrineMedia,
) {
    val time = rememberRotTime()
    val pxSize = rememberRotPxSize()
    val density = LocalDensity.current
    val feed = remember(posts) { if (posts == null) emptyList() else buildFeed(posts) }

    var menuFor by remember { mutableStateOf<MenuTarget?>(null) }
    var writing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Post?>(null) }
    var deleting by remember { mutableStateOf<Post?>(null) }
    var viewing by remember { mutableStateOf<Post?>(null) }

    // A freshly kept post scrolls into view. The first emission (or re-entering the tab) must not.
    val newestId = posts?.lastOrNull()?.id
    val lastSeen = remember { longArrayOf(Long.MIN_VALUE) }
    LaunchedEffect(posts) {
        if (posts != null) {
            val cur = newestId ?: 0L
            if (lastSeen[0] != Long.MIN_VALUE && cur != lastSeen[0]) listState.animateScrollToItem(0)
            lastSeen[0] = cur
        }
    }

    Box(Modifier.fillMaxSize()) {
        ShrineBackdrop(time, pxSize)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val innerWidthPx = with(density) { (maxWidth - 32.dp).roundToPx() }
            Column(Modifier.fillMaxSize()) {
                ShrineHeader()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 96.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(feed, key = { it.key }) { item ->
                            when (item) {
                                is DayItem -> DayPill(item.label)
                                is PostItem -> PostCard(
                                    post = item.post,
                                    time = time,
                                    pxSize = pxSize,
                                    imageWidthPx = innerWidthPx,
                                    onMenu = { menuFor = MenuTarget(item.post, it) },
                                    onPhoto = { viewing = item.post },
                                    media = media,
                                )
                            }
                        }
                    }
                    if (posts != null && posts.isEmpty()) {
                        Text(
                            "nothing kept yet.",
                            modifier = Modifier.align(Alignment.Center),
                            style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = ShrineDim),
                        )
                    }
                    SigilFab("Keep something", { writing = true }, Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp), quill = true)
                }
            }
        }
    }
    menuFor?.let { t ->
        val clipboard = LocalClipboardManager.current
        ArcMenu(
            t.rect,
            hasText = !t.post.text.isNullOrEmpty(),
            onDismiss = { menuFor = null },
            onEdit = { menuFor = null; editing = t.post },
            onCopy = { clipboard.setText(AnnotatedString(t.post.text.orEmpty())); menuFor = null },
            onDelete = { menuFor = null; deleting = t.post },
        )
    }

    if (writing) {
        WritingPage(draft, draftPhoto, sending, onDraft, onDraftPhoto, onSend, media, onClose = { writing = false })
    }

    editing?.let { post ->
        EditDialog(post, onDismiss = { editing = null }) { text ->
            editing = null
            onEdit(post.id, text)
        }
    }

    deleting?.let { post ->
        PixelDialog(onDismiss = { deleting = null }) {
            Text("DELETE POST?", style = TextStyle(fontFamily = PageFont, fontSize = 24.sp, color = Px.Lavender))
            Spacer(Modifier.height(8.dp))
            Text("Gone for good. No undo.", style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = Px.TextDim))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PixelButton("KEEP", { deleting = null })
                PixelButton("DELETE", {
                    deleting = null
                    onDelete(post.id)
                }, primary = true)
            }
        }
    }

    viewing?.let { post -> if (post.videoPath != null) VideoViewer(post, media) { viewing = null } else PhotoViewer(post, media) { viewing = null } }
}

@Composable
private fun ShrineHeader() {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            "Grimoire",
            modifier = Modifier.alignByBaseline(),
            style = TextStyle(
                fontFamily = TitleFont,
                fontSize = 42.sp,
                color = Px.Lavender,
                shadow = Shadow(Color(0xFF6E1F5A), Offset(3f, 3f), 0f),
            ),
        )
    }
}

@Composable
private fun DayPill(label: String) {
    Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ShrineDayBg)
                .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp),
            style = TextStyle(fontFamily = TitleFont, fontSize = 20.sp, color = ShrineDayInk),
        )
    }
}

private class MenuTarget(val post: Post, val rect: Rect)

private val PEN = listOf("......XX", ".....XXX", "....XXX.", "...XXX..", "..XXX...", ".MXX....", ".MM.....", "M.......")
private val PAGES = listOf("XXXXX...", "X...X...", "X..XXXXX", "X..X...X", "X..X...X", "XXXX...X", "...X...X", "...XXXXX")
private val FLAME = listOf("...X....", "..XX....", ".XXXX...", ".XXXXX..", "XXXoXXX.", "XXooXXX.", "XXooXXX.", ".XXXXX..", "..XXX...")

/** Three small round buttons in a smile-shaped arc below the post (above it when there is no room). */
@Composable
private fun ArcMenu(
    rect: Rect,
    hasText: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    val provider = remember(rect, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (rect.center.x - popupContentSize.width / 2f).toInt().coerceIn(0, max(0, windowSize.width - popupContentSize.width))
                val below = rect.bottom.toInt() + gap
                val y = if (below + popupContentSize.height <= windowSize.height) below else rect.top.toInt() - popupContentSize.height - gap
                return IntOffset(x, y.coerceIn(0, max(0, windowSize.height - popupContentSize.height)))
            }
        }
    }
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(150)) }
    val items = buildList {
        add(Triple("Edit post", PEN, onEdit))
        if (hasText) add(Triple("Copy text", PAGES, onCopy))
        add(Triple("Delete post", FLAME, onDelete))
    }
    Popup(provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.size(width = 132.dp, height = 50.dp).graphicsLayer { alpha = a.value; scaleX = 0.6f + 0.4f * a.value; scaleY = 0.6f + 0.4f * a.value }) {
            items.forEachIndexed { i, (label, grid, act) ->
                val u = i - (items.size - 1) / 2f
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(x = (u * 46).dp, y = (10 * (1 - u * u)).dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ShrineBtnBg)
                        .border(2.dp, Color(0xFF5A3E7A), CircleShape)
                        .clickable(role = Role.Button, onClick = act)
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    PixelArt(grid, if (grid === FLAME) mapOf('X' to Px.Magenta, 'o' to Px.Lavender) else mapOf('X' to Px.Lavender, 'M' to Px.Magenta), 2.dp)
                }
            }
        }
    }
}

/** Full-screen writing page: back, attach and keep on top; the attachment chip; then the open text area. */
@Composable
private fun WritingPage(
    draft: String,
    photo: String?,
    sending: Boolean,
    onDraft: (String) -> Unit,
    onPhoto: (String?) -> Unit,
    onSend: () -> Unit,
    media: ShrineMedia,
    onClose: () -> Unit,
) {
    val pick = media.rememberPicker { onPhoto(it) }
    val canSend = !sending && (draft.isNotBlank() || photo != null)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(
        onDismissRequest = onClose,
        properties = edgeToEdgeDialog(),
    ) {
        Column(Modifier.fillMaxSize().background(ShrineBase).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SigilFab("Back", onClose, back = true)
                Spacer(Modifier.weight(1f))
                BarButton(
                    ATT, if (photo != null) Px.Magenta else ShrineIconDim,
                    fill = ShrineBtnBg, line = ShrineBtnLine, description = "Attach photo or video",
                ) { pick() }
                Spacer(Modifier.width(8.dp))
                BarButton(
                    SEND, Color(0xFFF0E8FA).copy(alpha = if (canSend) 1f else 0.45f),
                    fill = Color(0xFF5A2F94), line = Color(0xFF8A6AD0), description = "Keep post",
                ) { if (canSend) { onSend(); onClose() } }
            }
            if (photo != null) {
                val thumb by produceState<ImageBitmap?>(null, photo) { value = media.thumb(photo, 48) }
                Row(
                    Modifier
                        .padding(start = 18.dp, top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1C1128))
                        .border(2.dp, Px.Magenta, RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClickLabel = "Remove attachment") { onPhoto(null) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    thumb?.let {
                        Image(it, null, Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (media.isVideo(photo)) "video attached" else "photo attached", style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, color = ShrineText))
                    Spacer(Modifier.width(8.dp))
                    PixelIcon(CROSS, Px.Magenta, pixel = 2.dp)
                }
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                textStyle = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = ShrineText),
                cursorBrush = SolidColor(Px.Lavender),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize()) {
                        if (draft.isEmpty()) Text("keep something…", style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = ShrineDim))
                        inner()
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Post card
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostCard(
    post: Post,
    time: FloatState,
    pxSize: Float,
    imageWidthPx: Int,
    onMenu: (Rect) -> Unit,
    onPhoto: () -> Unit,
    media: ShrineMedia,
) {
    val px = pxSize.toInt().coerceAtLeast(1)
    val shadowOffset = with(LocalDensity.current) { 3.dp.toPx() }
    val paints = remember { PaperPaints() }
    val seed = post.id.toInt()
    val img = post.imagePath ?: post.videoPath
    val hasText = !post.text.isNullOrEmpty()
    val timeText = timeOf(post.createdAt) + if (post.editedAt != null) " · edited" else ""
    val where = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val openMenu = { where[0]?.takeIf { it.isAttached }?.let { onMenu(it.boundsInWindow()) }; Unit }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .onGloballyPositioned { where[0] = it }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}, onDoubleClick = openMenu),
    ) {
        // Paper + shadow + embers, behind the content. Time is only read in the draw phase.
        Spacer(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val w = ceil(size.width / px).toInt().coerceAtLeast(2)
                    val h = ceil(size.height / px).toInt().coerceAtLeast(2)
                    val paper = BurnedPaper.get(seed, w, h)
                    onDrawBehind {
                        drawIntoCanvas { paints.drawBehind(it, paper, w, h, px, shadowOffset) }
                    }
                },
        )
        // Content (photo + text) is scorched and cut by the same burnt shape as the paper; embers glow on top.
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val w = ceil(size.width / px).toInt().coerceAtLeast(2)
                    val h = ceil(size.height / px).toInt().coerceAtLeast(2)
                    val paper = BurnedPaper.get(seed, w, h)
                    onDrawWithContent {
                        drawContent()
                        drawIntoCanvas { paints.drawFront(it, paper, w, h, px, time.floatValue) }
                    }
                },
        ) {
            if (img != null) {
                Box(Modifier.fillMaxWidth()) {
                    PostPhoto(img, post.videoPath != null, imageWidthPx, media, onPhoto, openMenu)
                    if (!hasText) {
                        Text(
                            timeText,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(14.dp)
                                .background(Color(0xA607030B), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            style = TextStyle(fontFamily = PageFont, fontSize = 16.sp, color = ShrineText),
                        )
                    }
                }
            }
            if (hasText || img == null) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = if (img != null) 12.dp else 16.dp, bottom = 6.dp)) {
                    if (hasText) {
                        Text(
                            post.text.orEmpty(),
                            style = TextStyle(fontFamily = PageFont, fontSize = 22.sp, lineHeight = 26.sp, color = ShrineText),
                        )
                    }
                    Text(
                        timeText,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                        style = TextStyle(fontFamily = PageFont, fontSize = 16.sp, color = ShrineTime),
                    )
                }
            }
        }
    }
}

private const val EMBER_LO = 0xFF3A1430.toInt()
private const val EMBER_MID = 0xFF5A1E48.toInt()
private const val EMBER_HI = 0xFF7A2A5E.toInt()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostPhoto(name: String, video: Boolean, widthPx: Int, media: ShrineMedia, onTap: () -> Unit, onLong: () -> Unit) {
    val bmp by produceState(media.cached(name, widthPx), name, widthPx) {
        if (value == null) value = media.load(name, widthPx, cache = true, video = video)
    }
    val b = bmp
    // Full card width at the photo's natural aspect; very tall photos stop at 1.4 x width and are fitted (never cropped).
    val aspect = if (b != null) b.width.toFloat() / b.height else 4f / 3f
    val mod = Modifier
        .fillMaxWidth()
        .aspectRatio(max(aspect, 1f / 1.4f))
        .background(Color(0xFF1B1221))
        .combinedClickable(onClick = onTap, onDoubleClick = onLong)
    if (b != null) {
        Box(mod, contentAlignment = Alignment.Center) {
            Image(b, if (video) "video" else "photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (video) PlayMarker()
        }
    } else {
        Box(mod)
    }
}

@Composable
private fun PlayMarker() {
    Canvas(Modifier.size(56.dp)) {
        drawCircle(Color(0xA607030B))
        val s = size.minDimension
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(s * 0.40f, s * 0.30f); lineTo(s * 0.72f, s * 0.50f); lineTo(s * 0.40f, s * 0.70f); close()
            },
            ShrineText,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Burned paper (low-res bitmap per post id + size, cached)
// ---------------------------------------------------------------------------------------------

/** The four paints that cut and shade a post card's paper; split out of PostCard so the compositing is testable. */
internal class PaperPaints {
    private fun paint() = hardPaint().apply { filterQuality = FilterQuality.None }
    private val shadow = paint().apply { colorFilter = ColorFilter.tint(Color(0xFF040206.toInt()), BlendMode.SrcIn) }
    private val paper = paint()
    private val ember = paint()
    private val mask = paint().apply { blendMode = BlendMode.DstIn }

    private fun Canvas.stretch(img: ImageBitmap, w: Int, h: Int, px: Int, p: Paint) =
        drawImageRect(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(w * px, h * px), p)

    /** Drop shadow then the paper itself. */
    fun drawBehind(cv: Canvas, p: Paper, w: Int, h: Int, px: Int, shadowOffset: Float) {
        cv.save()
        cv.translate(shadowOffset, shadowOffset)
        cv.stretch(p.bmp, w, h, px, shadow)
        cv.restore()
        cv.stretch(p.bmp, w, h, px, paper)
    }

    /** Over the card content (in an offscreen layer): scorch, cut the burnt shape, then glowing embers. */
    fun drawFront(cv: Canvas, p: Paper, w: Int, h: Int, px: Int, t: Float) {
        cv.stretch(p.scorch, w, h, px, paper)
        cv.stretch(p.mask, w, h, px, mask)
        for (i in p.ex.indices) {
            val f = max(0f, sin(t * (0.6f + p.eh2[i] * 1.2f) + p.eh2[i] * 40f))
            if (f < 0.35f) continue
            ember.color = Color(if (f > 0.9f) EMBER_HI else if (f > 0.65f) EMBER_MID else EMBER_LO)
            val x = (p.ex[i] * px).toFloat()
            val y = (p.ey[i] * px).toFloat()
            cv.drawRect(x, y, x + px, y + px, ember)
        }
    }
}

internal class Paper(val bmp: ImageBitmap, val scorch: ImageBitmap, val mask: ImageBitmap, val ex: IntArray, val ey: IntArray, val eh2: FloatArray)

internal object BurnedPaper {
    private const val BURN = 0.87f
    private const val EMB = 1.0f
    private const val R = 7f
    private val cache = object : LinkedHashMap<String, Paper>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Paper>) = size > 160
    }

    private fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 982451653
        h = (h xor (h ushr 13)) * 1274126177
        return (((h xor (h ushr 16)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0).toFloat()
    }

    fun get(seed: Int, w: Int, h: Int): Paper {
        val key = "$seed:$w:$h"
        synchronized(cache) { cache[key] }?.let { return it }
        return render(seed, w, h).also { synchronized(cache) { cache[key] = it } }
    }

    fun render(seed: Int, w: Int, h: Int): Paper {
        val pixels = IntArray(w * h)
        val scorch = IntArray(w * h)
        val mask = IntArray(w * h)
        val s1 = seed * 0.37f
        val s2 = seed * 1.13f
        val s3 = seed * 2.71f
        val thr = 1f - 0.22f * EMB
        val ex = ArrayList<Int>(); val ey = ArrayList<Int>(); val eh = ArrayList<Float>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val qx = abs(x + 0.5f - w / 2f) - (w / 2f - R)
                val qy = abs(y + 0.5f - h / 2f) - (h / 2f - R)
                val out = hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - R
                val dd = -out
                val corner = if (qx > -4f && qy > -4f) 1.2f else 0f
                val n = max(
                    0f,
                    (1.2f + 0.9f * sin(x * 0.45f + s1) + 0.8f * sin(y * 0.5f + s2) + 0.6f * sin((x + y) * 0.9f + s3) + corner) * BURN,
                )
                if (dd < n) continue // burnt away
                mask[y * w + x] = -1
                if (dd < n + 1f) {
                    pixels[y * w + x] = 0xFF000000.toInt() or ((46 shl 16) or (22 shl 8) or 64)
                    scorch[y * w + x] = pixels[y * w + x]
                    val hh = hash(x, y, seed)
                    if (hh >= thr) { ex.add(x); ey.add(y); eh.add(hash(y, x, seed)) }
                    continue
                }
                val k = min(1f, (dd - n - 1f) / (2f + 5f * BURN))
                val kk = k * k * (3f - 2f * k)
                scorch[y * w + x] = (((1f - kk) * 255f).toInt().coerceIn(0, 255) shl 24) or (26 shl 16) or (13 shl 8) or 36
                var r = (26 + (46 - 26) * kk).toInt()
                var g = (13 + (22 - 13) * kk).toInt()
                var b = (36 + (64 - 36) * kk).toInt()
                val d = if (hash(x shr 1, y, seed + 7) < 0.06f) -4 else if (hash(x, y shr 1, seed + 3) < 0.04f) 5 else 0
                r = (r + d).coerceIn(0, 255); g = (g + d).coerceIn(0, 255); b = (b + d).coerceIn(0, 255)
                pixels[y * w + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }
        return Paper(imageBitmapOf(pixels, w, h), imageBitmapOf(scorch, w, h), imageBitmapOf(mask, w, h), ex.toIntArray(), ey.toIntArray(), eh.toFloatArray())
    }
}

// ---------------------------------------------------------------------------------------------
// Photos
// ---------------------------------------------------------------------------------------------

@Composable
private fun VideoViewer(post: Post, media: ShrineMedia, onClose: () -> Unit) {
    media.VideoPlayer(post.videoPath ?: run { onClose(); return }, onClose)
}

@Composable
private fun PhotoViewer(post: Post, media: ShrineMedia, onClose: () -> Unit) {
    val screenWidth = LocalWindowInfo.current.containerSize.width
    val name = post.imagePath ?: run { onClose(); return }
    val bmp by produceState<ImageBitmap?>(null, name) {
        value = media.load(name, minOf(2048, screenWidth * 2), cache = false, video = false)
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onClose,
        properties = edgeToEdgeDialog(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xEB040206))
                .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    b, "photo",
                    Modifier
                        .padding(16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        },
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                dayLabel(dayOf(post.createdAt)) + ", " + timeOf(post.createdAt),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
                style = TextStyle(fontFamily = PageFont, fontSize = 18.sp, color = ShrineIconDim),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Writing page parts
// ---------------------------------------------------------------------------------------------

@Composable
private fun BarButton(grid: List<String>, tint: Color, fill: Color, line: Color, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .background(fill)
            .border(2.dp, line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        PixelIcon(grid, tint, pixel = 2.dp)
    }
}

@Composable
private fun EditDialog(post: Post, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(post.text.orEmpty()) }
    PixelDialog(onDismiss) {
        Text("EDIT POST", style = TextStyle(fontFamily = PageFont, fontSize = 24.sp, color = Px.Lavender))
        Spacer(Modifier.height(10.dp))
        val shape = RoundedCornerShape(10.dp)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontFamily = PageFont, fontSize = 22.sp, color = ShrineText),
            cursorBrush = SolidColor(Px.Lavender),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp, max = 260.dp)
                .clip(shape)
                .background(ShrineBase)
                .border(2.dp, Color(0xFF5A3E7A), shape)
                .padding(12.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            PixelButton("CANCEL", onDismiss)
            PixelButton("SAVE", { onSave(text) }, primary = true)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Background (specks, fading runes, vignette)
// ---------------------------------------------------------------------------------------------

private val SPECKS = intArrayOf(0x160818, 0x2A0F30, 0x1E0A24)

@Composable
private fun ShrineBackdrop(time: FloatState, pxSize: Float) {
    val pix = remember { Pix(1, 1) }
    val px = pxSize.toInt().coerceAtLeast(1)
    Canvas(Modifier.fillMaxSize()) {
        val t = time.floatValue
        val w = ceil(size.width / px).toInt()
        val h = ceil(size.height / px).toInt()
        if (w <= 0 || h <= 0) return@Canvas
        pix.resize(w, h)
        pix.fill(0x08040C)
        for (i in 0 until 120) {
            pix.dot((hf(i, 11) * w).toInt(), (hf(i, 29) * h).toInt(), 1, 1, SPECKS[i % 3])
        }
        for (i in 0 until 14) {
            val rune = RUNES[i % RUNES.size]
            val period = 3f + 4f * hf(i, 17)
            val a = 0.5f * (0.5f + 0.5f * sin(t * (2f * PI.toFloat() / period) + hf(i, 5) * 6.2832f))
            val rx = (hf(i, 51) * (w - 6)).toInt()
            val ry = (hf(i, 73) * (h - 8)).toInt()
            for (y in 0 until 7) for (x in 0 until 5) if (rune[y][x] == 'X') pix.dot(rx + x, ry + y, 1, 1, 0x5A3E7A, a)
        }
        drawIntoCanvas { pix.blit(it, 0, 0, px) }
        val unit = size.width / 180f
        drawRect(
            Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    80f / 260f to Color.Transparent,
                    1f to Color(3, 1, 5).copy(alpha = 0.7f),
                ),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = 260f * unit,
            ),
        )
    }
}

// Elder-Futhark-ish 5x7 rune bitmaps for the background.
private val RUNES = listOf(
    listOf("X....", "XX...", "X.X..", "X..X.", "X.X..", "X....", "X...."),
    listOf("XXX..", "XX.X.", "X..X.", "X..X.", "X..X.", "X..X.", "X..X."),
    listOf("X....", "XX...", "X.X..", "X..X.", "X.X..", "XX...", "X...."),
    listOf("X....", "XX...", "X.X..", "XX...", "X.X..", "X....", "X...."),
    listOf("XXX..", "X..X.", "X..X.", "XXX..", "X.X..", "X..X.", "X...X"),
    listOf("...X.", "..X..", ".X...", "X....", ".X...", "..X..", "...X."),
    listOf("..X..", "..X..", "..X..", "..X..", "..X..", "..X..", "..X.."),
    listOf(".X...", "X....", ".X...", "..X..", ".X...", "X....", ".X..."),
)
