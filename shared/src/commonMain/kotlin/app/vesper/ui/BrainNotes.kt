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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.data.Idea
import app.vesper.data.Lobe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max

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

/** Every item whose title, body or link holds [query], ignoring case (Georgian capitals too); nothing for a blank query. */
internal fun searchIdeas(ideas: List<Idea>, query: String): List<Idea> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return ideas.filter { it.title.lowercase().contains(q) || it.body.lowercase().contains(q) || it.link.orEmpty().lowercase().contains(q) }
}

/** The sub brain named Tasks (any case) holds the tasks brain's tasks; a nameless one holds the main brain's loose notes. */
internal fun isTasks(l: Lobe) = l.name.trim().equals("tasks", ignoreCase = true)
internal fun isLoose(l: Lobe) = l.name.isBlank()
/** The sub brain named Pinned (any case) holds the notes pinned to the corkboard on the main map. */
internal fun isPinned(l: Lobe) = l.name.trim().equals("pinned", ignoreCase = true)

/** The sub brain named Daily (any case) holds the Daily checklist; its items never show on the main map, in search or in the pickers. */
internal fun isDaily(l: Lobe) = app.vesper.isDailyLobe(l)

/** The main brain's notes: every sub brain's but Tasks, Pinned and Daily (old groups and the loose notes alike), newest first; the uid breaks ties so every device agrees. */
internal fun mainBrainNotes(lobes: List<Lobe>, ideas: List<Idea>): List<Idea> {
    val apart = lobes.filter { isTasks(it) || isPinned(it) || isDaily(it) }.mapTo(HashSet()) { it.id }
    return ideas.filter { it.lobeId !in apart }.sortedWith(compareBy({ -it.updatedAt }, { it.uid }))
}

/** The notes pinned to the corkboard, newest first (same order rule as [mainBrainNotes]). */
internal fun pinnedNotes(lobes: List<Lobe>, ideas: List<Idea>): List<Idea> {
    val ids = lobes.filter(::isPinned).mapTo(HashSet()) { it.id }
    return ideas.filter { it.lobeId in ids }.sortedWith(compareBy({ -it.updatedAt }, { it.uid }))
}

/** What a note's place is called where one is shown: "main brain", "tasks" or "pinned" (an old group keeps its own name). */
internal fun placeName(l: Lobe) = if (isLoose(l)) "main brain" else if (isTasks(l)) "tasks" else if (isPinned(l)) "pinned" else if (isDaily(l)) "daily" else l.name

/** A search result: a note (with [idea]), named [label], living at [where]. */
internal class Hit(val kind: String, val label: String, val where: String, val lobe: Lobe, val idea: Idea? = null)

/** The whole library: notes by [searchIdeas], ignoring case; nothing for a blank query. The Daily checklist is left out. */
internal fun searchLibrary(lobes: List<Lobe>, ideas: List<Idea>, query: String): List<Hit> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val lobeOf = lobes.filterNot(::isDaily).associateBy { it.id }
    return searchIdeas(ideas, q).mapNotNull { i ->
            val l = lobeOf[i.lobeId] ?: return@mapNotNull null
            Hit(if (isTasks(l)) "task" else "note", ideaLabel(i).ifBlank { "..." }, placeName(l), l, i)
        }
}

/** The heart's box around (SUN_X, SUN_Y), in world units: l, t, r, b. Names keep off it. */
internal val HEART_BOX = floatArrayOf(-14f, -34f, 14f, 3f)

/**
 * Every box (l, t, r, b, dp from the camera at the world origin) of the main map at zoom [z]: the core, the heart and its name
 * ([vaultName], dp), the learn and tasks brains with their names, every task's node and name ([taskNames], dp, in layout order) and
 * every goal moon with its name ([goalNames], dp).
 */
internal fun overviewBoxes(layout: Layout, vaultName: Size, z: Float, pxdp: Float): List<FloatArray> {
    val k = pxdp * z
    val ns = nodeScale(z)
    val out = ArrayList<FloatArray>()
    fun world(b: FloatArray) = out.add(floatArrayOf(b[0] * k, b[1] * k, b[2] * k, b[3] * k))
    val m = Offset.Zero; val h = HEART_AT
    world(floatArrayOf(m.x - 36f, m.y - 36f, m.x + 36f, m.y + 36f))
    world(floatArrayOf(h.x + HEART_BOX[0], h.y + HEART_MID + HEART_BOX[1], h.x + HEART_BOX[2], h.y + HEART_MID + HEART_BOX[3]))
    out.add(floatArrayOf(h.x * k - vaultName.width / 2f, (h.y + HEART_MID + 12f) * k, h.x * k + vaultName.width / 2f, (h.y + HEART_MID + 12f) * k + vaultName.height))
    world(boardBox(layout.boardAt)) // the whole pinned board, "+N" tag and all: it is in view at home
    for (q in layout.noteAt) world(floatArrayOf(m.x + q.x - 4f, m.y + q.y - 4f, m.x + q.x + 4f, m.y + q.y + 4f))
    for (p in listOf(LEARN_AT, TASKS_AT)) {
        world(learnBrainBox(p))
        out.add(floatArrayOf(p.x * k - LEARN_LABEL_W / 2f, (p.y + 17f * ns) * k, p.x * k + LEARN_LABEL_W / 2f, (p.y + 17f * ns) * k + LEARN_LABEL_H))
    }
    for (p in listOf(DAILY_AT, BIBLE_AT)) { // the daily and bible brains, their names above them
        world(learnBrainBox(p))
        out.add(floatArrayOf(p.x * k - LEARN_LABEL_W / 2f, (p.y - 15f * ns) * k - LEARN_LABEL_H, p.x * k + LEARN_LABEL_W / 2f, (p.y - 15f * ns) * k))
    }
    val mh = (5.5f * moonScale(z) + 1.5f) * pxdp // a goal moon's half size, dp
    for (g in layout.goalAt) out.add(floatArrayOf(g.x * k - mh, g.y * k - mh, g.x * k + mh, g.y * k + mh))
    for (q in layout.dailyAt + layout.bibleAt) world(decoBox(q))
    for (i in layout.tasks.indices) {
        val r = TASK_NODE_R * 2f * ns
        world(floatArrayOf(layout.tx[i] - r, layout.ty[i] - r, layout.tx[i] + r, layout.ty[i] + r))
    }
    return out
}

