package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.max

/** The zoom the paper words are measured at (a phone's home zoom); they scale from there with the map. */
internal const val BOARD_TEXT_Z = 0.5f // the paper words are never smaller than a task's name (17sp) from the zoom floor up
/** dp: a main-brain spark's name at most. */
internal const val SPARK_NAME_W = 72f

/** How visible the sparks' names are (0..1) at camera zoom [z] when the overview fit sits at [home]: none at the fit, fading in as you zoom past it. */
internal fun sparkNameAlpha(z: Float, home: Float): Float = if (home <= 0f) 0f else ((z / home - 1.15f) / 0.35f).coerceIn(0f, 1f)

/**
 * All the names on the main map, measured once and drawn at full resolution over the low-res frame: the vault's, the learn and
 * tasks brains', and every task's, left-aligned just right of its node.
 */
internal class BrainLabels(
    private val measurer: TextMeasurer, private val density: Float, private val pxSize: Float,
    private val pageFont: FontFamily, private val pixelFont: FontFamily,
    private val layout: Layout, private val hint: Boolean,
) {
    private val pxdp = pxSize / density

    /** A brain's name in the readable pixel font notes use, glowing in [c] ([glow] for the glow). */
    private fun labelStyle(size: Int, c: Color, glow: Color) =
        TextStyle(fontFamily = pageFont, fontSize = size.sp, lineHeight = (size + 2).sp, color = c, shadow = Shadow(glow.copy(alpha = 0.7f), blurRadius = 10f * density))

    private fun brainLabel(text: String) = Color(0xFFC9B6FF).let { GlowText(measurer.measure(text, labelStyle(18, it, it))) }
    private val learnLabel = brainLabel("learn")
    private val tasksLabel = brainLabel("tasks")
    private val dailyLabel = brainLabel("daily")
    private val bibleLabel = brainLabel("bible")

    private var vault: Pair<Float, GlowText>? = null
    private fun vaultLabel(sv: Float): GlowText = vault?.takeIf { it.first == sv }?.second ?: run {
        val c = lerp(Color(0xFFC9B6FF), Color(0xFF6F6080), sv)
        GlowText(measurer.measure("vault", labelStyle(20, c, if (sv > 0.5f) Color.Transparent else c), maxLines = 1)).also { vault = sv to it }
    }

    /** Each main-brain spark's name, shown below it only once the map is zoomed in. */
    private val noteLabels = layout.notes.map { n ->
        GlowText(measurer.measure(
            ideaLabel(n).ifBlank { "..." },
            TextStyle(fontFamily = pageFont, fontSize = 13.sp, lineHeight = 15.sp, color = if (n.done) Color(0xFF6F6880) else Color(0xFFE6DCFF), shadow = Shadow(Color(0xFF07030B), blurRadius = 4f * density)),
            overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (SPARK_NAME_W * density).toInt()),
        ))
    }
    /** Each pinned paper's first words in the notes' own type, as big as a task's name, dark purple, wrapped to the paper (two lines, then cut). */
    private val paperLabels = run {
        val wDp = PAPER_W * pxdp * BOARD_TEXT_Z - 6f
        layout.pinned.map { n ->
            measurer.measure(
                ideaLabel(n).ifBlank { "..." },
                TextStyle(fontFamily = pageFont, fontSize = 17.sp, lineHeight = 19.sp, color = Color(0xFF2A1858), textAlign = TextAlign.Center),
                overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = (wDp * density).toInt()),
            )
        }
    }
    private val moreLabel = (layout.pinnedTotal - layout.pinned.size).takeIf { it > 0 }?.let {
        measurer.measure("+$it", TextStyle(fontFamily = pixelFont, fontSize = 9.sp, color = Color.White), maxLines = 1)
    }
    private val hintStyle = TextStyle(fontFamily = pixelFont, fontSize = 9.sp, color = Px.TextDim)

    /** Every box of the main map at home zoom [z] (no task or goal names: they are hidden there), in dp from the camera at the origin ([fitOverview]). */
    fun overviewBoxes(z: Float): List<FloatArray> = overviewBoxes(layout, vaultLabel(0f).tl.size.let { Size(it.width / density, it.height / density) }, z, pxdp)

    /** One frame of names, with the same world -> device transform as the low-res bitmap. */
    fun draw(scope: DrawScope, renderer: BrainRenderer, camX: Float, camY: Float, z: Float, sunSel: Boolean, sunSv: Float, home: Float = 0f) = with(scope) {
        val ns = nodeScale(z)
        val u = pxSize * z
        val ox = renderer.W * pxSize / 2f
        val oy = renderer.H * pxSize / 2f
        fun dx(wx: Float) = (wx - camX) * u + ox
        fun dy(wy: Float) = (wy - camY) * u + oy
        val dim = if (sunSel) 0.45f else 1f

        val v = vaultLabel(sunSv)
        v.draw(this, dx(HEART_AT.x) - v.tl.size.width / 2f, dy(HEART_AT.y + HEART_MID + 12f), 1f)
        if (renderer.learnMoons >= 0) learnLabel.draw(this, dx(LEARN_AT.x) - learnLabel.tl.size.width / 2f, dy(LEARN_AT.y + 17f * ns), dim)
        tasksLabel.draw(this, dx(TASKS_AT.x) - tasksLabel.tl.size.width / 2f, dy(TASKS_AT.y + 17f * ns), dim)
        if (renderer.bible) bibleLabel.draw(this, dx(BIBLE_AT.x) - bibleLabel.tl.size.width / 2f, dy(BIBLE_AT.y - 15f * ns) - bibleLabel.tl.size.height, dim)
        if (renderer.daily >= 0f) dailyLabel.draw(this, dx(DAILY_AT.x) - dailyLabel.tl.size.width / 2f, dy(DAILY_AT.y - 15f * ns) - dailyLabel.tl.size.height, dim * (0.5f + 0.5f * renderer.daily))
        // the pinned papers' words, tilted with their paper and growing with the zoom (never below their measured size); a paper partly
        // under a newer one keeps its words hidden, so they never print over the paper on top
        val bc = layout.boardAt
        val ts = max(1f, z / BOARD_TEXT_Z)
        val cells = layout.pinned.indices.map { layout.paperCell(it) }
        for (i in paperLabels.indices.reversed()) {
            val q = cells[i]; val tl = paperLabels[i]
            if ((0 until i).any { o -> cells[o].let { q[0] < it[2] && it[0] < q[2] && q[1] < it[3] && it[1] < q[3] } }) continue
            val cx = dx(bc.x + (q[0] + q[2]) / 2f + paperSway(renderer.t, i)); val cy = dy(bc.y + (q[1] + q[3]) / 2f + 1.5f)
            if (cx < -300f || cx > size.width + 300f || cy < -300f || cy > size.height + 300f) continue
            val pivot = Offset(cx, cy)
            val deg = Math.toDegrees(kotlin.math.atan(paperTilt(layout.pinned[i].uid).toDouble())).toFloat()
            withTransform({ scale(ts, ts, pivot); rotate(deg, pivot) }) { drawText(tl, topLeft = Offset(cx - tl.size.width / 2f, cy - tl.size.height / 2f), alpha = dim) }
        }
        moreLabel?.let { drawText(it, topLeft = Offset(dx(bc.x + BOARD_W / 2f - 19.5f) - it.size.width / 2f, dy(bc.y + BOARD_H / 2f) - it.size.height / 2f), alpha = dim) }
        val na = sparkNameAlpha(z, home) * dim
        if (na > 0f) {
            val m = Offset.Zero
            val shown = ArrayList<FloatArray>() // a name that would land on a newer one's is left out
            for (i in noteLabels.indices) {
                val l = noteLabels[i]
                val left = dx(m.x + layout.noteAt[i].x) - l.tl.size.width / 2f; val top = dy(m.y + layout.noteAt[i].y) + 5f * pxSize
                if (left > size.width || left + l.tl.size.width < 0f || top > size.height || top + l.tl.size.height < 0f) continue
                val box = floatArrayOf(left, top, left + l.tl.size.width, top + l.tl.size.height)
                if (shown.any { box[0] < it[2] && it[0] < box[2] && box[1] < it[3] && it[1] < box[3] }) continue
                shown.add(box)
                l.draw(this, left, top, na)
            }
        }
        if (hint) {
            val h = measurer.measure("DOUBLE-TAP THE BRAIN TO KEEP A NOTE", hintStyle)
            drawText(h, topLeft = Offset(dx(0f) - h.size.width / 2f, dy(44f)))
        }
    }
}
