package app.vesper.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import app.vesper.data.Post
import java.io.File
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

/** 14x14 nav icon for the Shrine tab. */
internal val ShrineNavGrid = listOf(
    "......X.......", ".....XMX......", "....XMHMX.....", "....XHHHX.....", ".....XHX......",
    "..XXXXXXXXX...", ".XPPPPPPPPPX..", ".XPCCCCCCCPX..", ".XPCVVVVVCPX..", ".XPCVCCCVCPX..",
    ".XPCVVVVVCPX..", ".XPCCCCCCCPX..", ".XPPPPPPPPPX..", "..XXXXXXXXX...",
)
internal val ShrineNavActive = mapOf(
    'X' to Color(0xFF100817), 'H' to Color(0xFFB69AFF), 'C' to Color(0xFF47296D),
    'M' to Color(0xFFD45AAB), 'P' to Color(0xFF7540B8), 'V' to Color(0xFFF0E8FA),
)
internal val ShrineNavInactive = mapOf(
    'X' to Color(0xFF100817), 'H' to Color(0xFF563C78), 'C' to Color(0xFF241432),
    'M' to Color(0xFF7A3668), 'P' to Color(0xFF39234C), 'V' to Color(0xFF8A7A9A),
)

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
        d == today -> "today"
        d == today.minusDays(1) -> "yesterday"
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
    draftPhoto: Uri?,
    sending: Boolean,
    onDraft: (String) -> Unit,
    onDraftPhoto: (Uri?) -> Unit,
    onSend: () -> Unit,
    onEdit: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val time = rememberRotTime()
    val pxSize = rememberRotPxSize()
    val density = LocalDensity.current
    val feed = remember(posts) { if (posts == null) emptyList() else buildFeed(posts) }

    var menuFor by remember { mutableStateOf<Post?>(null) }
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

    // The bottom nav bar stays under the keyboard, so only lift the screen by what the keyboard covers above it.
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navTotal = with(density) { 74.dp.roundToPx() } + WindowInsets.navigationBars.getBottom(density)
    val lift = with(density) { (imeBottom - navTotal).coerceAtLeast(0).toDp() }

    Box(Modifier.fillMaxSize()) {
        ShrineBackdrop(time, pxSize)
        BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = lift)) {
            val innerWidthPx = with(density) { (maxWidth - 32.dp).roundToPx() }
            Column(Modifier.fillMaxSize()) {
                ShrineHeader(posts?.size ?: 0)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 4.dp),
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
                                    onMenu = { menuFor = item.post },
                                    onPhoto = { viewing = item.post },
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
                }
                Composer(draft, draftPhoto, sending, onDraft, onDraftPhoto, onSend)
            }
        }
    }

    menuFor?.let { post ->
        val clipboard = LocalClipboardManager.current
        Dialog(onDismissRequest = { menuFor = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .width(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ShrineBtnBg)
                    .border(2.dp, ShrineMenuLine, RoundedCornerShape(10.dp))
                    .padding(vertical = 6.dp),
            ) {
                MenuRow("edit", ShrineText) { menuFor = null; editing = post }
                if (!post.text.isNullOrEmpty()) {
                    MenuRow("copy text", ShrineText) {
                        clipboard.setText(AnnotatedString(post.text))
                        menuFor = null
                    }
                }
                MenuRow("delete", ShrineDelete) { menuFor = null; deleting = post }
            }
        }
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

    viewing?.let { post -> PhotoViewer(post) { viewing = null } }
}

