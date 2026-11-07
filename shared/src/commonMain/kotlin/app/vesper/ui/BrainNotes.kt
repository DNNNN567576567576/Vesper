package app.vesper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.data.Branch
import app.vesper.data.Idea
import app.vesper.data.Lobe
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Pure helpers (tested in BrainNotesTest)
// ---------------------------------------------------------------------------------------------

/** Text handed to Vesper by the share sheet. */
class SharedText(val text: String, val subject: String?)

internal class ParsedShare(val title: String, val body: String, val link: String?)

private val URL_ONLY = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

/** First line is the title, the whole text the body; a text that is nothing but a web address is also the link (and takes the page title the sender gave, if any). */
internal fun parseShare(text: String, subject: String? = null): ParsedShare {
    val t = text.trim()
    val url = URL_ONLY.matches(t)
    val first = t.lineSequence().firstOrNull().orEmpty().trim()
    return ParsedShare(if (url) subject?.trim().orEmpty().ifEmpty { first } else first, t, if (url) t else null)
}

/** Rings around a neuron's core by how much is written: no body 0, up to 100 characters 1, up to 400 2, more 3. */
internal fun ringCount(title: String, body: String): Int {
    if (body.isBlank()) return 0
    val n = title.length + body.length
    return if (n <= 100) 1 else if (n <= 400) 2 else 3
}

/** Items touched in the last week glow magenta. */
internal const val RECENT_MS = 7 * 86_400_000L

/** Every item whose title or body holds [query], ignoring case (Georgian capitals too); nothing for a blank query. */
internal fun searchIdeas(ideas: List<Idea>, query: String): List<Idea> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return ideas.filter { it.title.lowercase().contains(q) || it.body.lowercase().contains(q) }
}

/**
 * Boxes (x0, y0, x1, y1 in world units) that rot holes keep out of: each lobe with its name, each group with its name,
 * each item with its label, and the vault with its name.
 */
internal fun keepOutBoxes(lx: FloatArray, ly: FloatArray, bx: FloatArray, by: FloatArray, ix: FloatArray, iy: FloatArray, sunX: Float, sunY: Float): FloatArray {
    val out = ArrayList<Float>()
    fun box(x: Float, y: Float, l: Float, t: Float, r: Float, b: Float) { out.add(x - l); out.add(y - t); out.add(x + r); out.add(y + b) }
    for (i in lx.indices) box(lx[i], ly[i], 15f, 15f, 15f, 27f)
    for (i in bx.indices) box(bx[i], by[i], 12f, 9f, 12f, 14f)
    for (i in ix.indices) box(ix[i], iy[i], 5.5f, 3f, 5.5f, 8.5f)
    box(sunX, sunY, 14f, 15f, 14f, 27f)
    return out.toFloatArray()
}

/** Whether a hole of radius [r] (rim included) at ([x], [y]) stays [gap] clear of every box. */
internal fun holeClears(x: Float, y: Float, r: Float, boxes: FloatArray, gap: Float = 0f): Boolean {
    var i = 0
    while (i < boxes.size) {
        if (hypot(x - x.coerceIn(boxes[i], boxes[i + 2]), y - y.coerceIn(boxes[i + 1], boxes[i + 3])) < r + gap) return false
        i += 4
    }
    return true
}

/** Where a hole of radius [r] can sit once pushed clear of the [boxes] (always the same answer for the same input); null = no room, leave it out. */
internal fun clearHole(x: Float, y: Float, r: Float, boxes: FloatArray): FloatArray? {
    var px = x
    var py = y
    val rr = r + 2f
    repeat(8) {
        var moved = false
        var i = 0
        while (i < boxes.size) {
            val nx = px.coerceIn(boxes[i], boxes[i + 2]); val ny = py.coerceIn(boxes[i + 1], boxes[i + 3])
            val d = hypot(px - nx, py - ny)
            if (d < rr) {
                if (d < 1e-3f) { // the centre is inside the box: leave by the nearest side
                    val l = px - boxes[i]; val rt = boxes[i + 2] - px; val t = py - boxes[i + 1]; val b = boxes[i + 3] - py
                    when (min(min(l, rt), min(t, b))) {
                        l -> px = boxes[i] - rr
                        rt -> px = boxes[i + 2] + rr
                        t -> py = boxes[i + 1] - rr
                        else -> py = boxes[i + 3] + rr
                    }
                } else {
                    px = nx + (px - nx) / d * (rr + 0.1f); py = ny + (py - ny) / d * (rr + 0.1f)
                }
                moved = true
            }
            i += 4
        }
        if (!moved) return floatArrayOf(px, py)
    }
    return null
}

