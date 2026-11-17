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
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
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

/** Items touched in the last 48 hours count as recent (Layout.irecent; no longer drawn differently). */
internal const val RECENT_MS = 48 * 3_600_000L

/** Every item whose title, body or link holds [query], ignoring case (Georgian capitals too); nothing for a blank query. */
internal fun searchIdeas(ideas: List<Idea>, query: String): List<Idea> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return ideas.filter { it.title.lowercase().contains(q) || it.body.lowercase().contains(q) || it.link.orEmpty().lowercase().contains(q) }
}

/** A search result: a sub brain, a group (with [branch]) or a note (with [idea]), named [label], living at [where]. */
internal class Hit(val kind: String, val label: String, val where: String, val lobe: Lobe, val branch: Branch? = null, val idea: Idea? = null)

/** The whole library: sub brains and groups by name, then notes by [searchIdeas], ignoring case; nothing for a blank query. */
internal fun searchLibrary(lobes: List<Lobe>, branches: List<Branch>, ideas: List<Idea>, query: String): List<Hit> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val lobeOf = lobes.associateBy { it.id }
    val branchOf = branches.associateBy { it.id }
    return lobes.filter { it.name.lowercase().contains(q) }.map { Hit("sub brain", it.name, "Brain", it) } +
        branches.mapNotNull { b -> lobeOf[b.lobeId]?.takeIf { b.name.lowercase().contains(q) }?.let { Hit("group", b.name, it.name, it, b) } } +
        searchIdeas(ideas, q).mapNotNull { i ->
            val l = lobeOf[i.lobeId] ?: return@mapNotNull null
            val b = i.branchId?.let { branchOf[it] }
            Hit("note", ideaLabel(i).ifBlank { "..." }, if (b != null) "${l.name} > ${b.name}" else l.name, l, b, i)
        }
}

internal const val PORTAL_ITEM_W = 120f // dp, a note's name at most
internal const val PORTAL_GROUP_W = 140f // dp, a group's name at most
internal const val NAME_PAD = 2f // dp of air counted around a name when placing it
internal const val STAR_R = 6f // dp, a star's reach (its twinkle), what names keep off
internal const val WORLD_ZOOM = 1f // a world is drawn at this zoom: one world unit, one low-res pixel

/** The view a world is laid out for, in dp: its size, the header over its top, dp per low-res pixel, and how tall the names get. */
internal class DimFrame(
    val w: Float = 411f, val h: Float = 700f, val top: Float = 60f, val pxdp: Float = 2.286f,
    val itemH: Float = 46f, val groupH: Float = 20f, val coreH: Float = 48f,
)

/**
 * A laid-out world. Positions are world units; the sub brain's name sits at the top, its box [coreName] (l, t, r, b, dp) from (0, 0).
 * Each group is a constellation: [groups] is the spot its name hangs from, [items] its stars. Loose notes come in one or more
 * unnamed constellations, [loose]. Name boxes are l, t, r, b in dp from their spot. [aim] is the camera (x, y, zoom), from the top.
 */
internal class Dimension(
    val groups: List<Offset>, val groupNames: List<FloatArray>,
    val loose: List<List<Offset>>, val looseNames: List<List<FloatArray>>,
    val items: List<List<Offset>>, val itemNames: List<List<FloatArray>>,
    val coreName: FloatArray, val aim: FloatArray,
    /** Per group: an empty one's ghost star (world) and its "empty" box (dp from it), else null. */
    val ghosts: List<Pair<Offset, FloatArray>?> = emptyList(),
)

/**
 * A world laid out for the view [f]: the name at the top, then constellations in columns. Columns come in pairs, stars near the
 * pair's middle and names on the outer side; a tall screen gets one pair (constellations alternate left and right), a wide one as
 * many as fit. Each constellation goes where its column ends highest, so both sides stay even; loose notes come first, split over
 * the columns when there are many. Stars swing a little either side of their column; every name box starts past the widest swing,
 * so no line between stars reaches a name, and boxes are sized for the largest name BrainLabels draws. A long page scrolls down.
 */