/**
 * Camera aim (x, y, zoom) for the whole map, [w] x [h] dp with the title row above [top]: the largest zoom from [maxZ] down to [minZ]
 * at which every box of [boxesAt] fits with [margin] to spare, so the map fills the view (at [minZ] the rest is simply cut off).
 * Names do not shrink with the zoom, but the gaps do, so stepping down from [maxZ] finds the first fit. The map is centred in the free area, sideways on the main brain and the heart (x = 0).
 */
internal fun fitOverview(boxesAt: (Float) -> List<FloatArray>, w: Float, h: Float, top: Float, pxdp: Float, maxZ: Float, minZ: Float, margin: Float = 10f): FloatArray {
    var z = maxZ
    while (true) {
        val b = boxesAt(z)
        val x1 = max(b.maxOf { it[2] }, -b.minOf { it[0] }); val x0 = -x1; val y0 = b.minOf { it[1] }; val y1 = b.maxOf { it[3] }
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

/** Whole-library search: type, see matching groups and notes with where they live, tap one to go to it. */
@Composable
internal fun SearchPanel(ideas: List<Idea>, lobes: List<Lobe>, onPick: (Hit) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val hits = remember(q, ideas, lobes) { searchLibrary(lobes, ideas, q).take(80) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(true, onClose)
    Column(Modifier.fillMaxSize().background(Px.Bg.copy(alpha = 0.96f)).imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PixelField(q, { q = it }, "Search groups, notes, tasks", Modifier.weight(1f).focusRequester(focus), cursor = Px.Lavender)
            PixelButton("CLOSE", onClose)
        }
        Spacer(Modifier.height(14.dp))
        val dim = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim)
        when {
            q.isBlank() -> Text("Type to search names, titles, text and links.", style = dim)
            hits.isEmpty() -> Text("Nothing found.", style = dim)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits, key = { "${it.kind}${it.idea?.id ?: it.lobe.id}" }) { h ->
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
 * Shared text arrives here: pick where to keep it, the main brain, tasks or the pinned board ([onNamed] "" makes the main brain's nameless sub brain,
 * "Tasks" or "Pinned" the others, when there is none yet). Only CANCEL throws it away, and the dialog says so; a stray tap outside does nothing.
 */
@Composable
internal fun SharePicker(share: SharedText, onNamed: (String) -> Unit, onCancel: () -> Unit) {
    val dim = TextStyle(fontFamily = PixelFont, fontSize = 9.sp, lineHeight = 16.sp, color = Px.TextDim)
    PixelDialog({}) {
        Text("KEEP THIS WHERE?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(10.dp))
        Text(parseShare(share.text, share.subject).title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = PageFont, fontSize = 17.sp, lineHeight = 20.sp, color = Px.Text))
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickRow("MAIN BRAIN", Px.Lavender) { onNamed("") }
            PickRow("TASKS", Px.Lavender) { onNamed("Tasks") }
            PickRow("PINNED", Px.Lavender) { onNamed("Pinned") }
        }
        Spacer(Modifier.height(12.dp))
        Text("CANCEL throws this text away. Nothing is saved anywhere else.", style = dim)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            PixelButton("CANCEL", onCancel)
        }
    }
}

@Composable
private fun PickRow(label: String, color: androidx.compose.ui.graphics.Color, here: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).pixelCard(fill = Px.Bg, shadow = false, notch = 2.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, color = color))
        if (here) Text("HERE", style = TextStyle(fontFamily = PixelFont, fontSize = 8.sp, color = Px.TextDim))
    }
}

/** The place a lobe stands for in the pickers: 0 main brain (loose notes and old groups), 1 tasks, 2 pinned. */
internal fun placeIndex(l: Lobe?) = if (l == null) 0 else if (isTasks(l)) 1 else if (isPinned(l)) 2 else 0
/** The lobe name each [placeIndex] is made under ("" = the main brain's nameless one). */
internal val PLACE_NAMES = listOf("", "Tasks", "Pinned")

/** "Move to..." for an open note: the main brain, tasks or pinned, where it sits now ([here], a [placeIndex]) marked HERE. [onPick] gets "" (main brain), "Tasks" or "Pinned". */
@Composable
internal fun MovePicker(here: Int, onPick: (String) -> Unit, onCancel: () -> Unit) {
    PixelDialog(onCancel) {
        Text("MOVE TO WHERE?", style = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, letterSpacing = 1.sp, color = Px.Lavender))
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickRow("MAIN BRAIN", Px.Lavender, here = here == 0) { onPick("") }
            PickRow("TASKS", Px.Lavender, here = here == 1) { onPick("Tasks") }
            PickRow("PINNED", Px.Lavender, here = here == 2) { onPick("Pinned") }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            PixelButton("CANCEL", onCancel)
        }
    }
}
