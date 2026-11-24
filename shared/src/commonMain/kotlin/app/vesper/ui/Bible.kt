package app.vesper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.data.BIBLE_PERSON
import app.vesper.data.BIBLE_THING
import app.vesper.data.BibleCard
import app.vesper.data.BibleLink
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

// The Bible world behind the top-right brain: one free canvas of cards (people, things) joined by labelled lines.
// Everything here is in canvas dp, (0, 0) the canvas middle; the pure parts are tested in BibleTest.

// ---------------------------------------------------------------------------------------------
// Pure
// ---------------------------------------------------------------------------------------------

/** The canvas runs this far (dp) each way from its middle: much bigger than any screen, never endless. */
internal const val BIBLE_HALF = 1500f
internal const val BIBLE_MIN_ZOOM = 0.35f
internal const val BIBLE_MAX_ZOOM = 2.5f
internal const val BIBLE_NAME_MAX = 40
internal const val BIBLE_LABEL_MAX = 24
internal const val BIBLE_NOTE_MAX = 2000

internal enum class BibleMode { Move, Link, Lock }

/** A point kept on the canvas. */
internal fun onCanvas(p: Offset) = Offset(p.x.coerceIn(-BIBLE_HALF, BIBLE_HALF), p.y.coerceIn(-BIBLE_HALF, BIBLE_HALF))

/** A box (l, t, r, b) of [size] round [c]. */
internal fun boxAround(c: Offset, size: Size) = floatArrayOf(c.x - size.width / 2f, c.y - size.height / 2f, c.x + size.width / 2f, c.y + size.height / 2f)

/** The topmost (last drawn) of [boxes] under [p], or -1. */
internal fun topBoxAt(boxes: List<FloatArray>, p: Offset, slack: Float = 4f) =
    boxes.indexOfLast { p.x in it[0] - slack..it[2] + slack && p.y in it[1] - slack..it[3] + slack }

/** Only a free card in Move mode follows a drag; anything else pans the canvas. */
internal fun canDrag(mode: BibleMode, card: BibleCard) = mode == BibleMode.Move && !card.locked

/** A one-finger drag that starts on a locked card does nothing at all: no card move, and no pan that would carry the card along. */
internal fun dragHeld(card: BibleCard?) = card?.locked == true

/** What a tap does. */
internal sealed interface BibleTap {
    data class Create(val at: Offset) : BibleTap
    data class Select(val uid: String?) : BibleTap
    /** Link mode: the first card picked (null = none any more). */
    data class Pick(val uid: String?) : BibleTap
    data class Link(val from: String, val to: String) : BibleTap
    data class Lock(val uid: String) : BibleTap
    data class EditLink(val uid: String) : BibleTap
}

/**
 * A tap at [at] (canvas dp) on [card] (its uid; null = none) or else a link's label [link], in [mode], with [selected] the shown
 * card and [linkFrom] the card picked first in Link mode. A tap on empty space while a card is shown only hides it, so a tap meant to
 * close the info strip never makes a card. Null = nothing happens.
 */
internal fun bibleTap(mode: BibleMode, card: String?, link: String?, selected: String?, linkFrom: String?, at: Offset): BibleTap? = when (mode) {
    BibleMode.Move -> when {
        card != null -> BibleTap.Select(if (card == selected) null else card)
        link != null -> BibleTap.EditLink(link)
        selected != null -> BibleTap.Select(null)
        else -> BibleTap.Create(onCanvas(at))
    }
    BibleMode.Link -> when {
        card != null && linkFrom == null -> BibleTap.Pick(card)
        card != null && card == linkFrom -> BibleTap.Pick(null)
        card != null -> BibleTap.Link(linkFrom!!, card)
        link != null -> BibleTap.EditLink(link)
        else -> BibleTap.Pick(null)
    }
    BibleMode.Lock -> card?.let { BibleTap.Lock(it) }
}