internal fun dimensionLayout(loose: Int, groups: List<Int>, f: DimFrame): Dimension {
    val m = 12f
    val zig = 14f; val gap = 24f // a star column sits [gap] from its pair's middle; stars swing up to [zig] off it
    val half = f.w / 2f - m
    // shortcut: below ~340 dp wide names are cut narrower than BrainLabels draws them; upgrade with a one-column layout if that ever matters
    val iw = min(PORTAL_ITEM_W + NAME_PAD, half - gap - zig - 10f)
    val reach = gap + zig + 10f + iw
    val gw = min(PORTAL_GROUP_W + NAME_PAD, reach - gap + 6f) // a group's name ends where its notes' names do
    val pairGap = 20f
    val rowStep = f.itemH + 8f
    val blockGap = 28f
    val parts = if (loose == 0) 0 else max(1, min((loose + 3) / 4, 2 * max(1, ((2f * half + pairGap) / (2f * reach + pairGap)).toInt())))
    val blocks = parts + groups.size
    val pairs = max(1, min(((2f * half + pairGap) / (2f * reach + pairGap)).toInt(), (blocks + 1) / 2))
    val px = FloatArray(pairs) { (it - (pairs - 1) / 2f) * (2f * reach + pairGap) }
    val cols = 2 * pairs
    // the middle pairs first, left before right
    val order = (0 until cols).sortedWith(compareBy({ abs(px[it / 2]) }, { it / 2 }, { it % 2 }))
    val coreName = floatArrayOf(-half, 0f, half, f.coreH)
    val bottom = FloatArray(cols) { f.coreH + 22f }
    val swing = floatArrayOf(0f, 1f, -0.5f, 0.6f, -1f, 0.3f, -0.8f)

    // one constellation of [n] stars in the column that ends highest; [head] = room for a group name above it
    // an empty group still takes one row: its ghost star
    fun place(n: Int, head: Float): Triple<Offset, List<Offset>, List<FloatArray>> {
        val c = order.minBy { bottom[it] }
        val s = c % 2
        val x = px[c / 2] + (if (s == 0) -gap else gap)
        val y0 = bottom[c]
        val stars = List(if (head > 0f) max(n, 1) else n) { j -> Offset(x + zig * swing[j % swing.size], y0 + head + f.itemH / 2f + j * rowStep) }
        val near = zig + 10f
        val names = stars.map { p ->
            if (s == 1) floatArrayOf(x + near - p.x, -f.itemH / 2f, x + near + iw - p.x, f.itemH / 2f)
            else floatArrayOf(x - near - iw - p.x, -f.itemH / 2f, x - near - p.x, f.itemH / 2f)
        }
        bottom[c] = y0 + head + stars.size * rowStep - (if (stars.isNotEmpty()) 8f else 0f) + blockGap
        return Triple(Offset(x, y0 + f.groupH / 2f), stars, names)
    }
    val looseParts = (0 until parts).map { p -> place(loose / parts + (if (p < loose % parts) 1 else 0), 0f) }
    val gs = groups.map { n -> place(n, f.groupH + 8f) }
    // a group's name starts just inside its star column and reads outward
    val gNames = gs.map { (at, _, _) ->
        if (at.x > px.minBy { abs(it - at.x) }) floatArrayOf(-6f, -f.groupH / 2f, gw - 6f, f.groupH / 2f) else floatArrayOf(6f - gw, -f.groupH / 2f, 6f, f.groupH / 2f)
    }

    // the camera: across, the middle; down, from the top
    val k = f.pxdp * WORLD_ZOOM
    val camY = -m - f.top + f.h / 2f
    fun w(o: Offset) = Offset(o.x / k, o.y / k)
    return Dimension(
        gs.map { w(it.first) }, gNames,
        looseParts.map { p -> p.second.map(::w) }, looseParts.map { it.third },
        gs.mapIndexed { g, p -> if (groups[g] == 0) emptyList() else p.second.map(::w) }, gs.mapIndexed { g, p -> if (groups[g] == 0) emptyList() else p.third },
        coreName, floatArrayOf(0f, camY / k, WORLD_ZOOM),
        gs.mapIndexed { g, p -> if (groups[g] == 0) w(p.second[0]) to p.third[0] else null },
    )
}