/**
 * Camera aim (x, y, zoom) that centres [pts] (world) in the part of a [w] x [h] view (low-res pixels) below [top], with room
 * around them for the node sprites and the names. Zoom is the largest that fits, within [minZ]..[maxZ].
 */
internal fun fitCloud(pts: List<Offset>, w: Float, h: Float, top: Float, minZ: Float, maxZ: Float): FloatArray {
    val x0 = pts.minOf { it.x }; val x1 = pts.maxOf { it.x }
    val y0 = pts.minOf { it.y }; val y1 = pts.maxOf { it.y }
    var z = 1f
    var mTop = 0f; var mBot = 0f
    repeat(4) { // the margins depend on the zoom (sprites grow with it, then stop), so settle them
        val k = min(z, 2f)
        val mx = 14f * k + 10f
        mTop = 15f * k + 4f; mBot = 15f * k + 22f
        z = min((w - 2f * mx) / max(1f, x1 - x0), (h - top - mTop - mBot) / max(1f, y1 - y0)).coerceIn(minZ, maxZ)
    }
    val cy = (y0 + y1) / 2f
    return floatArrayOf((x0 + x1) / 2f, cy - (top / 2f - (mBot - mTop) / 2f) / z, z)
}

// ---------------------------------------------------------------------------------------------
// Search + share picker
// ---------------------------------------------------------------------------------------------

/** Full-map search: type, see matching items with their sub brain, tap one to go to it. */
@Composable
internal fun SearchPanel(ideas: List<Idea>, lobes: List<Lobe>, onPick: (Idea) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val hits = remember(q, ideas) { searchIdeas(ideas, q).take(50) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(true, onClose)
    Column(Modifier.fillMaxSize().background(Px.Bg.copy(alpha = 0.96f)).imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PixelField(q, { q = it }, "Search every item", Modifier.weight(1f).focusRequester(focus))
            PixelButton("CLOSE", onClose)
        }
        Spacer(Modifier.height(14.dp))
        val dim = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim)
        when {
            q.isBlank() -> Text("Type to search titles and text.", style = dim)
            hits.isEmpty() -> Text("Nothing found.", style = dim)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits, key = { it.id }) { i ->
                    val lobe = lobes.firstOrNull { it.id == i.lobeId }
                    Column(
                        Modifier.fillMaxWidth().pixelCard(fill = Px.Elevated, shadow = false, notch = 2.dp)
                            .clickable(role = Role.Button) { onPick(i) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            ideaLabel(i).ifBlank { "..." }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = PageFont, fontSize = 19.sp, lineHeight = 22.sp, color = Px.Text),
                        )
                        Text(
                            lobe?.name.orEmpty().uppercase(), Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = lobe?.let { lobeColor(it) } ?: Px.TextDim),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shared text arrives here: pick the sub brain (and group) to keep it in. Only CANCEL throws it away, and the dialog says so;
 * a stray tap outside does nothing. With no sub brain yet it asks for a name for the first one.
 */
@Composable
internal fun SharePicker(
    share: SharedText,
    lobes: List<Lobe>,
    branches: List<Branch>,
    onPick: (lobeId: Long, branchId: Long?) -> Unit,
    onNewLobe: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val dim = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim)
    var name by remember { mutableStateOf("") }
    PixelDialog({}) {
        Text("KEEP THIS WHERE?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(10.dp))
        Text(parseShare(share.text, share.subject).title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, lineHeight = 20.sp, color = Px.Text))
        Spacer(Modifier.height(12.dp))
        if (lobes.isEmpty()) {
            Text("No sub brains yet. Name the first one to keep this in.", style = dim)
            Spacer(Modifier.height(10.dp))
            PixelField(name, { name = it.take(24) }, "Name it: Ideas, Coding...", onDone = { if (name.isNotBlank()) onNewLobe(name) })
        } else {
            LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lobes.forEach { l ->
                    item(key = "l${l.id}") { PickRow(l.name.uppercase(), lobeColor(l), 0) { onPick(l.id, null) } }
                    items(branches.filter { it.lobeId == l.id }.sortedBy { it.name }, key = { "b${it.id}" }) { b ->
                        PickRow(b.name.uppercase(), lobeColor(l), 18) { onPick(l.id, b.id) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("CANCEL throws this text away. Nothing is saved anywhere else.", style = dim)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            PixelButton("CANCEL", onCancel)
            if (lobes.isEmpty()) PixelButton("KEEP", { if (name.isNotBlank()) onNewLobe(name) }, primary = true)
        }
    }
}

@Composable
private fun PickRow(label: String, color: androidx.compose.ui.graphics.Color, indent: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).pixelCard(fill = Px.Bg, shadow = false, notch = 2.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(start = (12 + indent).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, color = color))
    }
}