private val VERBS = setOf("built", "made", "bore", "begat", "wrote", "led", "saw", "fought", "slew", "met", "took", "gave", "sent", "left", "found", "sold", "bought", "became", "knew", "won", "ruled", "freed", "fed", "taught", "anointed")
private val TAIL_WORDS = setOf("of", "by", "to", "with", "from", "for", "in", "on", "at", "into")

/** A label that reads as a deed ("built", "anointed") rather than a role ("son", "wife"). */
internal fun isDeed(label: String) = label.trim().lowercase().let { it.endsWith("ed") || it in VERBS }

/** How a link [label] reads from its own card: "son" -> "son of", "built" -> "built", "married to" stays. */
internal fun outWords(label: String): String {
    val l = label.trim()
    return if (isDeed(l) || l.substringAfterLast(' ').lowercase() in TAIL_WORDS) l else "$l of"
}

/** How a link [label] reads from the card it points at: "son" -> "has son", "built" -> "built by", "son of" -> "has son". */
internal fun inWords(label: String): String {
    val l = label.trim()
    val last = l.substringAfterLast(' ').lowercase()
    return when {
        isDeed(l) -> "$l by"
        last == "of" && ' ' in l -> "has ${l.substringBeforeLast(' ')}"
        last in TAIL_WORDS -> l
        else -> "has $l"
    }
}

/**
 * [card]'s links in plain words, its own first, then the ones pointing at it, each kind once with its names:
 * "Isaac: son of Abraham, Sarah · has son Jacob, Esau". A link to a card that is gone is left out.
 */
internal fun bibleWords(card: BibleCard, cards: List<BibleCard>, links: List<BibleLink>): String {
    val name = cards.associate { it.uid to it.name }
    val parts = LinkedHashMap<String, MutableList<String>>()
    for (l in links) if (l.fromUid == card.uid) name[l.toUid]?.let { parts.getOrPut(outWords(l.label)) { mutableListOf() }.add(it) }
    for (l in links) if (l.toUid == card.uid && l.fromUid != card.uid) name[l.fromUid]?.let { parts.getOrPut(inWords(l.label)) { mutableListOf() }.add(it) }
    val body = if (parts.isEmpty()) "no links yet" else parts.entries.joinToString(" · ") { (k, v) -> "$k ${v.distinct().joinToString(", ")}" }
    return "${card.name}: $body"
}

/** Where the camera looks first: the middle of every card, or the canvas middle. */
internal fun bibleHome(cards: List<BibleCard>): Offset =
    if (cards.isEmpty()) Offset.Zero else Offset((cards.minOf { it.x } + cards.maxOf { it.x }) / 2f, (cards.minOf { it.y } + cards.maxOf { it.y }) / 2f)

// ---------------------------------------------------------------------------------------------
// The world
// ---------------------------------------------------------------------------------------------

private val CardFill = Color(0xFF2A1450)
private val CardFillLit = Color(0xFF3D1F70)
private val Lilac = Color(0xFFB69AFF)
private val LineInk = Color(0xFF8F7BE0)
private val Ink = Color(0xFFE6DCFF)
private val Night = Color(0xFF07030B)
private const val CARD_PAD_X = 10f // dp
private const val CARD_PAD_Y = 8f
private const val CARD_MIN_W = 56f
private const val CARD_TEXT_W = 112f
private const val PILL_PAD = 4f

/** The tiny pixel lock on a locked card's corner. */
private val LOCK_ROWS = listOf(".XXX.", "X...X", "X...X", "XXXXX", "XX.XX", "XXXXX")

