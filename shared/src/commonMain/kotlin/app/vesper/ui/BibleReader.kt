package app.vesper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.data.BibleCard
import app.vesper.res.Res
import kotlinx.coroutines.delay

// The Bible reader: Genesis (KJV) as a calm serif page over the canvas, with search, jump-to-verse, card names that open
// their pages, and long-press to copy a verse into a card's facts. Parsing and search are pure and tested in BibleReaderTest.

internal data class Verse(val chapter: Int, val verse: Int, val text: String)

/** `<chapter>:<verse>|<text>` lines; blank lines, `#` lines and malformed lines are skipped. */
internal fun parseBible(text: String): List<Verse> = text.lineSequence().map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { l ->
        val bar = l.indexOf('|')
        val ref = if (bar < 0) null else l.substring(0, bar).split(':')
        val c = ref?.getOrNull(0)?.trim()?.toIntOrNull(); val v = ref?.getOrNull(1)?.trim()?.toIntOrNull()
        if (ref?.size != 2 || c == null || v == null) null else Verse(c, v, l.substring(bar + 1).trim())
    }.toList()

/** Verses whose text holds [query] (trimmed, any case); an empty query finds nothing. */
internal fun searchVerses(verses: List<Verse>, query: String): List<Verse> {
    val q = query.trim()
    return if (q.isEmpty()) emptyList() else verses.filter { it.text.contains(q, ignoreCase = true) }
}

/** Where [query] (trimmed, any case) sits in [text]. */
internal fun matchRanges(text: String, query: String): List<IntRange> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var i = text.indexOf(q, 0, ignoreCase = true)
    while (i >= 0) { out += i until i + q.length; i = text.indexOf(q, i + q.length, ignoreCase = true) }
    return out
}

/** Whole-word, case-sensitive occurrences in [text] of any of [names]. shortcut: single-word names only, multi-word card names never match. */
internal fun nameSpans(text: String, names: Set<String>): List<IntRange> =
    Regex("[A-Za-z]+").findAll(text).filter { it.value in names }.map { it.range }.toList()

/** English name spans, each keyed by the name it spells. */
internal fun enNameSpans(text: String, names: Set<String>): Map<IntRange, String> = nameSpans(text, names).associateWith { text.substring(it.first, it.last + 1) }

/** A Georgian name's stem: inflected forms (კაენმა, კაენს, აბელისა) share the name minus its final ი. */
internal fun kaStem(name: String) = name.trim().removeSuffix("ი")

/**
 * Georgian words that start with one of [stems], each keyed by its longest matching stem (ადამს is ადამ, not ადა).
 * shortcut: single-word names only, like [nameSpans].
 */
internal fun kaNameSpans(text: String, stems: Set<String>): Map<IntRange, String> =
    Regex("[ა-ჿ]+").findAll(text).mapNotNull { w -> stems.filter { w.value.startsWith(it) }.maxByOrNull { it.length }?.let { w.range to it } }.toMap()

/** Per verse text, the spans that are the first occurrence of their name (the key [spans] gives) in the whole run, a chapter: only those get styled. */
internal fun firstKeyed(texts: List<String>, spans: (String) -> Map<IntRange, String>): List<Set<IntRange>> {
    val seen = HashSet<String>()
    return texts.map { t -> spans(t).filter { seen.add(it.value) }.keys }
}

internal fun firstSpans(texts: List<String>, names: Set<String>): List<Set<IntRange>> = firstKeyed(texts) { enNameSpans(it, names) }

/** KJV spellings that differ from the card name. */
private val BIBLE_ALIAS = mapOf("Eva" to "Eve")

private const val BIBLE_FILE = "files/bible/genesis_kjv.txt"
private val ReaderBg = Color(0xFF120A1C)
private val ReaderInk = Color(0xFFE6DCFF)
private val ReaderLilac = Color(0xFFB69AFF)
private val ReaderDim = Color(0xFF8E7FB0)
private val ReaderOpen = Color(0xFF22113D)
private val Serif = FontFamily.Serif