/** Whether the segment ([x1], [y1])-([x2], [y2]) crosses the box [l], [t], [r], [b] (Liang-Barsky). */
internal fun segmentHitsBox(x1: Float, y1: Float, x2: Float, y2: Float, l: Float, t: Float, r: Float, b: Float): Boolean {
    var t0 = 0f; var t1 = 1f
    fun clip(p: Float, q: Float): Boolean {
        if (p == 0f) return q >= 0f
        val u = q / p
        if (p < 0f) { if (u > t1) return false; if (u > t0) t0 = u } else { if (u < t0) return false; if (u < t1) t1 = u }
        return true
    }
    val dx = x2 - x1; val dy = y2 - y1
    return clip(-dx, x1 - l) && clip(dx, r - x1) && clip(-dy, y1 - t) && clip(dy, b - y1) && t0 <= t1
}

/** The heart's box around (SUN_X, SUN_Y), in world units: l, t, r, b. Names keep off it. */
internal val HEART_BOX = floatArrayOf(-14f, -34f, 14f, 3f)

/**
 * Where each lobe's name sits, as its top-left from the camera at the world origin, in the units of [sizes] ([k] of them per world unit),
 * null = no name (only a lobe with no name). Under its lobe if that keeps clear of the heart, other sprites, the names already placed
 * (in slot order, so every device agrees) and every vein, else above, further out, beside or aslant. With no clear spot:
 * the least overlapping one, and of those the one crossed by the fewest veins. [ns] is [nodeScale].
 */
internal fun lobeNames(layout: Layout, sizes: List<Size?>, k: Float, ns: Float): List<Offset?> {
    val out = arrayOfNulls<Offset>(layout.nL)
    val taken = ArrayList<FloatArray>()
    taken.add(floatArrayOf((SUN_X + HEART_BOX[0]) * k, (SUN_Y + HEART_BOX[1]) * k, (SUN_X + HEART_BOX[2]) * k, (SUN_Y + HEART_BOX[3]) * k))
    // every vein (core to lobe, core to heart) as short straight pieces (x1, y1, x2, y2, pad), in the units of [sizes]
    val lines = ArrayList<FloatArray>()
    val pt = FloatArray(2)
    fun curve(x1: Float, y1: Float, x2: Float, y2: Float, bend: Float, pad: Float) {
        var px = x1; var py = y1
        for (q in 1..8) {
            veinPoint(pt, x1, y1, x2, y2, bend, q / 8f)
            lines.add(floatArrayOf(px * k, py * k, pt[0] * k, pt[1] * k, pad * k)); px = pt[0]; py = pt[1]
        }
    }
    for (i in 0 until layout.nL) {
        curve(0f, 0f, layout.lx[i], layout.ly[i], layout.lbend[i], 4f) // three strands about 2.6 apart, and their wobble
    }
    curve(0f, 0f, SUN_X, SUN_Y - HEART_MID, SUN_BEND, 3f)
    for (i in (0 until layout.nL).sortedBy { layout.lslot[it] }) {
        val n = sizes[i] ?: continue
        val cx = layout.lx[i] * k - n.width / 2f
        val below = (layout.ly[i] + (PORTAL_OUT + 1f) * ns) * k
        val above = (layout.ly[i] - (PORTAL_OUT + 1f) * ns) * k - n.height
        val heartSide = if (layout.lx[i] >= SUN_X) taken[0][2] + 2f else taken[0][0] - 2f - n.width // clear of the heart, still under the lobe
        val side = (PORTAL_OUT + 3f) * ns * k; val mid = layout.ly[i] * k - n.height / 2f
        val tries = listOf(
            Offset(cx, below), Offset(cx, above), Offset(heartSide, below),
            Offset(cx, below + n.height), Offset(cx, above - n.height), // one line further out
            Offset(layout.lx[i] * k + side, mid), Offset(layout.lx[i] * k - side - n.width, mid), // beside the lobe
            Offset(layout.lx[i] * k + side, below), Offset(layout.lx[i] * k - side - n.width, below), // aslant
            Offset(layout.lx[i] * k + side, above), Offset(layout.lx[i] * k - side - n.width, above),
        )
        // other portals count as taken too (not this one's own, which the name hangs from)
        val sprites = (0 until layout.nL).filter { it != i }.map { j ->
            floatArrayOf((layout.lx[j] - PORTAL_OUT * ns) * k, (layout.ly[j] - PORTAL_OUT * ns) * k, (layout.lx[j] + PORTAL_OUT * ns) * k, (layout.ly[j] + PORTAL_OUT * ns) * k)
        }
        fun overlap(t: Offset) = (taken + sprites).sumOf { b ->
            (minOf(t.x + n.width, b[2]) - maxOf(t.x, b[0])).coerceAtLeast(0f).toDouble() * (minOf(t.y + n.height, b[3]) - maxOf(t.y, b[1])).coerceAtLeast(0f)
        }
        fun crossed(t: Offset, from: Int, to: Int) = (from until to).count { q -> val it = lines[q]
            segmentHitsBox(it[0], it[1], it[2], it[3], t.x - it[4], t.y - it[4], t.x + n.width + it[4], t.y + n.height + it[4]) }
        // a name is never dropped: with no clear spot it takes the one that overlaps least, then is crossed by the fewest veins (the first of equals).
        val at = tries.withIndex().minWithOrNull(compareBy<IndexedValue<Offset>>(
            { overlap(it.value) }, { crossed(it.value, 0, lines.size) },
        ))!!.value
        taken.add(floatArrayOf(at.x, at.y, at.x + n.width, at.y + n.height))
        out[i] = at
    }
    return out.asList()
}