/** The Bible world: the canvas with its header (back, the name, the mode chips) and, for a shown card, the info strip at the bottom. */
@Composable
internal fun BibleWorld(
    cards: List<BibleCard>,
    links: List<BibleLink>,
    onBack: () -> Unit,
    onAdd: (String, Float, Float) -> Unit,
    onMove: (BibleCard, Float, Float) -> Unit,
    onLock: (BibleCard) -> Unit,
    onRename: (BibleCard, String) -> Unit,
    onKind: (BibleCard, String) -> Unit,
    onNote: (BibleCard, String) -> Unit,
    onDelete: (BibleCard) -> Unit,
    onLink: (String, String, String) -> Unit,
    onRenameLink: (BibleLink, String) -> Unit,
    onDeleteLink: (BibleLink) -> Unit,
) {
    var mode by remember { mutableStateOf(BibleMode.Move) }
    var selected by remember { mutableStateOf<String?>(null) }
    var linkFrom by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Offset?>(null) } // a new card waiting for its name
    var linking by remember { mutableStateOf<Pair<String, String>?>(null) } // waiting for its label
    var cardMenu by remember { mutableStateOf<Pair<BibleCard, Offset>?>(null) }
    var linkMenu by remember { mutableStateOf<Pair<BibleLink, Offset>?>(null) }
    var renaming by remember { mutableStateOf<BibleCard?>(null) }
    var noting by remember { mutableStateOf<BibleCard?>(null) }
    var burning by remember { mutableStateOf<BibleCard?>(null) }
    var relabeling by remember { mutableStateOf<BibleLink?>(null) }
    var menuClear by remember { mutableStateOf(Size.Zero) } // half the long-pressed card in dp: the chip ring stays outside it
    var dragging by remember { mutableStateOf<Pair<String, Offset>?>(null) } // a card following a finger, where it is now
    var dropped by remember { mutableStateOf<Pair<String, Offset>?>(null) } // shown there until the database says so
    LaunchedEffect(cards) { dropped = null }
    // a card or link deleted (here or by sync) is never left shown or picked
    LaunchedEffect(cards) {
        val uids = cards.mapTo(HashSet()) { it.uid }
        if (selected !in uids) selected = null
        if (linkFrom !in uids) linkFrom = null
    }

    val home = remember { bibleHome(cards) }
    var camX by remember { mutableFloatStateOf(home.x) }
    var camY by remember { mutableFloatStateOf(home.y) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var view by remember { mutableStateOf(IntSize.Zero) }

    BackHandler(true, onBack)
    BackHandler(selected != null || linkFrom != null) { selected = null; linkFrom = null }

    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val pixelFont = PixelFont; val pageFont = PageFont
    val names = remember(cards, density, pixelFont) {
        cards.map { c ->
            measurer.measure(c.name, TextStyle(fontFamily = pixelFont, fontSize = 8.sp, lineHeight = 13.sp, color = Color.White, textAlign = TextAlign.Center),
                maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (CARD_TEXT_W * density).toInt()))
        }
    }
    val labels = remember(links, density, pixelFont) {
        links.map { l -> measurer.measure(l.label, TextStyle(fontFamily = pixelFont, fontSize = 6.sp, color = Lilac), maxLines = 1) }
    }
    val sizes = names.map { Size(max(CARD_MIN_W, it.size.width / density + 2f * CARD_PAD_X), it.size.height / density + 2f * CARD_PAD_Y) }
    fun at(i: Int): Offset = cards[i].let { c -> (dragging ?: dropped)?.takeIf { it.first == c.uid }?.second ?: Offset(c.x, c.y) }
    val index = cards.withIndex().associate { (i, c) -> c.uid to i }
    val boxes = cards.indices.map { boxAround(at(it), sizes[it]) }
    val mids = links.map { l -> val a = index[l.fromUid]; val b = index[l.toUid]; if (a == null || b == null) null else (at(a) + at(b)) / 2f }
    val pills = links.indices.map { i -> mids[i]?.let { boxAround(it, Size(labels[i].size.width / density + 2f * PILL_PAD, labels[i].size.height / density + 2f * PILL_PAD)) } ?: floatArrayOf(0f, 0f, -1f, -1f) }

    // animations: stamped when cards or links change, then a frame loop runs only for the few hundred ms they last
    val fx = remember { BibleFx() }
    var clock by remember { mutableFloatStateOf(0f) }
    remember(cards, links) { fx.sync(cards, boxes, names, links) }
    LaunchedEffect(cards, links) {
        while (fx.now() < fx.endAt) withFrameNanos { clock = fx.now() }
        clock = fx.now(); fx.prune(clock)
    }

    fun toCanvas(p: Offset) = Offset((p.x - view.width / 2f) / (zoom * density) + camX, (p.y - view.height / 2f) / (zoom * density) + camY)
    fun toView(c: Offset) = Offset((c.x - camX) * zoom * density + view.width / 2f, (c.y - camY) * zoom * density + view.height / 2f)
    fun zoomAt(at: Offset, pan: Offset, k: Float) {
        val before = toCanvas(at)
        zoom = (zoom * k).coerceIn(BIBLE_MIN_ZOOM, BIBLE_MAX_ZOOM)
        val after = toCanvas(at)
        val c = onCanvas(Offset(camX + before.x - after.x - pan.x / (zoom * density), camY + before.y - after.y - pan.y / (zoom * density)))
        camX = c.x; camY = c.y
    }

    fun tap(p: Offset) {
        val w = toCanvas(p)
        val ci = topBoxAt(boxes, w)
        val li = if (ci >= 0) -1 else topBoxAt(pills, w, 2f)
        when (val t = bibleTap(mode, cards.getOrNull(ci)?.uid, links.getOrNull(li)?.uid, selected, linkFrom, w)) {
            is BibleTap.Create -> { pending = t.at }
            is BibleTap.Select -> selected = t.uid
            is BibleTap.Pick -> linkFrom = t.uid
            is BibleTap.Link -> { linking = t.from to t.to; linkFrom = null }
            is BibleTap.Lock -> { cards.firstOrNull { it.uid == t.uid }?.let(onLock) }
            is BibleTap.EditLink -> links.firstOrNull { it.uid == t.uid }?.let { linkMenu = it to p }
            null -> {}
        }
    }
    fun longPress(p: Offset) {
        if (mode != BibleMode.Move) return
        val ci = topBoxAt(boxes, toCanvas(p))
        if (ci >= 0) { menuClear = Size(sizes[ci].width / 2f * zoom, sizes[ci].height / 2f * zoom); cardMenu = cards[ci] to toView(at(ci)) }
    }

    val latest by rememberUpdatedState(Triple(boxes, cards, mode))
    val tapNow by rememberUpdatedState(::tap)
    val longNow by rememberUpdatedState(::longPress)
    val selectedCard = cards.firstOrNull { it.uid == selected }
    val count = if (cards.size == 1) "1 card" else "${cards.size} cards"

    Column(Modifier.fillMaxSize().background(Night)) {
        WorldHeader("bible", "", onBack) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                for (m in BibleMode.entries) ModeChip(m.name, m == mode) { mode = m; linkFrom = null; if (m != BibleMode.Move) selected = null }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Canvas(
                Modifier.fillMaxSize().onSizeChanged { view = it }
                    .semantics { contentDescription = "Bible canvas, $count" }
                    .pointerInput(Unit) {
                        // Mouse wheel zoom (desktop); touch screens never send this.
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type != PointerEventType.Scroll) continue
                                val c = e.changes.first()
                                zoomAt(c.position, Offset.Zero, exp(-c.scrollDelta.y * 0.18f)); c.consume()
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.position
                            // 1 a tap, 2 a drag, 3 a second finger; null = held still: a long press
                            val r: Int? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var out = 0
                                while (out == 0) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id }
                                    out = when {
                                        e.changes.count { it.pressed } > 1 -> 3
                                        c == null || !c.pressed -> 1
                                        (c.position - start).getDistance() > viewConfiguration.touchSlop -> 2
                                        else -> 0
                                    }
                                }
                                out
                            }
                            if (r == 1) { tapNow(start); return@awaitEachGesture }
                            if (r == null) {
                                longNow(start)
                                do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                                return@awaitEachGesture
                            }
                            val (bx, cs, m) = latest
                            val ci = if (r == 2) topBoxAt(bx, toCanvas(start)) else -1
                            val card = cs.getOrNull(ci)?.takeIf { canDrag(m, it) }
                            val held = dragHeld(cs.getOrNull(ci))
                            var pos = card?.let { Offset(it.x, it.y) }
                            while (true) {
                                val e = awaitPointerEvent()
                                val pressed = e.changes.count { it.pressed }
                                if (pressed == 0) break
                                if (pressed > 1 || card == null) {
                                    if (pressed > 1 || !held) zoomAt(e.calculateCentroid(useCurrent = true), e.calculatePan(), e.calculateZoom())
                                } else {
                                    pos = onCanvas(pos!! + e.calculatePan() / (zoom * density))
                                    dragging = card.uid to pos
                                }
                                e.changes.forEach { it.consume() }
                            }
                            if (card != null && pos != null) {
                                dropped = if (pos != Offset(card.x, card.y)) card.uid to pos else null; dragging = null
                                if (pos != Offset(card.x, card.y)) onMove(card, pos.x, pos.y)
                            }
                        }
                    },
            ) {
                val k = zoom * density
                val lit = selected ?: linkFrom
                fun touches(l: BibleLink, u: String?) = u != null && (l.fromUid == u || l.toUid == u)
                // text is laid out in px: drawn in view px, scaled only by the zoom round its middle
                val now = clock
                fun text(tl: TextLayoutResult, c: Offset, alpha: Float = 1f, grow: Float = 1f) {
                    val v = toView(c)
                    val zk = zoom * grow
                    withTransform({ scale(zk, zk, v) }) { drawText(tl, topLeft = Offset(v.x - tl.size.width / 2f, v.y - tl.size.height / 2f), alpha = alpha) }
                }
                withTransform({ translate(size.width / 2f - camX * k, size.height / 2f - camY * k); scale(k, k, Offset.Zero) }) {
                    grid(camX, camY, size.width / k, size.height / k)
                    links.forEach { l ->
                        val a = index[l.fromUid] ?: return@forEach
                        val b = index[l.toUid] ?: return@forEach
                        val f = fx.line(l.uid, now) // a new link draws itself from card A to card B
                        pixelLine(at(a), at(a) + (at(b) - at(a)) * f, if (touches(l, lit)) Color.White else LineInk, 1.5f)
                    }
                    cards.forEachIndexed { i, c ->
                        val pk = fx.pop(c.uid, now)
                        if (pk >= 1f) drawCard(boxes[i], c.kind == BIBLE_THING, c.locked, c.uid == selected || c.uid == linkFrom, c.uid == linkFrom)
                        else withTransform({ scale(pk, pk, at(i)) }) { drawCard(boxes[i], c.kind == BIBLE_THING, c.locked, c.uid == selected || c.uid == linkFrom, c.uid == linkFrom) }
                    }
                    for (g in fx.ghosts) { // a deleted card fades out where it was
                        val a = 1f - (now - g.t0) / BibleFx.GHOST_S
                        if (a <= 0f) continue
                        drawIntoCanvas { cv ->
                            val bounds = Rect(g.box[0] - 12f, g.box[1] - 12f, g.box[2] + 12f, g.box[3] + 12f)
                            val layer = Paint().apply { alpha = a }
                            cv.saveLayer(bounds, layer)
                            drawCard(g.box, g.thing, g.locked, false, false)
                            cv.restore()
                        }
                    }
                    pending?.let { p -> drawCard(boxAround(p, Size(CARD_MIN_W, 28f)), false, false, true, true) }
                }
                cards.indices.forEach { i -> fx.pop(cards[i].uid, now).let { pk -> text(names[i], at(i), appearAlpha(pk), pk) } }
                for (g in fx.ghosts) text(g.name, g.at, (1f - (now - g.t0) / BibleFx.GHOST_S).coerceIn(0f, 1f))
                // the labels over every line and card edge, in a small dark pill
                withTransform({ translate(size.width / 2f - camX * k, size.height / 2f - camY * k); scale(k, k, Offset.Zero) }) {
                    links.forEachIndexed { i, l ->
                        val q = pills[i]
                        if (q[2] < q[0] || fx.line(l.uid, now) < 1f) return@forEachIndexed // the label comes once the line has arrived
                        drawRoundRect(Night, Offset(q[0], q[1]), Size(q[2] - q[0], q[3] - q[1]), CornerRadius(5f))
                        drawRoundRect(if (touches(l, lit)) Color.White else Px.Border, Offset(q[0], q[1]), Size(q[2] - q[0], q[3] - q[1]), CornerRadius(5f), style = Stroke(1f))
                    }
                }
                links.forEachIndexed { i, l -> mids[i]?.takeIf { fx.line(l.uid, now) >= 1f }?.let { text(labels[i], it, if (lit == null || touches(l, lit)) 1f else 0.6f) } }
                if (cards.isEmpty() && pending == null) {
                    val tl: TextLayoutResult = measurer.measure("TAP EMPTY SPACE TO ADD A CARD", TextStyle(fontFamily = pixelFont, fontSize = 8.sp, color = Px.TextDim, textAlign = TextAlign.Center),
                        constraints = Constraints(maxWidth = (size.width - 32f * density).toInt().coerceAtLeast(1)))
                    drawText(tl, topLeft = Offset(size.width / 2f - tl.size.width / 2f, size.height / 2f - tl.size.height / 2f))
                }
            }

            // the info strip: the shown card's links in words and its note; or what the mode wants next
            val strip: Pair<String, String>? = when {
                selectedCard != null -> bibleWords(selectedCard, cards, links) to selectedCard.note
                mode == BibleMode.Link -> (if (linkFrom == null) "tap a card, then the card it links to" else "now tap the card it links to") to ""
                mode == BibleMode.Lock -> "tap a card to lock or unlock it" to ""
                else -> null
            }
            strip?.let { (words, note) ->
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0D0618)).border(1.5.dp, Lilac, RoundedCornerShape(10.dp))
                        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } } // taps on the strip never reach the canvas
                        .heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(words, Modifier.semantics { contentDescription = words }, style = TextStyle(fontFamily = pageFont, fontSize = 20.sp, lineHeight = 21.sp, color = Ink))
                    if (note.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(note, style = TextStyle(fontFamily = pageFont, fontSize = 18.sp, lineHeight = 19.sp, color = Px.TextDim))
                    }
                }
            }

            cardMenu?.let { (c, p) ->
                BrainMenu(p, listOf(
                    "Rename" to { renaming = c },
                    (if (c.kind == BIBLE_THING) "Make person" else "Make thing") to { onKind(c, if (c.kind == BIBLE_THING) BIBLE_PERSON else BIBLE_THING) },
                    "Note" to { noting = c },
                    "Delete" to { burning = c },
                ), { cardMenu = null }, clearW = menuClear.width.dp, clearH = menuClear.height.dp)
            }
            linkMenu?.let { (l, p) -> BrainMenu(p, listOf("Rename" to { relabeling = l }, "Delete" to { onDeleteLink(l) }), { linkMenu = null }) }
        }
    }

    pending?.let { p ->
        NameDialog("NEW CARD", "Name it: Abraham, the ark...", submit = "ADD", maxLen = BIBLE_NAME_MAX, onDismiss = { pending = null }, onSubmit = { onAdd(it.trim(), p.x, p.y) })
    }
    linking?.let { (a, b) ->
        NameDialog("LINK", "How are they related? e.g. son, wife, built", submit = "LINK", maxLen = BIBLE_LABEL_MAX, onDismiss = { linking = null }, onSubmit = { onLink(a, b, it.trim()) })
    }
    renaming?.let { c ->
        NameDialog("RENAME", "Name it", initial = c.name, submit = "RENAME", maxLen = BIBLE_NAME_MAX, onDismiss = { renaming = null }, onSubmit = { onRename(c, it) })
    }
    relabeling?.let { l ->
        NameDialog("RENAME LINK", "How are they related?", initial = l.label, submit = "RENAME", maxLen = BIBLE_LABEL_MAX, onDismiss = { relabeling = null }, onSubmit = { onRenameLink(l, it) })
    }
    noting?.let { c -> NoteDialog(c.name, c.note, onDismiss = { noting = null }, onSave = { onNote(c, it) }) }
    burning?.let { c ->
        val n = links.count { it.fromUid == c.uid || it.toUid == c.uid }
        TornNoteDialog(
            title = "Delete this card?",
            body = "\"${c.name}\"" + (if (n == 0) "" else if (n == 1) " and its link" else " and its $n links") + " go for good, on every device.",
            onKeep = { burning = null },
            onBurn = { burning = null; if (selected == c.uid) selected = null; onDelete(c) },
        )
    }
}