@Composable
private fun ShrineHeader(count: Int) {
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
        Spacer(Modifier.weight(1f))
        Text(
            "$count kept",
            modifier = Modifier.alignByBaseline(),
            style = TextStyle(fontFamily = PageFont, fontSize = 18.sp, color = ShrineDim),
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

@Composable
private fun MenuRow(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        style = TextStyle(fontFamily = PageFont, fontSize = 21.sp, color = color),
    )
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
    onMenu: () -> Unit,
    onPhoto: () -> Unit,
) {
    val px = pxSize.toInt().coerceAtLeast(1)
    val shadowOffset = with(LocalDensity.current) { 3.dp.toPx() }
    val shadowPaint = remember { Paint().apply { isFilterBitmap = false; colorFilter = PorterDuffColorFilter(0xFF040206.toInt(), PorterDuff.Mode.SRC_IN) } }
    val paperPaint = remember { Paint().apply { isFilterBitmap = false } }
    val emberPaint = remember { Paint().apply { isAntiAlias = false } }
    val maskPaint = remember { Paint().apply { isFilterBitmap = false; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) } }
    val seed = post.id.toInt()
    val img = post.imagePath
    val hasText = !post.text.isNullOrEmpty()
    val timeText = timeOf(post.createdAt) + if (post.editedAt != null) " · edited" else ""

    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = onMenu),
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
                        drawIntoCanvas { c ->
                            val cv = c.nativeCanvas
                            val dst = android.graphics.Rect(0, 0, w * px, h * px)
                            cv.save()
                            cv.translate(shadowOffset, shadowOffset)
                            cv.drawBitmap(paper.bmp, null, dst, shadowPaint)
                            cv.restore()
                            cv.drawBitmap(paper.bmp, null, dst, paperPaint)
                        }
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
                        drawIntoCanvas { c ->
                            val cv = c.nativeCanvas
                            val dst = android.graphics.Rect(0, 0, w * px, h * px)
                            cv.drawBitmap(paper.scorch, null, dst, paperPaint)
                            cv.drawBitmap(paper.mask, null, dst, maskPaint)
                            val t = time.floatValue
                            for (i in paper.ex.indices) {
                                val f = max(0f, sin(t * (0.6f + paper.eh2[i] * 1.2f) + paper.eh2[i] * 40f))
                                if (f < 0.35f) continue
                                emberPaint.color = if (f > 0.9f) EMBER_HI else if (f > 0.65f) EMBER_MID else EMBER_LO
                                val x = (paper.ex[i] * px).toFloat()
                                val y = (paper.ey[i] * px).toFloat()
                                cv.drawRect(x, y, x + px, y + px, emberPaint)
                            }
                        }
                    }
                },
        ) {
            if (img != null) {
                Box(Modifier.fillMaxWidth()) {
                    PostPhoto(img, imageWidthPx, onPhoto, onMenu)
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
private fun PostPhoto(name: String, widthPx: Int, onTap: () -> Unit, onLong: () -> Unit) {
    val dir = LocalContext.current.filesDir
    val bmp by produceState(ShrineBitmaps.cached(name, widthPx), name, widthPx) {
        if (value == null) value = ShrineBitmaps.load(File(File(dir, "shrine"), name), widthPx, cache = true)
    }
    val b = bmp
    // Full card width at the photo's natural aspect; very tall photos stop at 1.4 x width and are fitted (never cropped).
    val aspect = if (b != null) b.width.toFloat() / b.height else 4f / 3f
    val mod = Modifier
        .fillMaxWidth()
        .aspectRatio(max(aspect, 1f / 1.4f))
        .background(Color(0xFF1B1221))
        .combinedClickable(onClick = onTap, onLongClick = onLong)
    if (b != null) {
        Image(b.asImageBitmap(), "photo", mod, contentScale = ContentScale.Fit)
    } else {
        Box(mod)
    }
}

// ---------------------------------------------------------------------------------------------
// Burned paper (low-res bitmap per post id + size, cached)
// ---------------------------------------------------------------------------------------------

private class Paper(val bmp: Bitmap, val scorch: Bitmap, val mask: Bitmap, val ex: IntArray, val ey: IntArray, val eh2: FloatArray)

private object BurnedPaper {
    private const val BURN = 0.87f
    private const val EMB = 1.0f
    private const val R = 7f
    private val cache = LruCache<String, Paper>(160)

    private fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 982451653
        h = (h xor (h ushr 13)) * 1274126177
        return (((h xor (h ushr 16)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0).toFloat()
    }

    fun get(seed: Int, w: Int, h: Int): Paper {
        val key = "$seed:$w:$h"
        cache.get(key)?.let { return it }
        return render(seed, w, h).also { cache.put(key, it) }
    }

    private fun render(seed: Int, w: Int, h: Int): Paper {
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
                    pixels[y * w + x] = 0xFF000000.toInt() or ((48 shl 16) or (20 shl 8) or 38)
                    scorch[y * w + x] = pixels[y * w + x]
                    val hh = hash(x, y, seed)
                    if (hh >= thr) { ex.add(x); ey.add(y); eh.add(hash(y, x, seed)) }
                    continue
                }
                val k = min(1f, (dd - n - 1f) / (2f + 5f * BURN))
                val kk = k * k * (3f - 2f * k)
                scorch[y * w + x] = (((1f - kk) * 255f).toInt().coerceIn(0, 255) shl 24) or (10 shl 16) or (5 shl 8) or 8
                var r = (10 + (27 - 10) * kk).toInt()
                var g = (5 + (18 - 5) * kk).toInt()
                var b = (8 + (33 - 8) * kk).toInt()
                val d = if (hash(x shr 1, y, seed + 7) < 0.06f) -4 else if (hash(x, y shr 1, seed + 3) < 0.04f) 5 else 0
                r = (r + d).coerceIn(0, 255); g = (g + d).coerceIn(0, 255); b = (b + d).coerceIn(0, 255)
                pixels[y * w + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        val scorchBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        scorchBmp.setPixels(scorch, 0, w, 0, 0, w, h)
        val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        maskBmp.setPixels(mask, 0, w, 0, 0, w, h)
        return Paper(bmp, scorchBmp, maskBmp, ex.toIntArray(), ey.toIntArray(), eh.toFloatArray())
    }
}

// ---------------------------------------------------------------------------------------------
// Photos
// ---------------------------------------------------------------------------------------------

private object ShrineBitmaps {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(name: String, w: Int): Bitmap? = cache.get("$name@$w")

    /** Decodes [file] downsampled to roughly [widthPx] wide. Returns null if it can't be read. */
    suspend fun load(file: File, widthPx: Int, cache: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${file.name}@$widthPx"
        if (cache) this@ShrineBitmaps.cache.get(key)?.let { return@withContext it }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null && cache) this@ShrineBitmaps.cache.put(key, bmp)
            bmp
        } catch (e: Throwable) {
            null // OutOfMemory or a corrupt file must never take the app down
        }
    }

    /** Small thumbnail of a picked (content://) photo for the attachment chip. */
    suspend fun thumb(context: android.content.Context, uri: Uri, edge: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val r = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
            r.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Throwable) {
            null
        }
    }
}

@Composable
private fun PhotoViewer(post: Post, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val dm = ctx.resources.displayMetrics
    val name = post.imagePath ?: run { onClose(); return }
    val bmp by produceState<Bitmap?>(null, name) {
        value = ShrineBitmaps.load(File(File(ctx.filesDir, "shrine"), name), minOf(2048, dm.widthPixels * 2), cache = false)
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
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
                    b.asImageBitmap(), "photo",
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
// Composer
// ---------------------------------------------------------------------------------------------

@Composable
private fun Composer(
    draft: String,
    photo: Uri?,
    sending: Boolean,
    onDraft: (String) -> Unit,
    onPhoto: (Uri?) -> Unit,
    onSend: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onPhoto(uri) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ctx = LocalContext.current
    val canSend = !sending && (draft.isNotBlank() || photo != null)

    Column(
        Modifier
            .fillMaxWidth()
            .background(ShrineBarBg)
            .drawBehind { drawRect(ShrineBarLine, size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
    ) {
        if (photo != null) {
            val thumb by produceState<Bitmap?>(null, photo) { value = ShrineBitmaps.thumb(ctx, photo, 48) }
            Row(
                Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1C1128))
                    .border(2.dp, Px.Magenta, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = "Remove photo") { onPhoto(null) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                thumb?.let {
                    Image(
                        it.asImageBitmap(), null,
                        Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("photo attached", style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, color = ShrineText))
                Spacer(Modifier.width(8.dp))
                PixelIcon(CROSS, Px.Magenta, pixel = 2.dp)
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarButton(
                ATT, if (photo != null) Px.Magenta else ShrineIconDim,
                fill = ShrineBtnBg, line = ShrineBtnLine, description = "Attach photo",
            ) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            val shape = RoundedCornerShape(10.dp)
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                interactionSource = interaction,
                textStyle = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = ShrineText),
                cursorBrush = SolidColor(Px.Lavender),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .background(ShrineBase)
                    .border(2.dp, if (focused) Color(0xFF5A3E7A) else ShrineBtnLine, shape)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text("keep something...", style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, color = ShrineDim))
                        }
                        inner()
                    }
                },
            )
            BarButton(
                SEND, Color(0xFFF0E8FA).copy(alpha = if (canSend) 1f else 0.45f),
                fill = Color(0xFF5A2F94), line = Color(0xFF8A6AD0), description = "Keep post",
            ) { if (canSend) onSend() }
        }
    }
}

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
// Background (dark fog, specks, rising sparks, vignette)
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
        val sx = w / 180f
        val sy = h / 390f
        for (i in 0 until 3) {
            val cx = (90f + sin(t * 0.04f + i * 2.1f) * 60f) * sx
            val cy = (195f + cos(t * 0.03f + i * 1.4f) * 150f) * sy
            val rgb = if (i % 2 == 0) 0x34186E else 0x501860
            pix.glow(cx, cy, 90f * (sx + sy) / 2f, rgb, if (i % 2 == 0) 0.2f else 0.22f, add = false)
        }
        for (i in 0 until 120) {
            pix.dot((hf(i, 11) * w).toInt(), (hf(i, 29) * h).toInt(), 1, 1, SPECKS[i % 3])
        }
        for (i in 0 until 17) {
            val x = (i * 37 + 11) % w + sin(t * 0.6f + i) * 3f
            val rawY = h - (t * (2 + i % 3) + i * 53)
            val y = ((rawY % h) + h) % h
            val a = 0.15f + 0.25f * max(0f, sin(t * 1.2f + i))
            pix.dot(floor(x).toInt(), floor(y).toInt(), 1, 1, if (i % 2 == 0) 0x5A1E48 else 0x7A2A5E, a)
        }
        drawIntoCanvas { pix.blit(it.nativeCanvas, 0, 0, px) }
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