/**
 * Every box (l, t, r, b, dp from the camera at the world origin) of the whole map at zoom [z]: core, portals with their names,
 * the heart and its name. [lobeNames] are the names' sizes in dp (null = no name), [vaultName] too.
 */
internal fun overviewBoxes(layout: Layout, lobeNames: List<Size?>, vaultName: Size, z: Float, pxdp: Float): List<FloatArray> {
    val k = pxdp * z
    val ns = nodeScale(z)
    val out = ArrayList<FloatArray>()
    fun around(x: Float, y: Float, r: Float) = out.add(floatArrayOf(x * k - r, y * k - r, x * k + r, y * k + r))
    around(0f, 0f, 36f * k)
    val names = lobeNames(layout, lobeNames, k, ns)
    for (i in 0 until layout.nL) {
        around(layout.lx[i], layout.ly[i], PORTAL_OUT * ns * k)
        names[i]?.let { out.add(floatArrayOf(it.x, it.y, it.x + lobeNames[i]!!.width, it.y + lobeNames[i]!!.height)) }
    }
    out.add(floatArrayOf((SUN_X + HEART_BOX[0]) * k, (SUN_Y + HEART_BOX[1]) * k, (SUN_X + HEART_BOX[2]) * k, (SUN_Y + HEART_BOX[3]) * k))
    out.add(floatArrayOf(SUN_X * k - vaultName.width / 2f, (SUN_Y + 12f) * k, SUN_X * k + vaultName.width / 2f, (SUN_Y + 12f) * k + vaultName.height))
    return out
}

/**
 * Camera aim (x, y, zoom) for the whole map, [w] x [h] dp with the title row above [top]: the largest zoom from [maxZ] down to [minZ]
 * at which every box of [boxesAt] fits with [margin] to spare, so the map fills the view (at [minZ] the rest is simply cut off).
 * Names do not shrink with the zoom, but the gaps do, so stepping down from [maxZ] finds the first fit. The map is centred in the free area.
 */