/** A header chip: lit when on. */
@Composable
private fun ModeChip(text: String, on: Boolean, onClick: () -> Unit) {
    val pill = RoundedCornerShape(50)
    Text(
        text,
        Modifier.clip(pill).background(if (on) Color(0xFF1A1030) else Color.Transparent).border(1.dp, if (on) Px.Lavender else Px.Border, pill)
            .semantics { contentDescription = "$text mode" + if (on) ", on" else "" }
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 9.dp, vertical = 8.dp),
        style = TextStyle(fontFamily = PixelFont, fontSize = 7.sp, color = if (on) Color.White else Px.TextDim),
    )
}

/** A card's longer note: the same field the dialogs use, several lines tall. */
@Composable
private fun NoteDialog(name: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    PixelDialog(onDismiss) {
        Text("NOTE · ${name.uppercase()}", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        PixelField(text, { text = it.take(BIBLE_NOTE_MAX) }, "Who they were, where it happened...", singleLine = false, minHeight = 140.dp, cursor = Px.Lavender)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PixelButton("CANCEL", onDismiss)
            PixelButton("SAVE", { onSave(text); onDismiss() }, primary = true)
        }
    }
}

/** A faint dot every 40 dp over the part of the canvas in view ([w] x [h] dp round [cx], [cy]), and the canvas edge. */
private fun DrawScope.grid(cx: Float, cy: Float, w: Float, h: Float) {
    val step = 40f
    val x0 = (((cx - w / 2f) / step).toInt() - 1) * step
    val y0 = (((cy - h / 2f) / step).toInt() - 1) * step
    var x = x0
    while (x <= cx + w / 2f + step) {
        var y = y0
        while (y <= cy + h / 2f + step) {
            if (x in -BIBLE_HALF..BIBLE_HALF && y in -BIBLE_HALF..BIBLE_HALF) drawRect(Color(0x33B69AFF), Offset(x - 0.75f, y - 0.75f), Size(1.5f, 1.5f))
            y += step
        }
        x += step
    }
    drawRect(Px.Border, Offset(-BIBLE_HALF - 60f, -BIBLE_HALF - 60f), Size(2f * BIBLE_HALF + 120f, 2f * BIBLE_HALF + 120f), style = Stroke(2f))
}

