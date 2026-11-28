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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalFocusManager
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
    /** Link mode: a marriage ring picked (its link uid; null = none any more), waiting for the child. */
    data class PickRing(val uid: String?) : BibleTap
    data class Child(val ring: String, val child: String) : BibleTap
}

/**
 * A tap at [at] (canvas dp) on [card] (its uid; null = none) or else a link's label [link] ([ring] too when that is a marriage ring), in
 * [mode], with [selected] the shown card, [linkFrom] the card picked first in Link mode and [ringFrom] the ring picked first. A tap on
 * empty space while a card is shown only hides it, so a tap meant to close the info strip never makes a card. Null = nothing happens.
 */
internal fun bibleTap(mode: BibleMode, card: String?, link: String?, selected: String?, linkFrom: String?, at: Offset, ring: String? = null, ringFrom: String? = null): BibleTap? = when (mode) {
    BibleMode.Move -> when {
        card != null -> BibleTap.Select(if (card == selected) null else card)
        link != null -> BibleTap.EditLink(link)
        selected != null -> BibleTap.Select(null)
        else -> BibleTap.Create(onCanvas(at))
    }
    BibleMode.Link -> when {
        ringFrom != null -> when {
            card != null -> BibleTap.Child(ringFrom, card)
            ring != null && ring != ringFrom -> BibleTap.PickRing(ring)
            else -> BibleTap.PickRing(null)
        }
        card != null && linkFrom == null -> BibleTap.Pick(card)
        card != null && card == linkFrom -> BibleTap.Pick(null)
        card != null -> BibleTap.Link(linkFrom!!, card)
        ring != null && linkFrom == null -> BibleTap.PickRing(ring)
        link != null -> BibleTap.EditLink(link)
        else -> BibleTap.Pick(null)
    }
    BibleMode.Lock -> card?.let { BibleTap.Lock(it) }
}

private val VERBS = setOf("built", "made", "bore", "begat", "wrote", "led", "saw", "fought", "slew", "met", "took", "gave", "sent", "left", "found", "sold", "bought", "became", "knew", "won", "ruled", "freed", "fed", "taught", "anointed")
private val TAIL_WORDS = setOf("of", "by", "to", "with", "from", "for", "in", "on", "at", "into")

/** Offsets along the perpendicular for [n] labels sharing one midpoint, [step] apart and centred on it (2 -> -step/2, +step/2). */
internal fun stackOffsets(n: Int, step: Float): List<Float> = List(n) { (it - (n - 1) / 2f) * step }

/** A deed link's curve: quadratic bezier [a] -> [b] with control point [c]; [apex] is its middle, [side] the unit direction it bulges. */
internal class DeedCurve(val a: Offset, val c: Offset, val b: Offset, val side: Offset) {
    fun at(t: Float): Offset { val u = 1f - t; return a * (u * u) + c * (2f * u * t) + b * (t * t) }
    fun tangent(t: Float): Offset = (c - a) * (2f * (1f - t)) + (b - c) * (2f * t)
    val apex get() = at(0.5f)
}

/**
 * The curve for a deed from [a] to [b]: the control point is the midpoint pushed 22% of the distance (clamped [lo]..[hi], times 1 + 0.6 [k] for
 * the k-th deed on the same pair) sideways: down when the cards are on about the same row (a smile), else to the right of the way of travel.
 */
internal fun deedCurve(a: Offset, b: Offset, k: Int = 0, lo: Float = 14f, hi: Float = 60f): DeedCurve {
    val d = b - a; val len = d.getDistance()
    val side = if (len == 0f || kotlin.math.abs(d.y) < kotlin.math.abs(d.x)) Offset(0f, 1f) else Offset(-d.y, d.x) / len
    return DeedCurve(a, (a + b) / 2f + side * ((len * 0.22f).coerceIn(lo, hi) * (1f + 0.6f * k)), b, side)
}

/** A cubic bezier [p0] -> [p3] with control points [p1], [p2]. */
internal class Cubic(val p0: Offset, val p1: Offset, val p2: Offset, val p3: Offset) {
    fun at(t: Float): Offset { val u = 1f - t; return p0 * (u * u * u) + p1 * (3f * u * u * t) + p2 * (3f * u * t * t) + p3 * (t * t * t) }
}

/** A family strand from a marriage [ring] down to a child's [top]: leaves the ring straight down, arrives at the child straight down. */
internal fun childCurve(ring: Offset, top: Offset): Cubic {
    val h = (kotlin.math.abs(top.y - ring.y) * 0.5f).coerceAtLeast(12f)
    return Cubic(ring, ring + Offset(0f, h), top - Offset(0f, h), top)
}

/** A plain link's gentle curve: the middle bows sideways 8% of the length, at most 20 (quadratic, so its control point sits twice as far). */
internal fun bowedLink(a: Offset, b: Offset): DeedCurve {
    val d = b - a; val len = d.getDistance()
    if (len == 0f) return DeedCurve(a, a, b, Offset.Zero)
    val side = Offset(-d.y, d.x) / len
    return DeedCurve(a, (a + b) / 2f + side * (2f * (len * 0.08f).coerceAtMost(20f)), b, side)
}

/** A link labelled "married" is a marriage: drawn as a double line with a ring. */
internal fun isMarriage(label: String) = label.trim().equals("married", ignoreCase = true)

/** A label that reads as a deed ("built", "anointed") rather than a role ("son", "wife"). */
internal fun isDeed(label: String) = label.trim().lowercase().let { it.endsWith("ed") || it in VERBS }

/** How a link [label] reads from its own card: "son" -> "son of", "built" -> "built", "married to" stays. */
internal fun outWords(label: String): String {
    val l = label.trim()
    if (isMarriage(l)) return "married to"
    return if (isDeed(l) || l.substringAfterLast(' ').lowercase() in TAIL_WORDS) l else "$l of"
}

/** How a link [label] reads from the card it points at: "son" -> "has son", "built" -> "built by", "son of" -> "has son". */
internal fun inWords(label: String): String {
    val l = label.trim()
    val last = l.substringAfterLast(' ').lowercase()
    return when {
        isMarriage(l) -> "married to"
        isDeed(l) -> "$l by"
        last == "of" && ' ' in l -> "has ${l.substringBeforeLast(' ')}"
        last in TAIL_WORDS -> l
        else -> "has $l"
    }
}

private val PARENT_OUT = setOf("father of", "mother of", "parent of") // from = parent, to = child
private val PARENT_IN = setOf("son of", "daughter of", "child of") // from = child, to = parent

/** (child, parent) of a parent link, else null. */
internal fun parentEnds(l: BibleLink): Pair<String, String>? = when (l.label.trim().lowercase()) {
    in PARENT_OUT -> l.toUid to l.fromUid
    in PARENT_IN -> l.fromUid to l.toUid
    else -> null
}?.takeIf { it.first != it.second }

/** child uid -> its parents' uids, in link order. */
internal fun parentsMap(links: List<BibleLink>): Map<String, Set<String>> =
    links.mapNotNull(::parentEnds).groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

/** child uid -> the marriage link of two of its parents: such a child hangs from that marriage's ring. */
internal fun marriedParents(links: List<BibleLink>, parents: Map<String, Set<String>> = parentsMap(links)): Map<String, BibleLink> {
    val marriages = links.filter { isMarriage(it.label) }
    return parents.mapNotNull { (c, ps) -> marriages.firstOrNull { it.fromUid in ps && it.toUid in ps }?.let { c to it } }.toMap()
}