@Composable
internal fun BibleReader(
    cards: List<BibleCard>, ka: Boolean, kaText: suspend () -> String?, start: Pair<Int, Int>, onClose: () -> Unit,
    onCard: (BibleCard) -> Unit, onAdd: (BibleCard, String, Boolean) -> Unit,
) {
    BackHandler(true, onClose)
    var verses by remember { mutableStateOf(emptyList<Verse>()) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { parseBible(Res.readBytes(BIBLE_FILE).decodeToString()) }.onSuccess { verses = it }.onFailure { failed = true }
    }
    // Georgian text is the user's own copy, never in the app: null = not on this device (yet).
    var kaVerses by remember { mutableStateOf<List<Verse>?>(null) }
    var kaChecked by remember { mutableStateOf(false) }
    LaunchedEffect(ka) { if (ka && !kaChecked) { kaVerses = runCatching { kaText()?.let(::parseBible) }.getOrNull()?.takeIf { it.isNotEmpty() }; kaChecked = true } }
    val georgian = ka && kaVerses != null
    val shownVerses = if (georgian) kaVerses.orEmpty() else verses
    val english = remember(verses) { verses.associate { (it.chapter to it.verse) to it.text } }
    var opened by remember { mutableStateOf(emptySet<Pair<Int, Int>>()) }
    var chapter by remember { mutableStateOf(start.first) }
    var hl by remember { mutableStateOf(start.second.takeIf { it > 0 }?.let { start.first to it }) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<Verse?>(null) }
    var full by remember { mutableStateOf(false) }
    var asMoment by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val last = remember(shownVerses) { shownVerses.maxOfOrNull { it.chapter } ?: 1 }
    val inChapter = remember(shownVerses, chapter) { shownVerses.filter { it.chapter == chapter } }
    val results = remember(shownVerses, query) { searchVerses(shownVerses, query) }
    val names = remember(cards, georgian) {
        buildMap {
            if (georgian) for (c in cards) kaStem(kaName(c)).takeIf { it.length >= 2 }?.let { put(it, c) }
            else for (c in cards) { put(c.name, c); BIBLE_ALIAS[c.name]?.let { put(it, c) } }
        }
    }
    val spansOf: (String) -> Map<IntRange, String> = remember(names, georgian) { if (georgian) ({ t -> kaNameSpans(t, names.keys) }) else ({ t -> enNameSpans(t, names.keys) }) }
    val firsts = remember(inChapter, spansOf) { firstKeyed(inChapter.map { it.text }, spansOf).withIndex().associate { (i, f) -> inChapter[i].verse to f } }
    val list = rememberLazyListState()
    val glow = remember { Animatable(0f) }
    LaunchedEffect(hl) { if (hl != null) { glow.snapTo(1f); delay(500); glow.animateTo(0f, tween(1500)) } }
    LaunchedEffect(chapter, hl, inChapter.size, query.isEmpty()) {
        if (query.isEmpty()) list.scrollToItem(inChapter.indexOfFirst { hl == it.chapter to it.verse }.coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize().background(ReaderBg).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(HEADER_H - 2.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("< TREE", Modifier.semantics { contentDescription = "Back to the tree" }.clickable(onClick = onClose).padding(end = 14.dp, top = 8.dp, bottom = 8.dp),
                    style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = Px.Lavender))
                Text(if (georgian) "დაბადება $chapter" else "Genesis $chapter", Modifier.weight(1f).semantics { contentDescription = "Pick a chapter" }.clickable { picker = true }.padding(vertical = 8.dp),
                    style = TextStyle(fontFamily = Serif, fontSize = 22.sp, color = ReaderInk))
                for ((label, to) in listOf("<" to chapter - 1, ">" to chapter + 1)) {
                    val ok = to in 1..last
                    Text(label, Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).border(1.dp, if (ok) ReaderLilac else Px.Border, RoundedCornerShape(50))
                        .semantics { contentDescription = if (to < chapter) "Previous chapter" else "Next chapter" }
                        .clickable(enabled = ok) { chapter = to; hl = null; query = "" }.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, color = if (ok) Color.White else Px.TextDim.copy(alpha = 0.4f)))
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
            if (ka && kaChecked && !georgian) Text("ქართული ტექსტი ჯერ არ არის დამატებული", Modifier.padding(horizontal = 18.dp, vertical = 6.dp), style = TextStyle(fontFamily = Serif, fontSize = 14.sp, color = ReaderDim))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(fontFamily = Serif, fontSize = 16.sp, color = ReaderInk), cursorBrush = SolidColor(ReaderLilac),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, Px.Border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                                if (query.isEmpty()) Text(if (failed) "Could not load the text" else if (ka) "ძებნა" else "Search the text",style = TextStyle(fontFamily = Serif, fontSize = 16.sp, color = ReaderDim))
                                inner()
                            }
                        },
                    )
                    if (query.isBlank()) LazyColumn(Modifier.fillMaxSize(), list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                        items(inChapter, key = { it.verse }) { v ->
                            val tint = if (hl == v.chapter to v.verse) glow.value * 0.28f else 0f
                            val key = v.chapter to v.verse
                            VerseText(v, names, spansOf, firsts[v.verse].orEmpty(), tint, onCard, onHold = { menu = v; full = false },
                                en = if (georgian && key in opened) english[key] else null, onTap = if (georgian) ({ opened = if (key in opened) opened - key else opened + key }) else null)
                        }
                    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
                        if (results.isEmpty()) item { Text(if (ka) "ვერ მოიძებნა" else "Nothing found",Modifier.padding(vertical = 10.dp), style = TextStyle(fontFamily = Serif, fontSize = 16.sp, color = ReaderDim)) }
                        items(results, key = { it.chapter * 1000 + it.verse }) { v ->
                            val text = "Gen ${v.chapter}:${v.verse} — "
                            val shown = remember(v, query) {
                                buildAnnotatedString {
                                    append(text); append(v.text)
                                    for (r in matchRanges(v.text, query)) addStyle(SpanStyle(background = ReaderLilac.copy(alpha = 0.45f), color = Color.White), text.length + r.first, text.length + r.last + 1)
                                }
                            }
                            Text(shown, Modifier.fillMaxWidth().clickable { chapter = v.chapter; hl = v.chapter to v.verse; query = "" }.padding(vertical = 9.dp),
                                style = TextStyle(fontFamily = Serif, fontSize = 16.sp, lineHeight = 23.sp, color = ReaderInk))
                        }
                    }
                }
            }
        }
        if (picker) Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { picker = false }, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(20.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0D0618)).border(1.5.dp, ReaderLilac, RoundedCornerShape(10.dp))
                .clickable(enabled = false) {}.padding(12.dp)) {
                Text("GENESIS", Modifier.padding(bottom = 8.dp), style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, letterSpacing = 1.sp, color = ReaderLilac))
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((1..last).chunked(8)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in row) {
                                val on = c == chapter
                                Text("$c", Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (on) Color(0xFF2A1450) else Color.Transparent)
                                    .border(1.dp, if (on) ReaderLilac else Px.Border, RoundedCornerShape(6.dp))
                                    .semantics { contentDescription = "Chapter $c" }
                                    .clickable { chapter = c; hl = null; query = ""; picker = false }.padding(vertical = 9.dp),
                                    textAlign = TextAlign.Center, style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = if (on) Color.White else Px.TextDim))
                            }
                            repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        menu?.let { v ->
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { menu = null }, contentAlignment = Alignment.Center) {
                Column(Modifier.padding(28.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0D0618)).border(1.5.dp, ReaderLilac, RoundedCornerShape(10.dp))
                    .clickable(enabled = false) {}.padding(14.dp)) {
                    Text("ADD GEN ${v.chapter}:${v.verse} TO", style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, letterSpacing = 1.sp, color = ReaderLilac))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((label, m) in listOf("FACT" to false, "★ MOMENT" to true)) {
                            val on = asMoment == m
                            Text(label, Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(0xFF2A1450) else Color.Transparent).border(1.dp, if (on) ReaderLilac else Px.Border, RoundedCornerShape(50))
                                .clickable { asMoment = m; full = false }.padding(horizontal = 12.dp, vertical = 7.dp),
                                style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = if (on) Color.White else Px.TextDim))
                        }
                    }
                    if (full) Text("That page is full", Modifier.padding(top = 8.dp), style = TextStyle(fontFamily = Serif, fontSize = 14.sp, color = ReaderDim))
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (cards.isEmpty()) item { Text("No cards yet", style = TextStyle(fontFamily = Serif, fontSize = 16.sp, color = ReaderDim)) }
                        items(cards.sortedBy { it.name.lowercase() }, key = { it.uid }) { c ->
                            Text(shownName(c, ka).first, Modifier.fillMaxWidth().clickable {
                                val base = (if (asMoment) (if (ka) c.momentsKa else c.moments) else if (ka) c.noteKa else c.note).trimEnd()
                                val next = "$base\nGen ${v.chapter}:${v.verse} — ${v.text}".trim()
                                if (next.length <= BIBLE_NOTE_MAX) { onAdd(c, next, asMoment); menu = null } else full = true
                            }.padding(vertical = 10.dp), style = TextStyle(fontFamily = Serif, fontSize = 18.sp, color = ReaderInk))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerseText(
    v: Verse, names: Map<String, BibleCard>, spansOf: (String) -> Map<IntRange, String>, first: Set<IntRange>, tint: Float,
    onName: (BibleCard) -> Unit, onHold: () -> Unit, en: String? = null, onTap: (() -> Unit)? = null,
) {
    val prefix = "${v.verse} "
    val spans = remember(v, spansOf) { spansOf(v.text) }
    val shown = remember(v, names, first) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = 11.sp, color = ReaderDim, baselineShift = BaselineShift.Superscript)) { append(prefix) }
            append(v.text)
            for (r in spans.keys) if (r in first) addStyle(SpanStyle(color = ReaderLilac, textDecoration = TextDecoration.Underline), prefix.length + r.first, prefix.length + r.last + 1)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap by rememberUpdatedState(onTap)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(if (en != null) ReaderOpen else Color.Transparent).background(ReaderLilac.copy(alpha = tint))) {
        Text(
            shown, onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().pointerInput(v, spans) {
                detectTapGestures(onLongPress = { onHold() }) { p ->
                    val o = (layout?.getOffsetForPosition(p) ?: return@detectTapGestures) - prefix.length
                    // A name wins; anywhere else a tap shows or hides the English verse (Georgian mode only).
                    spans.entries.firstOrNull { o in it.key }?.let { names[it.value]?.let(onName) } ?: tap?.invoke()
                }
            }.padding(vertical = 6.dp),
            style = TextStyle(fontFamily = Serif, fontSize = 18.sp, lineHeight = 27.sp, color = ReaderInk),
        )
        if (en != null) Text(en, Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { tap?.invoke() } }.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            style = TextStyle(fontFamily = Serif, fontSize = 12.5.sp, lineHeight = 18.sp, color = ReaderDim))
    }
}