/** A straight line of little square pixels from [a] to [b]. */
private fun DrawScope.pixelLine(a: Offset, b: Offset, c: Color, px: Float) {
    val n = ((b - a).getDistance() / px).roundToInt().coerceIn(1, 4000)
    for (i in 0..n) {
        val p = a + (b - a) * (i / n.toFloat())
        drawRect(c, Offset((p.x / px).roundToInt() * px - px / 2f, (p.y / px).roundToInt() * px - px / 2f), Size(px, px))
    }
}

/** A card's body in [q]: a person a square-cut box, a thing a rounded dashed one; locked = a white border and a tiny lock; [hot] lit up. */
internal fun DrawScope.drawCard(q: FloatArray, thing: Boolean, locked: Boolean, hot: Boolean, picked: Boolean) {
    val tl = Offset(q[0], q[1]); val sz = Size(q[2] - q[0], q[3] - q[1])
    val edge = if (locked || picked) Color.White else Lilac
    if (hot) drawRect(Lilac.copy(alpha = if (picked) 0.45f else 0.22f), tl - Offset(5f, 5f), Size(sz.width + 10f, sz.height + 10f))
    // the first card of a link: a thick bright lilac frame round the card
    if (picked) drawRect(Color(0xFFD6C8FF), tl - Offset(5f, 5f), Size(sz.width + 10f, sz.height + 10f), style = Stroke(3f))
    val fill = if (hot) CardFillLit else CardFill
    if (thing) {
        drawRoundRect(fill, tl, sz, CornerRadius(10f))
        drawRoundRect(edge, tl + Offset(1f, 1f), Size(sz.width - 2f, sz.height - 2f), CornerRadius(9f), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f))))
    } else {
        // square-cut corners, like the rest of the pixel cards
        val b = 2f
        drawRect(fill, tl + Offset(b, b), Size(sz.width - 2f * b, sz.height - 2f * b))
        drawRect(edge, Offset(q[0] + b, q[1]), Size(sz.width - 2f * b, b))
        drawRect(edge, Offset(q[0] + b, q[3] - b), Size(sz.width - 2f * b, b))
        drawRect(edge, Offset(q[0], q[1] + b), Size(b, sz.height - 2f * b))
        drawRect(edge, Offset(q[2] - b, q[1] + b), Size(b, sz.height - 2f * b))
    }
    if (locked) {
        val u = 1.5f
        val x0 = q[2] - 5f * u - 3f; val y0 = q[1] - 3f * u
        drawRect(Night, Offset(x0 - u, y0 - u), Size(7f * u, 8f * u))
        LOCK_ROWS.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == 'X') drawRect(Color.White, Offset(x0 + x * u, y0 + y * u), Size(u, u)) } }
    }
}