/** A deed line ("created", "killed"): a deed word that is neither a marriage nor a parent link. */
internal fun isDeedLink(l: BibleLink) = isDeed(l.label) && !isMarriage(l.label) && parentEnds(l) == null

private val MADE = setOf("created", "made", "formed")

/** [uid] plus all its ancestors via parent links (cycle-safe); a parentless one also takes the sources of "created"/"made"/"formed" links pointing at it. */
internal fun lineageOf(uid: String, cards: List<BibleCard>, links: List<BibleLink>): Set<String> {
    val known = cards.mapTo(HashSet()) { it.uid }
    if (uid !in known) return emptySet()
    val par = parentsMap(links)
    val out = linkedSetOf(uid); val todo = ArrayDeque(listOf(uid))
    while (todo.isNotEmpty()) for (p in par[todo.removeFirst()].orEmpty()) if (p in known && out.add(p)) todo.add(p)
    for (u in out.toList()) if (par[u].orEmpty().none { it in known })
        for (l in links) if (l.toUid == u && l.fromUid in known && l.label.trim().lowercase() in MADE) out.add(l.fromUid)
    return out
}

/** 'm' for a card with a "son of" link, 'f' for "daughter of", else null. */
private fun gender(uid: String, links: List<BibleLink>) = links.firstNotNullOfOrNull { l ->
    if (l.fromUid != uid) null else when (l.label.trim().lowercase()) { "son of" -> 'm'; "daughter of" -> 'f'; else -> null }
}

/**
 * [card]'s links in plain words: its parents ("son of Adam and Eva"), its siblings (never stored: cards sharing a parent), its marriage,
 * then the rest, its own first, then the ones pointing at it, each kind once with its names:
 * "Isaac: son of Abraham, Sarah · has son Jacob, Esau". A link to a card that is gone is left out.
 */
private fun parentsOf(card: BibleCard, cards: List<BibleCard>, links: List<BibleLink>): List<String> {
    val name = cards.associate { it.uid to it.name }
    val byX = cards.associate { it.uid to it.x }
    val kin0 = parentsMap(links)[card.uid].orEmpty().filter { it in name }.sortedWith(compareBy({ byX.getValue(it) }, { name.getValue(it) })) // unmarried parents: left to right, then by name
    val wed = links.firstOrNull { isMarriage(it.label) && it.fromUid in kin0 && it.toUid in kin0 }
    return if (wed == null) kin0 else listOf(wed.fromUid, wed.toUid) + kin0.filter { it != wed.fromUid && it != wed.toUid } // married parents: in marriage order
}

/** "brother"/"sister"/"sibling" (with or without " of") when a link label is a sibling word, else null. */
private fun siblingWord(label: String): String? = label.trim().lowercase().removeSuffix(" of").trim().takeIf { it == "brother" || it == "sister" || it == "sibling" }

/** A sibling-word link between two cards that already share a parent: the tree shows them as siblings, so the link is not drawn. */
internal fun siblingLinkHidden(l: BibleLink, parents: Map<String, Set<String>>): Boolean =
    siblingWord(l.label) != null && parents[l.fromUid].orEmpty().intersect(parents[l.toUid].orEmpty()).isNotEmpty()

/** Whether [c] has big moments in the shown language (they earn it a star on the canvas). */
internal fun hasMoments(c: BibleCard, ka: Boolean): Boolean = (if (ka) c.momentsKa else c.moments).isNotBlank()

/** [card]'s siblings once each (shared parents plus user sibling links) and the word for it: its own gender, else the explicit link's word, else "sibling". */
private fun siblingFacts(card: BibleCard, cards: List<BibleCard>, links: List<BibleLink>, kin: List<String>): Pair<List<String>, String> {
    val all = parentsMap(links)
    val ex = links.filter { it.fromUid != it.toUid && (it.fromUid == card.uid || it.toUid == card.uid) && siblingWord(it.label) != null }
    val known = cards.mapTo(HashSet()) { it.uid }
    val uids = (cards.filter { it.uid != card.uid && all[it.uid].orEmpty().any { p -> p in kin } }.map { it.uid } + ex.map { if (it.fromUid == card.uid) it.toUid else it.fromUid }).filter { it in known }.distinct()
    val word = when (gender(card.uid, links)) { 'm' -> "brother"; 'f' -> "sister"; else -> ex.firstOrNull()?.let { siblingWord(it.label) } ?: "sibling" }
    return uids to word
}

internal fun bibleWords(card: BibleCard, cards: List<BibleCard>, links: List<BibleLink>): String {
    val name = cards.associate { it.uid to it.name }
    val kin = parentsOf(card, cards, links)
    val sex = gender(card.uid, links)
    fun and(l: List<String>) = if (l.size < 2) l.joinToString() else l.dropLast(1).joinToString(", ") + " and " + l.last()
    val pieces = mutableListOf<String>()
    if (kin.isNotEmpty()) pieces += "${when (sex) { 'm' -> "son"; 'f' -> "daughter"; else -> "child" }} of ${and(kin.map { name.getValue(it) })}"
    val (sibs, sibWord) = siblingFacts(card, cards, links, kin)
    if (sibs.isNotEmpty()) pieces += "$sibWord of ${and(sibs.map { name.getValue(it) }.distinct())}"
    val parts = LinkedHashMap<String, MutableList<String>>()
    fun add(l: BibleLink, other: String, words: String) { if (parentEnds(l) == null || other !in kin) name[other]?.let { parts.getOrPut(words) { mutableListOf() }.add(it) } }
    for (l in links) if (l.fromUid == card.uid && siblingWord(l.label) == null) add(l, l.toUid, outWords(l.label))
    for (l in links) if (l.toUid == card.uid && l.fromUid != card.uid && siblingWord(l.label) == null) add(l, l.fromUid, inWords(l.label))
    pieces += parts.entries.sortedBy { it.key != "married to" }.map { (k, v) -> "$k ${v.distinct().joinToString(", ")}" }
    return "${card.name}: ${if (pieces.isEmpty()) "no links yet" else pieces.joinToString(" · ")}"
}

/** Up to [max] cards whose English or Georgian name has a word starting with [query] (case-insensitive); none for a blank query. */
internal fun bibleFind(query: String, cards: List<BibleCard>, max: Int = 6): List<BibleCard> {
    val q = query.trim().lowercase().replace('-', ' ').takeIf { it.isNotEmpty() } ?: return emptyList()
    fun hit(n: String) = " ${n.lowercase().replace('-', ' ')}".contains(" $q")
    return cards.filter { hit(it.name) || hit(kaName(it)) }.take(max)
}

/** Georgian guesses for the well-known names (lowercase English key); a card's own [BibleCard.nameKa] wins. */
internal val KA_NAMES: Map<String, String> = mapOf(
    "god" to "ღმერთი", "adam" to "ადამი", "eve" to "ევა", "eva" to "ევა", "cain" to "კაენი", "abel" to "აბელი", "seth" to "შეთი",
    "enoch" to "ენოქი", "irad" to "ირადი", "mehujael" to "მეხუიაელი", "methushael" to "მეთუშაელი", "lamech" to "ლამექი",
    "adah" to "ადა", "zillah" to "ცილა", "jabal" to "იაბალი", "jubal" to "იუბალი", "tubal-cain" to "თუბალ-კაენი", "naamah" to "ნაამა",
    "enosh" to "ენოში", "noah" to "ნოე", "shem" to "სემი", "ham" to "ქამი", "japheth" to "იაფეთი", "methuselah" to "მათუსალა",
    "abraham" to "აბრაამი", "sarah" to "სარა", "isaac" to "ისააკი", "jacob" to "იაკობი", "esau" to "ესავი", "cain's wife" to "კაენის ცოლი",
)