internal fun fitOverview(boxesAt: (Float) -> List<FloatArray>, w: Float, h: Float, top: Float, pxdp: Float, maxZ: Float, minZ: Float, margin: Float = 10f): FloatArray {
    var z = maxZ
    while (true) {
        val b = boxesAt(z)
        val x0 = b.minOf { it[0] }; val x1 = b.maxOf { it[2] }; val y0 = b.minOf { it[1] }; val y1 = b.maxOf { it[3] }
        if (z <= minZ || (x1 - x0 <= w - 2 * margin && y1 - y0 <= h - top - 2 * margin)) {
            val k = pxdp * z
            return floatArrayOf((x0 + x1) / 2f / k, ((y0 + y1) / 2f - top / 2f) / k, z)
        }
        z = max(minZ, z * 0.99f)
    }
}

// ---------------------------------------------------------------------------------------------
// Search + share picker
// ---------------------------------------------------------------------------------------------

/** Whole-library search: type, see matching sub brains, groups and notes with where they live, tap one to go to it. */
@Composable
internal fun SearchPanel(ideas: List<Idea>, lobes: List<Lobe>, branches: List<Branch>, onPick: (Hit) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val hits = remember(q, ideas, lobes, branches) { searchLibrary(lobes, branches, ideas, q).take(80) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(true, onClose)
    Column(Modifier.fillMaxSize().background(Px.Bg.copy(alpha = 0.96f)).imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PixelField(q, { q = it }, "Search sub brains, groups, notes", Modifier.weight(1f).focusRequester(focus), cursor = Px.Lavender)
            PixelButton("CLOSE", onClose)
        }
        Spacer(Modifier.height(14.dp))
        val dim = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim)
        when {
            q.isBlank() -> Text("Type to search names, titles, text and links.", style = dim)
            hits.isEmpty() -> Text("Nothing found.", style = dim)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits, key = { "${it.kind}${it.idea?.id ?: it.branch?.id ?: it.lobe.id}" }) { h ->
                    Column(
                        Modifier.fillMaxWidth().pixelCard(fill = Px.Elevated, shadow = false, notch = 2.dp)
                            .clickable(role = Role.Button) { onPick(h) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            h.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = PageFont, fontSize = 19.sp, lineHeight = 22.sp, color = Px.Text),
                        )
                        Text(
                            "${h.kind} · ${h.where}".uppercase(), Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = lobeColor(h.lobe)),
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
private fun PickRow(label: String, color: androidx.compose.ui.graphics.Color, indent: Int, here: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).pixelCard(fill = Px.Bg, shadow = false, notch = 2.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(start = (12 + indent).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, color = color))
        if (here) Text("HERE", style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim))
    }
}

/**
 * "Move to..." for an open note: first every sub brain, then the chosen one's groups plus "no group". Where the note sits now is
 * marked HERE. Nothing moves until a place is tapped; [onPick] gets (sub brain, group or null for loose).
 */
@Composable
internal fun MovePicker(
    lobes: List<Lobe>,
    branches: List<Branch>,
    hereLobe: Long,
    hereBranch: Long?,
    onPick: (lobeId: Long, branchId: Long?) -> Unit,
    onCancel: () -> Unit,
) {
    var lobeId by remember { mutableStateOf<Long?>(null) }
    val lobe = lobes.firstOrNull { it.id == lobeId }
    PixelDialog(onCancel) {
        Text(if (lobe == null) "MOVE TO WHERE?" else "MOVE TO ${lobe.name.uppercase()}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lobe == null) {
                items(lobes, key = { "l${it.id}" }) { l -> PickRow(l.name.uppercase(), lobeColor(l), 0, here = l.id == hereLobe) { lobeId = l.id } }
            } else {
                item(key = "none") { PickRow("NO GROUP", lobeColor(lobe), 0, here = lobe.id == hereLobe && hereBranch == null) { onPick(lobe.id, null) } }
                items(branches.filter { it.lobeId == lobe.id }.sortedBy { it.name }, key = { "b${it.id}" }) { b ->
                    PickRow(b.name.uppercase(), lobeColor(lobe), 18, here = b.id == hereBranch) { onPick(lobe.id, b.id) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            if (lobe != null) PixelButton("BACK", { lobeId = null })
            PixelButton("CANCEL", onCancel)
        }
    }
}