/** A deleted card, kept for [BibleFx.GHOST_S] so it can fade out where it was. */
private class Ghost(val box: FloatArray, val thing: Boolean, val locked: Boolean, val name: TextLayoutResult, val at: Offset, val t0: Float)

/**
 * The Bible world's little animations: a new card pops in (0.6 to 1), a deleted one fades out, a new link line draws from card A to
 * card B. Stamps are seconds on [now]; the screen only redraws every frame while one of them is running (see the loop in [BibleWorld]).
 */
private class BibleFx {
    private val t0 = System.nanoTime()
    private val born = HashMap<String, Float>()
    private val linkBorn = HashMap<String, Float>()
    private val known = HashMap<String, Ghost>()
    private val knownLinks = HashSet<String>()
    val ghosts = ArrayList<Ghost>()
    private var first = true
    var endAt = 0f
        private set

    fun now() = (System.nanoTime() - t0) / 1_000_000_000f

    /** Compares the cards and links now with the last time: stamps the new ones and keeps the vanished cards as ghosts. */
    fun sync(cards: List<BibleCard>, boxes: List<FloatArray>, names: List<TextLayoutResult>, links: List<BibleLink>) {
        val t = now()
        val uids = HashSet<String>()
        for (i in cards.indices) {
            val c = cards[i]; uids.add(c.uid)
            if (!first && c.uid !in known) { born[c.uid] = t; endAt = maxOf(endAt, t + POP_S) }
            known[c.uid] = Ghost(boxes[i], c.kind == BIBLE_THING, c.locked, names[i], Offset(c.x, c.y), 0f)
        }
        val iter = known.entries.iterator()
        while (iter.hasNext()) {
            val (u, g) = iter.next()
            if (u in uids) continue
            iter.remove(); born.remove(u)
            if (!first) { ghosts.add(Ghost(g.box, g.thing, g.locked, g.name, g.at, t)); endAt = maxOf(endAt, t + GHOST_S) }
        }
        val ls = HashSet<String>()
        for (l in links) {
            ls.add(l.uid)
            if (!first && l.uid !in knownLinks) { linkBorn[l.uid] = t; endAt = maxOf(endAt, t + LINK_S) }
        }
        knownLinks.clear(); knownLinks.addAll(ls); linkBorn.keys.retainAll(ls)
        first = false
    }

    fun pop(uid: String, t: Float) = born[uid]?.let { popScale(t - it) } ?: 1f
    /** 0..1: how much of a new link's line is drawn. */
    fun line(uid: String, t: Float) = linkBorn[uid]?.let { easeOut((t - it) / LINK_S) } ?: 1f
    fun prune(t: Float) { ghosts.removeAll { t - it.t0 >= GHOST_S } }

    companion object { const val GHOST_S = 0.25f; const val LINK_S = 0.3f }
}