/** Georgian guesses for common link labels (lowercase English key). */
internal val KA_WORDS: Map<String, String> = mapOf(
    "married" to "ცოლ-ქმარი", "father of" to "მამა", "mother of" to "დედა", "son of" to "ძე", "daughter of" to "ასული", "child of" to "შვილი",
    "brother" to "ძმა", "sister" to "და", "killed" to "მოკლა", "created" to "შექმნა",
)

/** A card's Georgian name: the one the user wrote, else the dictionary's guess, else "". */
internal fun kaName(c: BibleCard): String = c.nameKa.ifBlank { KA_NAMES[c.name.trim().lowercase()] ?: "" }
internal fun kaLabel(l: BibleLink): String = l.labelKa.ifBlank { KA_WORDS[l.label.trim().lowercase()] ?: "" }

/** A card's name in the shown language and how bright to draw it: 1 = written/English, [KA_GUESS] = a dictionary guess, [KA_MISSING] = English still to translate. */
internal fun shownName(c: BibleCard, ka: Boolean): Pair<String, Float> = when {
    !ka -> c.name to 1f
    c.nameKa.isNotBlank() -> c.nameKa to 1f
    kaName(c).isNotEmpty() -> kaName(c) to KA_GUESS
    else -> c.name to KA_MISSING
}
internal fun shownLabel(l: BibleLink, ka: Boolean): Pair<String, Float> = when {
    !ka -> l.label to 1f
    l.labelKa.isNotBlank() -> l.labelKa to 1f
    kaLabel(l).isNotEmpty() -> kaLabel(l) to KA_GUESS
    else -> l.label to KA_MISSING
}
internal const val KA_GUESS = 0.75f
internal const val KA_MISSING = 0.5f

/**
 * [bibleWords] in Georgian, as "name — role: names" groups with no sentence grammar:
 * "აბელი — მშობლები: ადამი, ევა · ძმა: კაენი · მოკლა: ← კაენი" (a deed or link goes "→ name" outward, "← name" inward).
 */
internal fun bibleWordsKa(card: BibleCard, cards: List<BibleCard>, links: List<BibleLink>): String {
    val name = cards.associate { it.uid to shownName(it, true).first }
    val kin = parentsOf(card, cards, links)
    val groups = LinkedHashMap<String, MutableList<String>>()
    fun put(k: String, v: String) { groups.getOrPut(k) { mutableListOf() }.let { if (v !in it) it.add(v) } }
    for (k in kin) put("მშობლები", name.getValue(k))
    val (sibs, sibWord) = siblingFacts(card, cards, links, kin)
    for (u in sibs) put(when (sibWord) { "brother" -> "ძმა"; "sister" -> "და"; else -> "და-ძმა" }, name.getValue(u))
    val marriages = links.filter { isMarriage(it.label) && (it.fromUid == card.uid || it.toUid == card.uid) }
    for (l in marriages) name[if (l.fromUid == card.uid) l.toUid else l.fromUid]?.let { put("მეუღლე", it) }
    for (l in links) {
        if (l in marriages || l.fromUid == l.toUid || siblingWord(l.label) != null) continue
        val out = l.fromUid == card.uid; if (!out && l.toUid != card.uid) continue
        val other = if (out) l.toUid else l.fromUid
        if (parentEnds(l) != null && other in kin) continue
        name[other]?.let { put(shownLabel(l, true).first, (if (out) "→ " else "← ") + it) }
    }
    val body = if (groups.isEmpty()) "ბმები ჯერ არ არის" else groups.entries.joinToString(" · ") { "${it.key}: ${it.value.joinToString(", ")}" }
    return "${name.getValue(card.uid)} — $body"
}

/** A Genesis verse reference in English or Georgian ("Gen 4:19", "Genesis 2:24", "Gen. 4:8", "დაბ 4:19-20"): book, chapter, verse, optional "-verse". */
private val VERSE_REF = Regex("""(?:Genesis|Gen|დაბადება|დაბ)\.?\s*(\d{1,3}):(\d{1,3})(?:\s?[-–]\s?(\d{1,3}))?""", RegexOption.IGNORE_CASE)

/** Where the verse references are in [text]. */
internal fun verseRefs(text: String): List<IntRange> = VERSE_REF.findAll(text).map { it.range }.toList()

/** (chapter, verse) of one reference text found by [verseRefs]. */
internal fun verseOf(ref: String): Pair<Int, Int>? = VERSE_REF.find(ref)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

private const val TIDY_ROW = 130f // dp between generations
private const val TIDY_GAP = 64f // dp between neighbours in a row
private const val TIDY_MATE_GAP = 96f // dp between the two spouses of a couple (room for the ring and "married")

/**
 * New centres (uid -> point) for the unlocked cards that have links, as a family tree: a row per generation (no parents = 0, a child one
 * below its lowest parent, spouses share a row, a parentless unmarried card that did a deed sits one row above its deeds' targets). A row
 * keeps couples together and children in their parents' order, each group centred under its parents, nothing closer than [TIDY_GAP].
 * Cards with no links stay where they are; locked ones are left out, so they never move. [sizes] runs parallel to [cards].
 */
