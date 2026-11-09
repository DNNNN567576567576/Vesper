package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import app.vesper.data.Lobe

/** An item name in the unfocused map, and the size it was measured for (so a zoom change rebuilds it). */
private class ItemLabel(val g: GlowText, val key: Int)

private const val LABEL_WIDTH = 84f // dp, an item name in the unfocused map at most
private const val FOCUS_GROUP_WIDTH = 132f
private const val NAME_PAD = 2f // dp of air counted around a name when placing it

/**
 * All the names on the map (lobe, group, item, vault), measured once and drawn at full resolution over the low-res frame.
 * The focused sub brain gets its own placement (no lobe name: the header has it) ([planFocus]) so names never sit on neurons, rings or each other.
 */
internal class BrainLabels(
    private val measurer: TextMeasurer, private val density: Float, private val pxSize: Float,
    private val pageFont: FontFamily, private val titleFont: FontFamily, pixelFont: FontFamily,
    private val lobes: List<Lobe>, private val web: Web, private val layout: Layout,
) {
    /** The zoom at which one cell is as wide as an idea's name: where the unfocused map starts showing them. */
    val itemZ = LABEL_WIDTH * density / (pxSize * CELL_X)
    private val pxdp = pxSize / density

    private fun labelStyle(c: Color, glow: Color) =
        TextStyle(fontFamily = titleFont, fontSize = 20.sp, color = c, shadow = Shadow(glow.copy(alpha = 0.7f), blurRadius = 12f * density))

    private val lobeLabels = List(layout.nL) { i ->
        val name = lobes[i].name
        if (name.isBlank()) null else {
            val lc = Color(layout.lcol[i] or 0xFF000000.toInt())
            GlowText(measurer.measure(
                name, labelStyle(lc, lc), overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = (150f * density).toInt().coerceAtLeast(60)),
            ))
        }
    }
    private val groupLabels = web.branches.map { b ->
        val lc = lobeColor(b.lobe)
        GlowText(measurer.measure(
            b.branch.name, TextStyle(fontFamily = pageFont, fontSize = 20.sp, lineHeight = 22.sp, color = lc, shadow = Shadow(lc.copy(alpha = 0.7f), blurRadius = 8f * density)),
            overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = (FOCUS_GROUP_WIDTH * density).toInt().coerceAtLeast(60)),
        ))
    }
    private var vault: Pair<Float, GlowText>? = null
    private fun vaultLabel(sv: Float): GlowText = vault?.takeIf { it.first == sv }?.second ?: run {
        val c = lerp(Color(0xFFF596E1), Color(0xFF6F6080), sv)
        GlowText(measurer.measure("vault", labelStyle(c, if (sv > 0.5f) Color.Transparent else c), maxLines = 1)).also { vault = sv to it }
    }
    // Built only when first seen, a few per frame, so a big cloud coming into view can't stall a frame.
    private val itemLabels = arrayOfNulls<ItemLabel>(web.items.size)
    private val hintStyle = TextStyle(fontFamily = pixelFont, fontSize = 9.sp, color = Px.TextDim)
    private val focusStyle = TextStyle(
        fontFamily = pageFont, fontSize = 19.sp, lineHeight = 21.sp, color = Color(0xFFE6DCFF), textAlign = TextAlign.Center,
        shadow = Shadow(Color(0xFFB69AFF).copy(alpha = 0.85f), blurRadius = 8f * density),
    )
    // A focused sub brain's names are as wide as their column: neurons by lobe i, groups by lobe i's column count.
    private fun colsOf(i: Int) = focusCols(layout.ipar.slice(layout.istart[i] until layout.istart[i + 1]).count { it < 0 }, layout.bstart[i + 1] - layout.bstart[i])
    private fun subOf(i: Int) = focusSub(layout.ipar.slice(layout.istart[i] until layout.istart[i + 1]).count { it < 0 }, layout.bstart[i + 1] - layout.bstart[i])
    private val focusNames = arrayOfNulls<GlowText>(web.items.size)
    private fun focusName(k: Int) = focusNames[k] ?: run {
        val i = (0 until layout.nL).first { k < layout.istart[it + 1] }
        GlowText(measurer.measure(
            ideaLabel(web.items[k].idea).ifBlank { "..." }, focusStyle, overflow = TextOverflow.Ellipsis, maxLines = 2,
            constraints = Constraints(maxWidth = (focusItemWidth(colsOf(i), subOf(i)) * density).toInt()),
        ))
    }.also { focusNames[k] = it }
    private val focusGroups = arrayOfNulls<GlowText>(web.branches.size)
    private fun focusGroup(g: Int, i: Int) = focusGroups[g] ?: run {
        val lc = lobeColor(web.branches[g].lobe)
        GlowText(measurer.measure(
            web.branches[g].branch.name, TextStyle(fontFamily = pageFont, fontSize = 20.sp, lineHeight = 22.sp, color = lc, textAlign = TextAlign.Center, shadow = Shadow(lc.copy(alpha = 0.7f), blurRadius = 8f * density)),
            overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = (focusGroupWidth(colsOf(i)) * density).toInt().coerceAtLeast(60)),
        ))
    }.also { focusGroups[g] = it }

    /** Every box of the unfocused map at zoom [z], in dp from the camera at the origin ([fitOverview]). */
    fun overviewBoxes(z: Float): List<FloatArray> = overviewBoxes(
        layout, lobeLabels.map { g -> g?.let { Size(it.tl.size.width / density, it.tl.size.height / density) } },
        vaultLabel(0f).tl.size.let { Size(it.width / density, it.height / density) }, z, pxdp,
    )

    private fun dpSize(g: GlowText) = Size(g.tl.size.width / density + NAME_PAD, g.tl.size.height / density + NAME_PAD)

    private val scenes = HashMap<Int, FocusScene>()

    /** The focused sub brain [i] as [planFocus] and [fitFocus] want it. */
    fun scene(i: Int): FocusScene = scenes.getOrPut(i) {
        val gs = layout.bstart[i] until layout.bstart[i + 1]
        val ks = layout.istart[i] until layout.istart[i + 1]
        FocusScene(
            Offset(layout.lx[i], layout.ly[i]),
            gs.map { Offset(layout.bx[it], layout.by[it]) }, gs.map { dpSize(focusGroup(it, i)) },
            ks.map { Offset(layout.ix[it], layout.iy[it]) }, IntArray(ks.count()) { layout.irings[ks.first + it] }, ks.map { dpSize(focusName(it)) },
            IntArray(ks.count()) { layout.ipar[ks.first + it].let { p -> if (p < 0) -1 else p - layout.bstart[i] } },
        )
    }

    private var planKey = Pair(-1, 0f)
    private var plan: FocusPlan? = null
    private fun planFor(i: Int, z: Float): FocusPlan {
        if (plan == null || planKey != Pair(i, z)) { plan = planFocus(scene(i), z, pxdp, force = true); planKey = Pair(i, z) }
        return plan!!
    }

    /** One frame of names, with the same world -> device transform as the low-res bitmap. [sel] is the focused lobe index or -1. */
    fun draw(scope: DrawScope, renderer: BrainRenderer, camX: Float, camY: Float, z: Float, sel: Int, sunSel: Boolean, sunSv: Float) = with(scope) {
        val ns = nodeScale(z)
        val u = pxSize * z
        val ox = renderer.W * pxSize / 2f
        val oy = renderer.H * pxSize / 2f
        fun dx(wx: Float) = (wx - camX) * u + ox
        fun dy(wy: Float) = (wy - camY) * u + oy
        fun dimOf(i: Int) = if ((sel < 0 && !sunSel) || sel == i) 1f else if (sel >= 0) FOCUS_DIM else 0.45f
        fun off(left: Float, top: Float, w: Float, h: Float) = left > size.width || left + w < 0f || top > size.height || top + h < 0f

        // Taken label boxes (left, top, right, bottom): a name that would land on another is left out this frame.
        val taken = ArrayList<FloatArray>()
        fun fits(left: Float, top: Float, w: Float, h: Float): Boolean {
            if (taken.any { left < it[2] && left + w > it[0] && top < it[3] && top + h > it[1] }) return false
            taken.add(floatArrayOf(left, top, left + w, top + h))
            return true
        }
        // The heart and its glow: names keep off it.
        val skL = dx(SUN_X - 14f); val skR = dx(SUN_X + 14f); val skT = dy(SUN_Y - 34f); val skB = dy(SUN_Y + 3f)
        taken.add(floatArrayOf(skL, skT, skR, skB))

        // The focused sub brain: every name beside its node, off every ring, at the spot the plan gives it.
        if (sel >= 0 && renderer.ipx.size >= layout.nI) {
            val p = planFor(sel, z)
            fun put(g: GlowText, nx: Float, ny: Float, at: Offset?) {
                if (at == null) return
                val left = nx + at.x * density; val top = ny + at.y * density
                val w = g.tl.size.width.toFloat(); val h = g.tl.size.height.toFloat()
                if (off(left, top, w, h)) return
                taken.add(floatArrayOf(left, top, left + w, top + h))
                g.draw(this, left, top, 1f)
            }
            for (g in layout.bstart[sel] until layout.bstart[sel + 1]) put(focusGroup(g, sel), dx(layout.bx[g]), dy(layout.by[g]), p.groups[g - layout.bstart[sel]])
            for (k in layout.istart[sel] until layout.istart[sel + 1]) put(focusName(k), dx(renderer.ipx[k]), dy(renderer.ipy[k]), p.items[k - layout.istart[sel]])
        }

        // A focused sub brain shows nothing of the other lobes: no names, groups or items.
        val names = if (sel >= 0) emptyList() else lobeNames(layout, lobeLabels.map { g -> g?.let { Size(it.tl.size.width.toFloat(), it.tl.size.height.toFloat()) } }, u, ns)
        for (i in names.indices) {
            val g = lobeLabels[i] ?: continue
            val tl = g.tl
            val w = tl.size.width.toFloat(); val h = tl.size.height.toFloat()
            val at = names[i] ?: continue
            val left = at.x - camX * u + ox; val top = at.y - camY * u + oy
            if (off(left, top, w, h)) continue
            taken.add(floatArrayOf(left, top, left + w, top + h))
            g.draw(this, left, top, dimOf(i))
        }

        // No vault label while a sub brain is focused.
        if (sel < 0) {
            val v = vaultLabel(sunSv)
            v.draw(this, dx(SUN_X) - v.tl.size.width / 2f, dy(SUN_Y + 12f), 1f)
        }

        // Other lobes show their group and item names once zoomed in far enough.
        val groupAlpha = ((z - GROUP_ZOOM) / 0.3f).coerceIn(0f, 1f)
        for (i in 0 until layout.nL) {
            if (sel >= 0 || groupAlpha <= 0f) continue
            for (g in layout.bstart[i] until layout.bstart[i + 1]) {
                val tl = groupLabels[g].tl
                val left = dx(layout.bx[g]) - tl.size.width / 2f
                val top = dy(layout.by[g]) + 9f * ns * u
                if (off(left, top, tl.size.width.toFloat(), tl.size.height.toFloat()) || !fits(left, top, tl.size.width.toFloat(), tl.size.height.toFloat())) continue
                groupLabels[g].draw(this, left, top, groupAlpha * dimOf(i))
            }
        }

        val itemAlpha = ((z - (itemZ - 0.5f)) / 0.5f).coerceIn(0f, 1f)
        if (itemAlpha > 0f && renderer.ipx.size >= layout.nI) {
            // A name is as wide as the cell it hangs in (cells are where items sit), one line unless the rows are far enough apart for two.
            val boxDp = (CELL_X * u / density - 5f).coerceIn(34f, LABEL_WIDTH)
            val lines = if (CELL_Y * u / density >= 34f) 2 else 1
            val key = (boxDp / 6f).toInt() * 2 + (lines - 1)
            val boxW = boxDp * density; val boxH = (if (lines == 2) 34f else 19f) * density
            val style = TextStyle(
                fontFamily = focusStyle.fontFamily, fontSize = if (boxDp >= 56f) 15.sp else 12.sp, lineHeight = if (boxDp >= 56f) 15.sp else 12.sp,
                color = Color(0xFFE6DCFF), textAlign = TextAlign.Center, shadow = Shadow(Color(0xFFB69AFF).copy(alpha = 0.85f), blurRadius = 8f * density),
            )
            var built = 0
            for (i in 0 until layout.nL) {
                if (sel >= 0) continue
                for (k in layout.istart[i] until layout.istart[i + 1]) {
                    // cull before measuring: a label is at most boxW wide and boxH tall, centred under its dot
                    val cx = dx(renderer.ipx[k]); val top = dy(renderer.ipy[k]) + 7f * density
                    if (off(cx - boxW / 2f, top, boxW, boxH)) continue
                    val cached = itemLabels[k]
                    val label = if (cached != null && (cached.key == key || built >= 6)) cached else if (built >= 6) continue else run {
                        built++
                        ItemLabel(GlowText(measurer.measure(
                            ideaLabel(web.items[k].idea).ifBlank { "..." }, style, overflow = TextOverflow.Ellipsis, maxLines = lines,
                            constraints = Constraints(maxWidth = boxW.toInt().coerceAtLeast(40)),
                        )), key).also { itemLabels[k] = it }
                    }
                    val tl = label.g.tl
                    if (!fits(cx - tl.size.width / 2f, top, tl.size.width.toFloat(), tl.size.height.toFloat())) continue
                    label.g.draw(this, cx - tl.size.width / 2f, top, itemAlpha * dimOf(i))
                }
            }
        }

        if (layout.nL == 0) {
            val hint = measurer.measure("TAP THE BRAIN TO GROW A LOBE", hintStyle)
            drawText(hint, topLeft = Offset(dx(0f) - hint.size.width / 2f, dy(44f)))
        }
    }
}
