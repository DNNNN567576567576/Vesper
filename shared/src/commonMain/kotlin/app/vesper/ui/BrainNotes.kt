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
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

/** Items touched in the last 48 hours glow magenta. */
internal const val RECENT_MS = 48 * 3_600_000L

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
    for (i in ix.indices) box(ix[i], iy[i], 8f, 6f, 8f, 11f)
    box(sunX, sunY, 14f, 15f, 14f, 27f)
    return out.toFloatArray()
}

/** One box around a focused sub brain's whole tree and the screen around it (rot holes drew pink-rimmed blocks at its edges), so rot holes keep off all of it. */
internal fun focusKeepOutBoxes(lx: Float, ly: Float, bx: List<Float>, by: List<Float>, ix: List<Float>, iy: List<Float>): FloatArray {
    val xs = bx + ix + lx; val ys = by + iy
    return floatArrayOf(xs.min() - 30f, ly - 60f, xs.max() + 30f, (ys.maxOrNull() ?: ly) + 60f)
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

/** Where a focused sub brain's groups and items sit: [loose] on the lobe, a node per group, and each group's [items]. World units. */
internal class Formation(val loose: List<Offset>, val groups: List<Offset>, val items: List<List<Offset>>)

// A family tree: the lobe on top, its groups side by side below it, each group's neurons hanging under it in its own column.
// World units, made for zoom 2 (where nodes reach full size, 1 unit = about 4.7 dp), so the tree is about as wide as a phone.
internal const val TREE_W = 70f
private const val FORM_REF_DP = 4.7f
private const val FORM_GROUP_Y = 24.5f // the lobe to a row of groups (their rings 3 dp apart)
private const val FORM_BELOW_GROUP = 19f // a group to the bottom of its name

/** A neuron's reach in units (its rings, with the dot's wobble), by ring count: 17, 23, 28.6 and 33.8 dp. */
private fun reachU(rings: Int) = (floatArrayOf(17f, 23f, 28.6f, 33.8f)[rings.coerceIn(0, 3)] + 1f) / FORM_REF_DP

/** Columns in the widest row of a focused sub brain: its groups, plus one for loose items, at most 3 to a row. */
internal fun focusCols(looseN: Int, groupN: Int): Int {
    val c = groupN + if (looseN > 0) 1 else 0
    if (c <= 3) return max(1, c)
    return (c + (c + 2) / 3 - 1) / ((c + 2) / 3) // rows = ceil(c / 3), spread evenly
}
/** How many neurons side by side inside one column: a lone column or a pair may hold two, a grid of loose items up to 3. */
internal fun focusSub(looseN: Int, groupN: Int) = if (groupN == 0) looseN.coerceIn(1, 3) else if (focusCols(looseN, groupN) <= 2) 2 else 1
/** How far apart [sub] neurons sit across a [width] wide column: a pair a bit wider than half each, so the names have room. */
private fun subStep(width: Float, sub: Int) = width * (if (sub == 2) 0.54f else 1f / sub)
/** dp a neuron's name may be wide, and a group's name. */
internal fun focusItemWidth(cols: Int, sub: Int) = (subStep(TREE_W / cols, sub) * FORM_REF_DP - 16f).coerceIn(64f, 140f)
internal fun focusGroupWidth(cols: Int) = (TREE_W / cols * FORM_REF_DP - 12f).coerceIn(70f, 150f)

/**
 * The lobe at [lobe]; the groups in rows of up to 3, evenly spread and level, each with its neurons straight below in its own
 * column (two side by side for 1 or 2 columns); loose items a column of their own beside the groups, or a grid with no groups.
 * Only the ring counts of the items go in (so every device draws the same tree), and each row of neurons is as far from the
 * next as its rings and a two-line name need. [loose] and each of [groups] list their items' ring counts.
 */
internal fun focusFormation(lobe: Offset, loose: List<Int>, groups: List<List<Int>>): Formation {
    val perRow = focusCols(loose.size, groups.size); val sub = focusSub(loose.size, groups.size)
    val colW = TREE_W / perRow
    val outLoose = ArrayList<Offset>(); val outGroups = ArrayList<Offset>(); val outItems = groups.map { ArrayList<Offset>() }
    /** Puts a column's neurons in rows of [per] from [first] down, [step] apart across; returns where the last name ends. */
    fun column(cx: Float, rings: List<Int>, per: Int, first: Float, step: Float, out: ArrayList<Offset>): Float {
        var y = first; var prev = 0f; var bottom = first
        rings.chunked(per).forEachIndexed { r, row ->
            val reach = row.maxOf { reachU(it) }
            if (r > 0) y += prev + reach + 11.3f
            val pair = per > 1 && row.size > 1
            row.indices.forEach { c ->
                val x = if (per == 1) cx + (if (r % 2 == 0) -0.6f else 0.6f) else cx + (c - (row.size - 1) / 2f) * step
                out.add(Offset(x, lobe.y + y + (if (pair && c % 2 == 1) 2.5f else 0f))) // the right one hangs a little lower
            }
            prev = reach
            bottom = y + reach + 10.4f + (if (pair) 2.5f else 0f)
        }
        return bottom
    }
    if (groups.isEmpty()) {
        if (loose.isNotEmpty()) column(lobe.x, loose, sub, 17f + loose.take(sub).maxOf { reachU(it) }, subStep(TREE_W, sub), outLoose)
        return Formation(outLoose, outGroups, outItems)
    }
    val cols = (if (loose.isNotEmpty()) listOf(loose) else emptyList()) + groups // a column per entry; loose is first when it exists
    val g0 = if (loose.isNotEmpty()) 1 else 0
    var y = FORM_GROUP_Y
    cols.indices.chunked(perRow).forEach { row ->
        var bottom = y + FORM_BELOW_GROUP
        row.forEachIndexed { p, ci ->
            val cx = lobe.x + (p - (row.size - 1) / 2f) * colW
            val isLoose = ci < g0; val rs = cols[ci]
            if (!isLoose) outGroups.add(Offset(cx, lobe.y + y))
            if (rs.isEmpty()) return@forEachIndexed
            val first = y + (if (isLoose) 2f else 19.6f) + reachU(rs[0])
            val per = if (sub == 2 && rs.size >= 2) 2 else 1
            bottom = max(bottom, column(cx, rs, per, first, subStep(colW, 2), if (isLoose) outLoose else outItems[ci - g0]))
        }
        y = bottom + 10f
    }
    return Formation(outLoose, outGroups, outItems)
}

/** What a focused sub brain is made of, for placing names: world positions, each name's size in dp, rings per item. The lobe's own name is not drawn: the header has it. */
internal class FocusScene(
    val lobe: Offset,
    val groups: List<Offset>, val groupNames: List<Size>,
    val items: List<Offset>, val rings: IntArray, val itemNames: List<Size>,
    val itemGroup: IntArray, // index into [groups] of each item's group, -1 = on the lobe
)

/** Names placed for one zoom: where each name's top-left sits from its node (dp), null = no room. [box] is l, t, r, b (dp, world * scale) around everything drawn. */
internal class FocusPlan(val groups: List<Offset?>, val items: List<Offset?>, val box: FloatArray, val discs: List<FloatArray>)

private const val NAME_GAP = 3f

/**
 * Puts every name straight under its node, centred. If that is on another node, its rings or another name it is dropped (null),
 * or with [force] kept there anyway, so a crowded tree still shows every name. [pxdp] is dp per low-res pixel.
 */
internal fun planFocus(s: FocusScene, z: Float, pxdp: Float, force: Boolean = false): FocusPlan {
    val k = pxdp * z
    val c = min(z, NODE_ZOOM) * pxdp // a node's size in dp: it stops growing at NODE_ZOOM
    val discs = ArrayList<FloatArray>() // x, y, r in dp
    discs.add(floatArrayOf(s.lobe.x * k, s.lobe.y * k, 15f * c + 2f))
    s.groups.forEach { discs.add(floatArrayOf(it.x * k, it.y * k, 8f * c + 2f)) }
    val dot = max(2, rnd(2f * min(z, 2.8f) / 1.4f)) * 0.9f
    s.items.forEachIndexed { i, p ->
        val n = s.rings[i]
        val reach = (if (n > 0) dot + 3f + (n - 1) * 2.2f + 1f else dot + 1.5f) + 1.5f * min(z, NODE_ZOOM) // rings, and the dot's wobble
        discs.add(floatArrayOf(p.x * k, p.y * k, reach * pxdp))
    }
    val placed = ArrayList<FloatArray>() // l, t, r, b
    fun place(d: FloatArray, size: Size): Offset? {
        val l = d[0] - size.width / 2f; val t = d[1] + d[2] + NAME_GAP; val r = l + size.width; val b = t + size.height
        val hit = discs.any { hypot(it[0] - it[0].coerceIn(l, r), it[1] - it[1].coerceIn(t, b)) < it[2] - 0.01f } ||
            placed.any { l < it[2] + 2f && r > it[0] - 2f && t < it[3] + 2f && b > it[1] - 2f }
        if (hit && !force) return null
        placed.add(floatArrayOf(l, t, r, b))
        return Offset(l - d[0], t - d[1])
    }
    val groupAt = s.groups.mapIndexed { g, _ -> place(discs[1 + g], s.groupNames[g]) }
    val itemAt = s.items.mapIndexed { i, _ -> place(discs[1 + s.groups.size + i], s.itemNames[i]) }

    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
    discs.forEach { x0 = min(x0, it[0] - it[2]); x1 = max(x1, it[0] + it[2]); y0 = min(y0, it[1] - it[2]); y1 = max(y1, it[1] + it[2]) }
    placed.forEach { x0 = min(x0, it[0]); x1 = max(x1, it[2]); y0 = min(y0, it[1]); y1 = max(y1, it[3]) }
    return FocusPlan(groupAt, itemAt, floatArrayOf(x0, y0, x1, y1), discs)
}

/**
 * Camera aim (x, y, zoom) for a focused sub brain, [w] x [h] dp: the largest zoom within [minZ]..[maxZ] at which the tree
 * (lobe, groups, items, rings and names) fits the part of the view below [top], with [margin] to spare. It is centred across on
 * the lobe, so the lobe sits at the top centre; a tree shorter than the view is not stretched, and sits a little above the middle.
 */
internal fun fitFocus(s: FocusScene, w: Float, h: Float, top: Float, pxdp: Float, minZ: Float, maxZ: Float, margin: Float = 10f): FloatArray {
    fun inView(p: FocusPlan, z: Float): Boolean { val b = p.box; val m = s.lobe.x * pxdp * z; return 2 * max(m - b[0], b[2] - m) <= w - 2 * margin && b[3] - b[1] <= h - top - 2 * margin }
    // every name must have found its place under its node, so a smaller zoom that squeezes names together does not count
    fun fits(z: Float) = planFocus(s, z, pxdp).let { p -> p.groups.none { it == null } && p.items.none { it == null } && inView(p, z) }
    // names do not shrink with the zoom, so "fits" is not monotonic: step down from the top
    var z = maxZ
    while (z > minZ && !fits(z)) z = max(minZ, z * 0.98f)
    if (!fits(z)) { // too crowded for every name: at least keep the whole tree in view
        z = maxZ
        while (z > minZ && !inView(planFocus(s, z, pxdp, force = true), z)) z = max(minZ, z * 0.98f)
    }
    val b = planFocus(s, z, pxdp, force = true).box
    val k = pxdp * z
    val spare = (h - top - 2 * margin - (b[3] - b[1])).coerceAtLeast(0f)
    val boxTop = top + margin + 0.4f * spare // where the tree's top lands on screen
    return floatArrayOf(s.lobe.x, (b[1] + h / 2f - boxTop) / k, z)
}

/** The heart's box around (SUN_X, SUN_Y), in world units: l, t, r, b. Names keep off it. */
internal val HEART_BOX = floatArrayOf(-14f, -34f, 14f, 3f)

/**
 * Where each lobe's name sits, as its top-left from the camera at the world origin, in the units of [sizes] ([k] of them per world unit),
 * null = no name (only a lobe with no name). Under its lobe if that keeps clear of the heart, other sprites and the names already placed
 * (in slot order, so every device agrees), else above, beside, or further out; with no clear spot, the least overlapping one. [ns] is [nodeScale].
 */
internal fun lobeNames(layout: Layout, sizes: List<Size?>, k: Float, ns: Float): List<Offset?> {
    val out = arrayOfNulls<Offset>(layout.nL)
    val taken = ArrayList<FloatArray>()
    taken.add(floatArrayOf((SUN_X + HEART_BOX[0]) * k, (SUN_Y + HEART_BOX[1]) * k, (SUN_X + HEART_BOX[2]) * k, (SUN_Y + HEART_BOX[3]) * k))
    for (i in (0 until layout.nL).sortedBy { layout.lslot[it] }) {
        val n = sizes[i] ?: continue
        val cx = layout.lx[i] * k - n.width / 2f
        val below = (layout.ly[i] + 12f * ns) * k
        val above = (layout.ly[i] - 12f * ns) * k - n.height
        val heartSide = if (layout.lx[i] >= SUN_X) taken[0][2] + 2f else taken[0][0] - 2f - n.width // clear of the heart, still under the lobe
        val side = 16f * ns * k; val mid = layout.ly[i] * k - n.height / 2f
        val tries = listOf(
            Offset(cx, below), Offset(cx, above), Offset(heartSide, below),
            Offset(layout.lx[i] * k + side, mid), Offset(layout.lx[i] * k - side - n.width, mid), // beside the lobe
            Offset(cx, below + n.height), Offset(cx, above - n.height), // one line further out
        )
        // other lobes' sprites count as taken too (not this lobe's own, which the name hangs from)
        val sprites = (0 until layout.nL).filter { it != i }.map { j ->
            floatArrayOf((layout.lx[j] - 12f * ns) * k, (layout.ly[j] - 10f * ns) * k, (layout.lx[j] + 12f * ns) * k, (layout.ly[j] + 10f * ns) * k)
        }
        fun overlap(t: Offset) = (taken + sprites).sumOf { b ->
            (minOf(t.x + n.width, b[2]) - maxOf(t.x, b[0])).coerceAtLeast(0f).toDouble() * (minOf(t.y + n.height, b[3]) - maxOf(t.y, b[1])).coerceAtLeast(0f)
        }
        // a name is never dropped: with no clear spot it takes the one that overlaps least (the first of equals)
        val at = tries.firstOrNull { overlap(it) == 0.0 } ?: tries.minByOrNull { overlap(it) }!!
        taken.add(floatArrayOf(at.x, at.y, at.x + n.width, at.y + n.height))
        out[i] = at
    }
    return out.asList()
}

/**
 * Every box (l, t, r, b, dp from the camera at the world origin) of the whole map at zoom [z]: core, lobes with their names, groups,
 * items with their rings, the heart and its name. [lobeNames] are the names' sizes in dp (null = no name), [vaultName] too.
 */
internal fun overviewBoxes(layout: Layout, lobeNames: List<Size?>, vaultName: Size, z: Float, pxdp: Float): List<FloatArray> {
    val k = pxdp * z
    val ns = nodeScale(z)
    val out = ArrayList<FloatArray>()
    fun around(x: Float, y: Float, r: Float) = out.add(floatArrayOf(x * k - r, y * k - r, x * k + r, y * k + r))
    around(0f, 0f, 36f * k)
    val s = max(2, rnd(2f * min(z, 2.8f) / 1.4f))
    val names = lobeNames(layout, lobeNames, k, ns)
    for (i in 0 until layout.nL) {
        around(layout.lx[i], layout.ly[i], 16f * ns * k)
        names[i]?.let { out.add(floatArrayOf(it.x, it.y, it.x + lobeNames[i]!!.width, it.y + lobeNames[i]!!.height)) }
        for (g in layout.bstart[i] until layout.bstart[i + 1]) around(layout.bx[g], layout.by[g], 9f * ns * k)
        for (n in layout.istart[i] until layout.istart[i + 1]) around(layout.ix[n], layout.iy[n], max(7f * ns * k, (s * 0.9f + 3f + layout.irings[n] * 2.2f) * pxdp) + 1.5f * ns * k)
    }
    out.add(floatArrayOf((SUN_X + HEART_BOX[0]) * k, (SUN_Y + HEART_BOX[1]) * k, (SUN_X + HEART_BOX[2]) * k, (SUN_Y + HEART_BOX[3]) * k))
    out.add(floatArrayOf(SUN_X * k - vaultName.width / 2f, (SUN_Y + 12f) * k, SUN_X * k + vaultName.width / 2f, (SUN_Y + 12f) * k + vaultName.height))
    return out
}

/**
 * Camera aim (x, y, zoom) for the whole map, [w] x [h] dp with the title row above [top]: the home zoom [homeZ] if every box of
 * [boxesAt] fits with [margin] to spare, else the largest zoom down to [minZ] that does (then the rest is simply cut off).
 * Names do not shrink with the zoom, but the gaps do, so stepping down from [homeZ] finds the first fit. The map is centred in the free area.
 */
internal fun fitOverview(boxesAt: (Float) -> List<FloatArray>, w: Float, h: Float, top: Float, pxdp: Float, homeZ: Float, minZ: Float, margin: Float = 10f): FloatArray {
    var z = homeZ
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