internal fun bibleTidy(cards: List<BibleCard>, links: List<BibleLink>, sizes: List<Size>): Map<String, Offset> {
    val at = cards.indices.associate { cards[it].uid to it }
    val ls = links.filter { it.fromUid in at && it.toUid in at && it.fromUid != it.toUid }
    val parents = parentsMap(ls)
    val spouses = ls.filter { isMarriage(it.label) }
    val linked = ls.flatMapTo(HashSet()) { listOf(it.fromUid, it.toUid) }
    val used = cards.map { it.uid }.filter { it in linked }
    val gen = used.associateWith { 0 }.toMutableMap()
    for (pass in 0..cards.size + 2) { // the cap stops a loop of parents
        var moved = false
        for ((c, ps) in parents) for (p in ps) if (gen.getValue(c) <= gen.getValue(p)) { gen[c] = gen.getValue(p) + 1; moved = true }
        for (m in spouses) {
            val g = max(gen.getValue(m.fromUid), gen.getValue(m.toUid))
            if (gen[m.fromUid] != g || gen[m.toUid] != g) { gen[m.fromUid] = g; gen[m.toUid] = g; moved = true }
        }
        if (!moved) break
    }
    val deeds = ls.filter(::isDeedLink)
    for (s in used) if (s !in parents && spouses.none { it.fromUid == s || it.toUid == s })
        deeds.filter { it.fromUid == s }.minOfOrNull { gen.getValue(it.toUid) }?.let { gen[s] = it - 1 }

    val free = used.filter { !cards[at.getValue(it)].locked }
    if (free.isEmpty()) return emptyMap()
    val top = free.minOf { cards[at.getValue(it)].y }
    val g0 = free.minOf { gen.getValue(it) }
    val pos = cards.associate { it.uid to Offset(it.x, it.y) }.toMutableMap()
    val out = HashMap<String, Offset>()
    fun w(u: String) = sizes[at.getValue(u)].width
    fun want(u: String) = parents[u]?.map { pos.getValue(it).x }?.average()?.toFloat() ?: pos.getValue(u).x // under its parents' ring, else where it is
    class Block(var sum: Float, var n: Int, var w: Float, val items: MutableList<Pair<List<String>, Float>>) // sum / n = left edge
    for (g in free.map { gen.getValue(it) }.distinct().sorted()) {
        val row = free.filter { gen[it] == g }.sortedBy { pos.getValue(it).x } // a couple keeps its left-right order
        val seen = HashSet<String>(); val units = ArrayList<List<String>>()
        for (u in row) {
            if (!seen.add(u)) continue
            val mate = spouses.firstNotNullOfOrNull { m -> (if (m.fromUid == u) m.toUid else if (m.toUid == u) m.fromUid else null)?.takeIf { it in row && it !in seen } }
            units += if (mate == null) listOf(u) else { seen.add(mate); listOf(u, mate) }
        }
        val keyed = units.map { us -> us to us.map(::want).average().toFloat() }.sortedWith(compareBy({ it.second }, { pos.getValue(it.first[0]).x }))
        val blocks = ArrayList<Block>()
        for ((us, key) in keyed) {
            val uw = us.sumOf { w(it).toDouble() }.toFloat() + TIDY_MATE_GAP * (us.size - 1)
            var b = Block(key - uw / 2f, 1, uw, mutableListOf(us to 0f))
            blocks += b
            while (blocks.size > 1) { // a block that would touch the one before merges with it and both re-centre
                val p = blocks[blocks.size - 2]
                if (p.sum / p.n + p.w + TIDY_GAP <= b.sum / b.n) break
                val shift = p.w + TIDY_GAP
                p.items += b.items.map { it.first to it.second + shift }
                p.sum += b.sum - b.n * shift; p.n += b.n; p.w = shift + b.w
                blocks.removeAt(blocks.size - 1); b = p
            }
        }
        val y = top + (g - g0) * TIDY_ROW
        for (b in blocks) for ((us, off) in b.items) {
            var x = b.sum / b.n + off
            for (u in us) { pos[u] = onCanvas(Offset(x + w(u) / 2f, y)); out[u] = pos.getValue(u); x += w(u) + TIDY_MATE_GAP }
        }
    }
    return out
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
private val VeinInk = Color(0xFF6E5BC4)
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
    onNote: (BibleCard, String) -> Unit,
    lang: String,
    onLang: () -> Unit,
    kaText: suspend () -> String? = { null },
    onThoughts: (BibleCard, String) -> Unit,
    onNameKa: (BibleCard, String) -> Unit,
    onNoteKa: (BibleCard, String) -> Unit,
    onThoughtsKa: (BibleCard, String) -> Unit,
    onMoments: (BibleCard, String) -> Unit,
    onMomentsKa: (BibleCard, String) -> Unit,
    onLabelKa: (BibleLink, String) -> Unit,
    onDelete: (BibleCard) -> Unit,
    onLink: (String, String, String) -> Unit,
    onLinks: (List<Triple<String, String, String>>) -> Unit,
    onRenameLink: (BibleLink, String) -> Unit,
    onDeleteLink: (BibleLink) -> Unit,
) {
    var mode by remember { mutableStateOf(BibleMode.Move) }
    var selected by remember { mutableStateOf<String?>(null) }
    var linkFrom by remember { mutableStateOf<String?>(null) }
    var ringFrom by remember { mutableStateOf<String?>(null) } // the marriage (link uid) picked, waiting for its child
    var adopting by remember { mutableStateOf<Pair<BibleLink, String>?>(null) } // that marriage and the child tapped, waiting for son / daughter
    var pending by remember { mutableStateOf<Offset?>(null) } // a new card waiting for its name
    var linking by remember { mutableStateOf<Pair<String, String>?>(null) } // waiting for its label
    var cardMenu by remember { mutableStateOf<Pair<BibleCard, Offset>?>(null) }
    var linkMenu by remember { mutableStateOf<Pair<BibleLink, Offset>?>(null) }
    var renaming by remember { mutableStateOf<BibleCard?>(null) }
    var reader by remember { mutableStateOf<Pair<Int, Int>?>(null) } // the reader open at (chapter, verse); verse 0 = no highlight
    var stripH by remember { mutableStateOf(0) }
    var paging by remember { mutableStateOf<String?>(null) } // the uid of the card whose character page is open
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
    LaunchedEffect(links) { if (links.none { it.uid == ringFrom }) ringFrom = null }

    val home = remember { bibleHome(cards) }
    var camX by remember { mutableFloatStateOf(home.x) }
    var camY by remember { mutableFloatStateOf(home.y) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var view by remember { mutableStateOf(IntSize.Zero) }

    BackHandler(true, onBack)
    BackHandler(selected != null || linkFrom != null || ringFrom != null) { selected = null; linkFrom = null; ringFrom = null }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var glide by remember { mutableStateOf<Offset?>(null) } // a canvas point the camera is gliding to
    BackHandler(searching) { searching = false }
    LaunchedEffect(glide) {
        val t = glide ?: return@LaunchedEffect
        val c = onCanvas(t); val x0 = camX; val y0 = camY
        animate(0f, 1f, animationSpec = tween(500)) { f, _ -> camX = x0 + (c.x - x0) * f; camY = y0 + (c.y - y0) * f }
        glide = null
    }

    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val pixelFont = PixelFont; val pageFont = PageFont
    val ka = lang == "ka"
    val names = remember(cards, density, pixelFont, ka) {
        cards.map { c ->
            measurer.measure(shownName(c, ka).first, TextStyle(fontFamily = pixelFont, fontSize = 8.sp, lineHeight = 13.sp, color = Color.White, textAlign = TextAlign.Center),
                maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (CARD_TEXT_W * density).toInt()))
        }
    }
    val labels = remember(links, density, pixelFont, ka) {
        links.map { l -> measurer.measure(shownLabel(l, ka).first, TextStyle(fontFamily = pixelFont, fontSize = 6.sp, color = Lilac), maxLines = 1) }
    }
    val nameDim = cards.map { shownName(it, ka).second }
    val labelDim = links.map { shownLabel(it, ka).second }
    val sizes = names.map { Size(max(CARD_MIN_W, it.size.width / density + 2f * CARD_PAD_X), it.size.height / density + 2f * CARD_PAD_Y) }
    fun at(i: Int): Offset = cards[i].let { c -> (dragging ?: dropped)?.takeIf { it.first == c.uid }?.second ?: Offset(c.x, c.y) }
    val index = cards.withIndex().associate { (i, c) -> c.uid to i }
    val boxes = cards.indices.map { boxAround(at(it), sizes[it]) }
    val family = marriedParents(links) // a child of a married couple hangs from their ring, so its parent links are not drawn
    val parents = parentsMap(links)
    // "hung" links are not drawn at all: a parent link hung from its ring, or a sibling word between cards that already share a parent
    fun hung(l: BibleLink) = parentEnds(l)?.let { (c, p) -> family[c]?.let { p == it.fromUid || p == it.toUid } } == true || siblingLinkHidden(l, parents)
    val ok = links.filter { !hung(it) && index[it.fromUid] != null && index[it.toUid] != null }
    val deedK = ok.filter(::isDeedLink).groupBy { setOf(it.fromUid, it.toUid) }.values.flatMap { g -> g.sortedBy { it.uid }.mapIndexed { k, l -> l.uid to k } }.toMap() // k-th deed on a pair bulges more
    val stack = ok.filter { !isDeedLink(it) }
        .groupBy { setOf(it.fromUid, it.toUid) }.values.flatMap { g -> g.sortedBy { it.uid }.let { s -> s.zip(stackOffsets(s.size, labels[0].size.height / density + 2f * PILL_PAD + 3f)) } }
        .associate { (l, o) -> l.uid to o } // links joining the same two cards: label midpoints stacked across the line
    val mids = links.map { l ->
        val a = index[l.fromUid]; val b = index[l.toUid]
        if (a == null || b == null) null else {
            val d = at(b) - at(a)
            val m = (at(a) + at(b)) / 2f
            val o = stack[l.uid] ?: 0f
            if (isDeedLink(l)) deedCurve(at(a), at(b), deedK[l.uid] ?: 0).let { it.apex + it.side * (6f + labels[links.indexOf(l)].size.height / density / 2f) } // just past the apex
            else if (isMarriage(l.label)) { if (o == 0f || d.getDistance() == 0f) m else m + Offset(-d.y, d.x) / d.getDistance() * o }
            else bowedLink(at(a), at(b)).let { it.apex + it.side * o } // the pill sits on the bowed line
        }
    }
    val ringCards = links.firstOrNull { it.uid == ringFrom }?.let { setOf(it.fromUid, it.toUid) }.orEmpty()
    val pills = links.indices.map { i -> mids[i]?.takeIf { !hung(links[i]) }?.let { if (isMarriage(links[i].label)) boxAround(it, Size(16f, 16f)) else boxAround(it, Size(labels[i].size.width / density + 2f * PILL_PAD, labels[i].size.height / density + 2f * PILL_PAD)) } ?: floatArrayOf(0f, 0f, -1f, -1f) }

    // animations: stamped when cards or links change, then a frame loop runs only for the few hundred ms they last
    val fx = remember { BibleFx() }
    var clock by remember { mutableFloatStateOf(0f) }
    remember(cards, links) { fx.sync(cards, boxes, names, links) }
    LaunchedEffect(cards, links) {
        while (fx.now() < fx.endAt) withFrameNanos { clock = fx.now() }
        clock = fx.now(); fx.prune(clock)
    }

    var sparkClock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(selected != null) { // sparks only run while a card is selected
        while (selected != null) withFrameNanos { sparkClock = it / 1_000_000_000f }
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
        val ring = links.getOrNull(li)?.takeIf { isMarriage(it.label) }?.uid
        when (val t = bibleTap(mode, cards.getOrNull(ci)?.uid, links.getOrNull(li)?.uid, selected, linkFrom, w, ring, ringFrom)) {
            is BibleTap.Create -> { pending = t.at }
            is BibleTap.Select -> selected = t.uid
            is BibleTap.Pick -> linkFrom = t.uid
            is BibleTap.PickRing -> ringFrom = t.uid
            is BibleTap.Child -> links.firstOrNull { it.uid == t.ring }?.let { m -> if (t.child != m.fromUid && t.child != m.toUid) { adopting = m to t.child; ringFrom = null } }
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
    // Not ::tap: the compiler caches a local function reference once, so taps hit-tested the first frame's (empty) card list.
    val tapNow by rememberUpdatedState(@DontMemoize { p: Offset -> tap(p) })
    val longNow by rememberUpdatedState(@DontMemoize { p: Offset -> longPress(p) })
    val selectedCard = cards.firstOrNull { it.uid == selected }
    val count = if (cards.size == 1) "1 card" else "${cards.size} cards"

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Night)) {
        WorldHeader("bible", "", onBack) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                FindChip(searching) { searching = !searching; query = "" }
                ModeChip(if (ka) "ქა" else "EN", false, onLang)
                for (m in BibleMode.entries) ModeChip(m.name, m == mode) { mode = m; linkFrom = null; ringFrom = null; if (m != BibleMode.Move) selected = null }
                ModeChip("Tidy", false) {
                    for ((u, p) in bibleTidy(cards, links, sizes)) cards.firstOrNull { it.uid == u }?.takeIf { !it.locked && (it.x != p.x || it.y != p.y) }?.let { onMove(it, p.x, p.y) }
                }
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
                val line = if (linkFrom == null) selected?.let { lineageOf(it, cards, links) }.orEmpty() else emptySet() // a selected card lights its line of ancestors
                fun inLine(l: BibleLink) = l.fromUid in line && l.toUid in line && (isMarriage(l.label) || parentEnds(l) != null || (isDeedLink(l) && l.label.trim().lowercase() in MADE))
                fun touches(l: BibleLink, u: String?) = u != null && (l.fromUid == u || l.toUid == u)
                // text is laid out in px: drawn in view px, scaled only by the zoom round its middle
                val now = clock
                val sc = if (selected != null) sparkClock else 0f
                fun text(tl: TextLayoutResult, c: Offset, alpha: Float = 1f, grow: Float = 1f) {
                    val v = toView(c)
                    val zk = zoom * grow
                    withTransform({ scale(zk, zk, v) }) { drawText(tl, topLeft = Offset(v.x - tl.size.width / 2f, v.y - tl.size.height / 2f), alpha = alpha) }
                }
                withTransform({ translate(size.width / 2f - camX * k, size.height / 2f - camY * k); scale(k, k, Offset.Zero) }) {
                    grid(camX, camY, size.width / k, size.height / k)
                    val paths = ArrayList<(Float) -> Offset>() // lineage paths from the selected card up toward God
                    links.forEach { l ->
                        val a = index[l.fromUid] ?: return@forEach
                        val b = index[l.toUid] ?: return@forEach
                        if (hung(l)) return@forEach
                        val f = fx.line(l.uid, now) // a new link draws itself from card A to card B
                        val col = if (touches(l, lit) || inLine(l)) Color.White else if (isMarriage(l.label) || isDeedLink(l)) LineInk else VeinInk
                        val pa = at(a); val pb = pa + (at(b) - pa) * f
                        if (isMarriage(l.label)) { // two parallel lines
                            val d = at(b) - pa
                            val n = if (d.getDistance() > 0f) Offset(-d.y, d.x) / d.getDistance() * 2.5f else Offset.Zero
                            pixelLine(pa + n, pb + n, col, 1.5f); pixelLine(pa - n, pb - n, col, 1.5f)
                        } else if (isDeedLink(l)) { // a dashed curve with an arrowhead at the target's box edge
                            deedStroke(deedCurve(at(a), at(b), deedK[l.uid] ?: 0), boxes[a], boxes[b], f, col, 1.5f)
                        } else {
                            val bow = bowedLink(at(a), at(b))
                            strand(col, f) { bow.at(it) }
                            if (inLine(l)) parentEnds(l)?.let { (c, _) -> paths.add { t -> bow.at(if (c == l.fromUid) t else 1f - t) } }
                        }
                        if (isDeedLink(l) && inLine(l)) deedCurve(at(a), at(b), deedK[l.uid] ?: 0).let { cv -> paths.add { t -> cv.at(1f - t) } } // up from the made to its maker
                    }
                    // children hang from their parents' ring: one soft curve per child, down from the ring and down into the child
                    family.entries.groupBy({ it.value }, { it.key }).forEach { (m, kids) ->
                        val a = index[m.fromUid] ?: return@forEach
                        val b = index[m.toUid] ?: return@forEach
                        val ci = kids.mapNotNull { index[it] }.ifEmpty { return@forEach }
                        val ring = (at(a) + at(b)) / 2f
                        val below = ring.y + 9f + labels[links.indexOf(m)].size.height / density + 3f // under the word "married"
                        for (i in ci.sortedBy { cards[it].uid in line || cards[it].uid == lit }) { // lit ones last, over the rest
                            val top = Offset(at(i).x, boxes[i][1])
                            val cv = childCurve(Offset(ring.x, below.coerceAtMost(top.y - 24f)), top)
                            val on = cards[i].uid in line || cards[i].uid == lit
                            strand(if (on || touches(m, lit)) Color.White else VeinInk, 1f) { cv.at(it) }
                            if (on) paths.add { t -> cv.at(1f - t) }
                        }
                    }
                    if (selected != null) paths.forEachIndexed { j, p -> for (s in 0..1) drawCircle(Color.White, 1.6f, p(((sc / 3.75f + j * 0.37f + s * 0.5f) % 1f))) }
                    cards.forEachIndexed { i, c ->
                        val pk = fx.pop(c.uid, now)
                        val hot = c.uid == selected || c.uid == linkFrom || c.uid in ringCards
                        val picked = c.uid == linkFrom || c.uid in ringCards
                        val kin = !hot && c.uid in line
                        if (pk >= 1f) drawCard(boxes[i], c.kind == BIBLE_THING, c.locked, hot, picked, kin)
                        else withTransform({ scale(pk, pk, at(i)) }) { drawCard(boxes[i], c.kind == BIBLE_THING, c.locked, hot, picked, kin) }
                        if (pk >= 1f && hasMoments(c, ka)) drawStar(Offset(boxes[i][2] - if (c.locked) 21f else 1f, boxes[i][1] - 1f), 1.8f, Color(0xFFE6DCFF), true) // beside the lock when locked
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
                cards.indices.forEach { i -> fx.pop(cards[i].uid, now).let { pk -> text(names[i], at(i), appearAlpha(pk) * nameDim[i], pk) } }
                for (g in fx.ghosts) text(g.name, g.at, (1f - (now - g.t0) / BibleFx.GHOST_S).coerceIn(0f, 1f))
                // the labels over every line and card edge, in a small dark pill
                withTransform({ translate(size.width / 2f - camX * k, size.height / 2f - camY * k); scale(k, k, Offset.Zero) }) {
                    links.forEachIndexed { i, l ->
                        val q = pills[i]
                        if (q[2] < q[0] || fx.line(l.uid, now) < 1f) return@forEachIndexed // the label comes once the line has arrived (hung links have none)
                        if (isMarriage(l.label)) {
                            val c = mids[i] ?: return@forEachIndexed
                            val ink = if (touches(l, lit) || inLine(l)) Color.White else Ink
                            drawCircle(Night, 6f, c); drawCircle(ink, 6f, c, style = Stroke(1.5f))
                            return@forEachIndexed
                        }
                        if (isDeedLink(l)) return@forEachIndexed // a deed's label is bare text
                        drawRoundRect(Night, Offset(q[0], q[1]), Size(q[2] - q[0], q[3] - q[1]), CornerRadius(5f))
                        drawRoundRect(if (touches(l, lit)) Color.White else Px.Border, Offset(q[0], q[1]), Size(q[2] - q[0], q[3] - q[1]), CornerRadius(5f), style = Stroke(1f))
                    }
                }
                links.forEachIndexed { i, l -> mids[i]?.takeIf { fx.line(l.uid, now) >= 1f && !hung(l) }?.let { text(labels[i], if (isMarriage(l.label)) it + Offset(0f, 9f + labels[i].size.height / density / 2f) else it,(if (lit == null || touches(l, lit)) 1f else 0.6f) * labelDim[i]) } }
                if (cards.isEmpty() && pending == null) {
                    val tl: TextLayoutResult = measurer.measure("TAP EMPTY SPACE TO ADD A CARD", TextStyle(fontFamily = pixelFont, fontSize = 8.sp, color = Px.TextDim, textAlign = TextAlign.Center),
                        constraints = Constraints(maxWidth = (size.width - 32f * density).toInt().coerceAtLeast(1)))
                    drawText(tl, topLeft = Offset(size.width / 2f - tl.size.width / 2f, size.height / 2f - tl.size.height / 2f))
                }
            }

            // the info strip: the shown card's links in words and its note; or what the mode wants next
            val strip: Pair<String, String>? = when {
                selectedCard != null && ka -> bibleWordsKa(selectedCard, cards, links) to selectedCard.noteKa.ifBlank { selectedCard.note }
                selectedCard != null -> bibleWords(selectedCard, cards, links) to selectedCard.note
                mode == BibleMode.Link -> (if (ringFrom != null) "now tap their child" else if (linkFrom == null) "tap a card, then the card it links to, or a ring, then their child" else "now tap the card it links to") to ""
                mode == BibleMode.Lock -> "tap a card to lock or unlock it" to ""
                else -> null
            }
            strip?.let { (words, note) ->
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp).onSizeChanged { stripH = it.height }.clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0D0618)).border(1.5.dp, Lilac, RoundedCornerShape(10.dp))
                        .pointerInput(selectedCard?.uid) { detectTapGestures { selectedCard?.let { paging = it.uid } } } // taps on the strip never reach the canvas; on a card's it opens the page
                        .heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(words, Modifier.semantics { contentDescription = words }, style = TextStyle(fontFamily = pageFont, fontSize = 20.sp, lineHeight = 21.sp, color = Ink))
                    if (note.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(note, style = TextStyle(fontFamily = pageFont, fontSize = 18.sp, lineHeight = 19.sp, color = if (ka && selectedCard?.noteKa.isNullOrBlank()) Px.TextDim.copy(alpha = KA_MISSING) else Px.TextDim))
                    }
                }
            }

            Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp + (if (strip != null) (stripH / density + 20f).dp else 0.dp)).clip(RoundedCornerShape(50)).background(Night)) {
                ModeChip("READ", false) { reader = 1 to 0 }
            }
            cardMenu?.let { (c, p) ->
                BrainMenu(p, listOf(
                    "Rename" to { renaming = c },
                    "Page" to { paging = c.uid },
                    "Delete" to { burning = c },
                ), { cardMenu = null }, clearW = menuClear.width.dp, clearH = menuClear.height.dp)
            }
            linkMenu?.let { (l, p) -> BrainMenu(p, listOf("Rename" to { relabeling = l }, "Delete" to { onDeleteLink(l) }), { linkMenu = null }) }
            if (searching) {
                val fm = LocalFocusManager.current
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                val found = bibleFind(query, cards)
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp).pointerInput(Unit) { detectTapGestures { } }) {
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 16.sp, color = Ink), cursorBrush = SolidColor(Lilac),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0D0618)).border(1.dp, Lilac, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                                if (query.isEmpty()) Text(if (ka) "სახელის ძებნა" else "Find a name", style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 16.sp, color = Color(0xFF8E7FB0)))
                                inner()
                            }
                        },
                    )
                    if (found.isNotEmpty()) Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0D0618)).border(1.dp, Px.Border, RoundedCornerShape(8.dp))) {
                        for (c in found) {
                            val hint = parentsOf(c, cards, links).mapNotNull { u -> cards.firstOrNull { it.uid == u } }.joinToString(", ") { shownName(it, ka).first }
                            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                fm.clearFocus(); searching = false; query = ""
                                selected = c.uid; linkFrom = null; ringFrom = null; glide = Offset(c.x, c.y)
                            }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(shownName(c, ka).first, style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 16.sp, color = Ink))
                                if (hint.isNotEmpty()) Text(if (ka) "მშობელი: $hint" else "child of $hint", style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 12.sp, color = Color(0xFF8E7FB0)))
                            }
                        }
                    }
                }
            }
        }
    }
    cards.firstOrNull { it.uid == paging }?.let { c ->
        BiblePage(
            c, cards, links, ka, onBack = { paging = null }, onVerse = { ch, v -> reader = ch to v },
            onFacts = { t -> if (ka) onNoteKa(c, t) else onNote(c, t) }, onThoughts = { t -> if (ka) onThoughtsKa(c, t) else onThoughts(c, t) },
            onMoments = { t -> if (ka) onMomentsKa(c, t) else onMoments(c, t) },
        )
    }
    reader?.let { start ->
        BibleReader(cards, ka, kaText, start, onClose = { reader = null },
            onCard = { c -> reader = null; paging = c.uid },
            onAdd = { c, t, moment -> if (moment) { if (ka) onMomentsKa(c, t) else onMoments(c, t) } else if (ka) onNoteKa(c, t) else onNote(c, t) })
    }
    }

    pending?.let { p ->
        NameDialog("NEW CARD", "Name it: Abraham, the ark...", submit = "ADD", maxLen = BIBLE_NAME_MAX, onDismiss = { pending = null }, onSubmit = { onAdd(it.trim(), p.x, p.y) })
    }
    linking?.let { (a, b) ->
        NameDialog("LINK", "How are they related? e.g. son, wife, built", submit = "LINK", maxLen = BIBLE_LABEL_MAX, quick = listOf("married", "father of", "mother of", "brother", "sister", "killed"), onDismiss = { linking = null }, onSubmit = { onLink(a, b, it.trim()) })
    }
    adopting?.let { (m, child) ->
        NameDialog("CHILD", "son or daughter", submit = "ADD", maxLen = BIBLE_LABEL_MAX, quick = listOf("son", "daughter"), onDismiss = { adopting = null }, onSubmit = {
            val label = when (it.trim().lowercase()) { "son", "son of" -> "son of"; "daughter", "daughter of" -> "daughter of"; else -> "child of" }
            onLinks(listOf(m.fromUid, m.toUid).filter { s -> s != child && links.none { l -> l.fromUid == child && l.toUid == s && l.label.trim().equals(label, true) } }.map { s -> Triple(child, s, label) })
        })
    }
    renaming?.let { c ->
        NameDialog("RENAME", "Name it", initial = if (ka) kaName(c) else c.name, submit = "RENAME", maxLen = BIBLE_NAME_MAX, onDismiss = { renaming = null }, onSubmit = { if (ka) onNameKa(c, it) else onRename(c, it) })
    }
    relabeling?.let { l ->
        NameDialog("RENAME LINK", "How are they related?", initial = if (ka) kaLabel(l) else l.label, submit = "RENAME", maxLen = BIBLE_LABEL_MAX, onDismiss = { relabeling = null }, onSubmit = { if (ka) onLabelKa(l, it) else onRenameLink(l, it) })
    }
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

/** The search chip: a 7x7 pixel magnifier drawn in code, lit when on. */
@Composable
private fun FindChip(on: Boolean, onClick: () -> Unit) {
    val pill = RoundedCornerShape(50)
    val ink = if (on) Color.White else Px.TextDim
    Box(
        Modifier.clip(pill).background(if (on) Color(0xFF1A1030) else Color.Transparent).border(1.dp, if (on) Px.Lavender else Px.Border, pill)
            .semantics { contentDescription = "Find a name" + if (on) ", on" else "" }
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Canvas(Modifier.size(11.dp)) {
            val u = size.width / 7f
            for ((x, y) in listOf(1 to 0, 2 to 0, 3 to 0, 0 to 1, 4 to 1, 0 to 2, 4 to 2, 0 to 3, 4 to 3, 1 to 4, 2 to 4, 3 to 4, 5 to 5, 6 to 6))
                drawRect(ink, Offset(x * u, y * u), Size(u, u))
        }
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
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        maxLines = 1, softWrap = false,
        style = TextStyle(fontFamily = PixelFont, fontSize = 7.sp, color = if (on) Color.White else Px.TextDim),
    )
}

/** A character's page: name, relations in words, then the Bible facts and the user's own thoughts, each tap-to-edit; verse references are tappable. */
@Composable
private fun BiblePage(
    card: BibleCard, cards: List<BibleCard>, links: List<BibleLink>, ka: Boolean, onBack: () -> Unit,
    onFacts: (String) -> Unit, onThoughts: (String) -> Unit, onMoments: (String) -> Unit, onVerse: (Int, Int) -> Unit,
) {
    BackHandler(true, onBack)
    val (title, titleDim) = shownName(card, ka)
    val relations = if (ka) bibleWordsKa(card, cards, links).removePrefix("$title — ") else bibleWords(card, cards, links).removePrefix("${card.name}: ")
    val fm = LocalFocusManager.current
    val touched = remember { BooleanArray(1) } // set by the field being edited during a press, so only presses outside it end the edit
    Column(Modifier.fillMaxSize().background(Night).pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touched[0] = false
            awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() } // children have seen it; also keeps the canvas behind from getting it
            if (!touched[0]) fm.clearFocus()
        }
    }) {
        Row(Modifier.fillMaxWidth().height(HEADER_H - 2.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("< TREE", Modifier.semantics { contentDescription = "Back to the tree" }.clickable(onClick = onBack).padding(end = 14.dp, top = 8.dp, bottom = 8.dp),
                style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender))
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(title, style = TextStyle(fontFamily = PageFont, fontSize = 36.sp, lineHeight = 38.sp, color = Color.White.copy(alpha = titleDim)))
                Spacer(Modifier.height(4.dp))
                Text(relations, style = TextStyle(fontFamily = PageFont, fontSize = 19.sp, lineHeight = 20.sp, color = Px.TextDim))
                Spacer(Modifier.height(22.dp))
                PageSection("FACTS", if (ka) card.noteKa else card.note, if (ka) card.note else "", "What does the Bible say about them?", false, onFacts, onVerse) { touched[0] = true }
                Spacer(Modifier.height(22.dp))
                PageSection("BIG MOMENTS", if (ka) card.momentsKa else card.moments, if (ka) card.moments else "", "Mark what mattered most, one per line", false, onMoments, onVerse, star = true) { touched[0] = true }
                Spacer(Modifier.height(22.dp))
                PageSection("MY THOUGHTS", if (ka) card.thoughtsKa else card.thoughts, if (ka) card.thoughts else "", "What do you think about it?", true, onThoughts, onVerse) { touched[0] = true }
            }
        }
    }
}

/** One page section: its label, then [text] (tap to edit, saved on leaving the field or closing the page); [english] shows dimmed under the hint while a Georgian one is empty. */
@Composable
private fun PageSection(label: String, text: String, english: String, hint: String, bar: Boolean, onSave: (String) -> Unit, onVerse: (Int, Int) -> Unit, star: Boolean = false, onTouch: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val saveNow by rememberUpdatedState { onSave(draft.trim()) }
    val editingNow by rememberUpdatedState(editing)
    DisposableEffect(Unit) { onDispose { if (editingNow) saveNow() } } // the page closed mid-edit
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }
    Text(label, style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, letterSpacing = 1.sp, color = Lilac))
    Spacer(Modifier.height(8.dp))
    if (editing) {
        PixelField(
            draft, { draft = it.take(BIBLE_NOTE_MAX) }, hint, singleLine = false, minHeight = 84.dp, cursor = Lilac,
            modifier = Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); onTouch() } }.focusRequester(focus).onFocusChanged { f ->
                if (f.isFocused) focused = true else if (focused) { saveNow(); editing = false; focused = false }
            },
        )
        return
    }
    val refs = verseRefs(text)
    val shown = remember(text) {
        buildAnnotatedString {
            append(text)
            for (r in refs) addStyle(SpanStyle(color = Lilac, textDecoration = TextDecoration.Underline), r.first, r.last + 1)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val body = Modifier.fillMaxWidth().heightIn(min = 36.dp).let { if (bar) it.drawBehind { drawRect(Lilac, Offset.Zero, Size(2.dp.toPx(), size.height)) }.padding(start = 12.dp) else it }
    Column(body.pointerInput(text) {
        detectTapGestures { p ->
            val o = layout?.getOffsetForPosition(p)
            val r = refs.firstOrNull { o != null && o in it }
            if (r != null) verseOf(text.substring(r.first, r.last + 1))?.let { (c, v) -> onVerse(c, v) } else { draft = text; editing = true }
        }
    }) {
        if (text.isBlank()) {
            Text(hint, style = TextStyle(fontFamily = PageFont, fontSize = 19.sp, lineHeight = 20.sp, color = Px.TextDim.copy(alpha = 0.55f)))
            if (english.isNotBlank()) Text(english, style = TextStyle(fontFamily = PageFont, fontSize = 20.sp, lineHeight = 21.sp, color = Px.TextDim.copy(alpha = KA_MISSING)))
        } else if (star) for (line in text.lines().filter { it.isNotBlank() }) MomentLine(line, onVerse) { draft = text; editing = true }
        else Text(shown, onTextLayout = { layout = it }, style = TextStyle(fontFamily = PageFont, fontSize = 21.sp, lineHeight = 22.sp, color = Ink))
    }
}

/** One big moment: a pixel star, then the line with its verse refs underlined; a tap on a ref opens the verse, anywhere else edits. */
@Composable
private fun MomentLine(line: String, onVerse: (Int, Int) -> Unit, onEdit: () -> Unit) {
    val refs = remember(line) { verseRefs(line) }
    val shown = remember(line) { buildAnnotatedString { append(line); for (r in refs) addStyle(SpanStyle(color = Lilac, textDecoration = TextDecoration.Underline), r.first, r.last + 1) } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).pointerInput(line) {
        detectTapGestures { p ->
            val o = layout?.getOffsetForPosition(Offset(p.x - 23.dp.toPx(), p.y))
            val r = refs.firstOrNull { o != null && p.x >= 23.dp.toPx() && o in it }
            if (r != null) verseOf(line.substring(r.first, r.last + 1))?.let { (c, v) -> onVerse(c, v) } else onEdit()
        }
    }) {
        Canvas(Modifier.padding(top = 4.dp, end = 8.dp).size(15.dp)) { drawStar(Offset(size.width / 2f, size.height / 2f), size.width / 7f, Lilac, false) }
        Text(shown, Modifier.weight(1f), onTextLayout = { layout = it }, style = TextStyle(fontFamily = PageFont, fontSize = 21.sp, lineHeight = 22.sp, color = Ink))
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

/** A curve of little square pixels: [at] sampled over 0..[upTo]. */
private fun DrawScope.strand(c: Color, upTo: Float, at: (Float) -> Offset) {
    val n = 24; var prev = at(0f)
    for (i in 1..n) { val q = at(upTo * i / n); pixelLine(prev, q, c, 1.2f); prev = q }
}

/** A straight line of little square pixels from [a] to [b]. */
private fun DrawScope.pixelLine(a: Offset, b: Offset, c: Color, px: Float) {
    val n = ((b - a).getDistance() / px).roundToInt().coerceIn(1, 4000)
    for (i in 0..n) {
        val p = a + (b - a) * (i / n.toFloat())
        drawRect(c, Offset((p.x / px).roundToInt() * px - px / 2f, (p.y / px).roundToInt() * px - px / 2f), Size(px, px))
    }
}

/** A deed's dashed curve (4 on, 3 off) from card box [boxA] to [boxB], the first [f] of it drawn, with an arrowhead at the end once whole. */
private fun DrawScope.deedStroke(cv: DeedCurve, boxA: FloatArray, boxB: FloatArray, f: Float, c: Color, px: Float) {
    fun inside(q: FloatArray, p: Offset) = p.x >= q[0] && p.x <= q[2] && p.y >= q[1] && p.y <= q[3]
    val n = 48
    var i0 = 0; while (i0 < n && inside(boxA, cv.at(i0 / n.toFloat()))) i0++
    var i1 = n; while (i1 > i0 && inside(boxB, cv.at(i1 / n.toFloat()))) i1--
    if (i1 <= i0) return
    val t0 = i0 / n.toFloat(); val t1 = i1 / n.toFloat()
    val m = 40; val te = t0 + (t1 - t0) * f
    val pts = List(m + 1) { cv.at(t0 + (te - t0) * it / m) }
    val cum = FloatArray(m + 1); for (i in 1..m) cum[i] = cum[i - 1] + (pts[i] - pts[i - 1]).getDistance()
    fun along(s: Float): Offset { val i = (1..m).firstOrNull { cum[it] >= s } ?: m; val seg = cum[i] - cum[i - 1]; return if (seg == 0f) pts[i] else pts[i - 1] + (pts[i] - pts[i - 1]) * ((s - cum[i - 1]) / seg) }
    var s = 0f
    while (s < cum[m]) { pixelLine(along(s), along(minOf(s + 4f, cum[m])), c, px); s += 7f }
    if (f < 1f) return
    val t = cv.tangent(t1); val len = t.getDistance(); if (len == 0f) return
    val back = t / len * -6f
    for (r in floatArrayOf(0.5f, -0.5f)) {
        val cs = kotlin.math.cos(r); val sn = kotlin.math.sin(r)
        pixelLine(pts[m], pts[m] + Offset(back.x * cs - back.y * sn, back.x * sn + back.y * cs), c, px)
    }
}

/** A card's body in [q]: a person a square-cut box, a thing a rounded dashed one; locked = a white border and a tiny lock; [hot] lit up. */
private val STAR_ROWS = listOf("...X...", "..XXX..", "XXXXXXX", ".XXXXX.", "..XXX..", ".XX.XX.", ".X...X.")

/** A 7x7 pixel star centred on [c], each pixel [u] wide, filled [fill], with a 1 px Night outline when [outline]. */
private fun DrawScope.drawStar(c: Offset, u: Float, fill: Color, outline: Boolean) {
    val o = c - Offset(3.5f * u, 3.5f * u)
    if (outline) STAR_ROWS.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == 'X') drawRect(Night, o + Offset(x * u - 1f, y * u - 1f), Size(u + 2f, u + 2f)) } }
    STAR_ROWS.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == 'X') drawRect(fill, o + Offset(x * u, y * u), Size(u, u)) } }
}

internal fun DrawScope.drawCard(q: FloatArray, thing: Boolean, locked: Boolean, hot: Boolean, picked: Boolean, lineage: Boolean = false) {
    val tl = Offset(q[0], q[1]); val sz = Size(q[2] - q[0], q[3] - q[1])
    val edge = if (locked || picked) Color.White else Lilac
    if (lineage) drawRect(Lilac.copy(alpha = 0.12f), tl - Offset(5f, 5f), Size(sz.width + 10f, sz.height + 10f))
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
