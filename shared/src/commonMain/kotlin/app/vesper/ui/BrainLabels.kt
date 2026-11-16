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
import kotlin.math.min
import app.vesper.data.Lobe

private fun noteStyle(font: FontFamily) = TextStyle(fontFamily = font, fontSize = 19.sp, lineHeight = 21.sp)
private fun groupStyle(font: FontFamily) = TextStyle(fontFamily = font, fontSize = 9.sp, lineHeight = 14.sp, letterSpacing = 1.sp)
private fun worldTitleStyle(font: FontFamily) = TextStyle(fontFamily = font, fontSize = 34.sp, textAlign = TextAlign.Center)

/** How tall (dp, with [NAME_PAD]) a two-line note name, a one-line group name and a world's title get: what a world reserves. */
internal fun nameHeights(measurer: TextMeasurer, density: Float, pageFont: FontFamily, titleFont: FontFamily, pixelFont: FontFamily): FloatArray {
    fun h(text: String, style: TextStyle) = measurer.measure(text, style).size.height / density + NAME_PAD
    return floatArrayOf(h("Ag\nAg", noteStyle(pageFont)), h("AG", groupStyle(pixelFont)), h("Ag", worldTitleStyle(titleFont)))
}

/**
 * All the names on the map, measured once and drawn at full resolution over the low-res frame. The main map writes only sub brain
 * names (and the vault's); a dimension names its core, groups and notes in the boxes [dimensionLayout] keeps for them.
 */
internal class BrainLabels(
    private val measurer: TextMeasurer, private val density: Float, private val pxSize: Float,
    private val pageFont: FontFamily, private val titleFont: FontFamily, private val pixelFont: FontFamily,
    private val lobes: List<Lobe>, private val web: Web, private val layout: Layout,
) {
    private val pxdp = pxSize / density

    /** Main-map names (sub brains, vault) in the readable pixel font notes use; blackletter is kept for big titles. */
    private fun labelStyle(c: Color, glow: Color) =
        noteStyle(pageFont).copy(fontSize = 20.sp, color = c, shadow = Shadow(glow.copy(alpha = 0.7f), blurRadius = 10f * density))

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
    private var vault: Pair<Float, GlowText>? = null
    private fun vaultLabel(sv: Float): GlowText = vault?.takeIf { it.first == sv }?.second ?: run {
        val c = lerp(Color(0xFFC9B6FF), Color(0xFF6F6080), sv)
        GlowText(measurer.measure("vault", labelStyle(c, if (sv > 0.5f) Color.Transparent else c), maxLines = 1)).also { vault = sv to it }
    }
    private val hintStyle = TextStyle(fontFamily = pixelFont, fontSize = 9.sp, color = Px.TextDim)
    /** A world's title at its top: the sub brain's name, big, in its shade and glowing. */
    private fun coreLabel(i: Int) = lobeLabels[i]?.let { coreNames.getOrPut(i) {
        val c = Color(layout.lcol[i] or 0xFF000000.toInt())
        val box = web.dim?.coreName
        GlowText(measurer.measure(
            lobes[i].name, worldTitleStyle(titleFont).copy(color = c, shadow = Shadow(c.copy(alpha = 0.7f), blurRadius = 14f * density)),
            overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = ((if (box != null) box[2] - box[0] else 300f) * density).toInt().coerceAtLeast(60)),
        ))
    } }
    private val coreNames = HashMap<Int, GlowText>()
    /** A star's name, pale lavender, reading away from its star (left-aligned on the right, right-aligned on the left). */
    private val focusNames = arrayOfNulls<GlowText>(web.items.size)
    private fun focusName(k: Int) = focusNames[k] ?: run {
        val box = web.items[k].name
        val done = web.items[k].idea.done // a done note's name is dim grey, unlit
        GlowText(measurer.measure(
            ideaLabel(web.items[k].idea).ifBlank { "..." },
            noteStyle(pageFont).copy(
                color = if (done) Color(0xFF6F6880) else Color(0xFFE6DCFF), textAlign = if (box[0] + box[2] > 0f) TextAlign.Left else TextAlign.Right,
                shadow = if (done) null else Shadow(Color(0xFF9C84FF).copy(alpha = 0.8f), blurRadius = 8f * density),
            ),
            overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = (min(PORTAL_ITEM_W, box[2] - box[0]) * density).toInt()),
        ))
    }.also { focusNames[k] = it }
    /** "empty" by an empty group's ghost star, dim. */
    private val ghostLabel by lazy {
        GlowText(measurer.measure("empty", noteStyle(pageFont).copy(color = Color(0xFF6F6880), textAlign = TextAlign.Left), maxLines = 1))
    }
    /** A group's name in small pixel capitals, in its sub brain's shade. */
    private val focusGroups = arrayOfNulls<GlowText>(web.branches.size)
    private fun focusGroup(g: Int) = focusGroups[g] ?: run {
        val lc = Color(layout.lcol.getOrElse(lobes.indexOfFirst { it.id == web.focus }) { 0xC9B6FF } or 0xFF000000.toInt())
        val box = web.branches[g].name
        GlowText(measurer.measure(
            web.branches[g].branch.name.uppercase(),
            groupStyle(pixelFont).copy(color = lc, shadow = Shadow(lc.copy(alpha = 0.6f), blurRadius = 6f * density)),
            overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (min(PORTAL_GROUP_W, box[2] - box[0]) * density).toInt().coerceAtLeast(40)),
        ))
    }.also { focusGroups[g] = it }

    /** Every box of the unfocused map at zoom [z], in dp from the camera at the origin ([fitOverview]). */
    fun overviewBoxes(z: Float): List<FloatArray> = overviewBoxes(
        layout, lobeLabels.map { g -> g?.let { Size(it.tl.size.width / density, it.tl.size.height / density) } },
        vaultLabel(0f).tl.size.let { Size(it.width / density, it.height / density) }, z, pxdp,
    )

    /** One frame of names, with the same world -> device transform as the low-res bitmap. [sel] is the focused lobe index or -1. */
    fun draw(scope: DrawScope, renderer: BrainRenderer, camX: Float, camY: Float, z: Float, sel: Int, sunSel: Boolean, sunSv: Float, fade: Float = 1f) = with(scope) {
        val ns = nodeScale(z)
        val u = pxSize * z
        val ox = renderer.W * pxSize / 2f
        val oy = renderer.H * pxSize / 2f
        fun dx(wx: Float) = (wx - camX) * u + ox
        fun dy(wy: Float) = (wy - camY) * u + oy
        fun dimOf(i: Int) = (if (sunSel) 0.45f else 1f) * fade
        fun off(left: Float, top: Float, w: Float, h: Float) = left > size.width || left + w < 0f || top > size.height || top + h < 0f

        // Taken label boxes (left, top, right, bottom): a name that would land on another is left out this frame.
        val taken = ArrayList<FloatArray>()
        // The heart and its glow: names keep off it.
        val skL = dx(SUN_X - 14f); val skR = dx(SUN_X + 14f); val skT = dy(SUN_Y - 34f); val skB = dy(SUN_Y + 3f)
        taken.add(floatArrayOf(skL, skT, skR, skB))

        // A world: every name in the box its layout keeps for it, hugging its spot's side, centred down the box.
        if (sel >= 0) {
            fun put(g: GlowText, nx: Float, ny: Float, box: FloatArray?) {
                if (box == null) return
                val w = g.tl.size.width.toFloat(); val h = g.tl.size.height.toFloat()
                val mid = (box[0] + box[2]) / 2f
                val left = nx + when { mid > 0.5f -> box[0] * density; mid < -0.5f -> box[2] * density - w; else -> -w / 2f }
                val top = ny + (box[1] + box[3]) / 2f * density - h / 2f
                if (off(left, top, w, h)) return
                taken.add(floatArrayOf(left, top, left + w, top + h))
                g.draw(this, left, top, 1f)
            }
            for (g in layout.bstart[sel] until layout.bstart[sel + 1]) put(focusGroup(g), dx(layout.bx[g]), dy(layout.by[g]), web.branches[g].name)
            for (k in layout.istart[sel] until layout.istart[sel + 1]) put(focusName(k), dx(layout.ix[k]), dy(layout.iy[k]), web.items[k].name)
            for (g in layout.bstart[sel] until layout.bstart[sel + 1]) web.branches[g].ghost?.let { put(ghostLabel, dx(it.x), dy(it.y), web.branches[g].ghostName) }
            coreLabel(sel)?.let { put(it, dx(0f), dy(0f), web.dim?.coreName) }
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
            if (fade > 0f) v.draw(this, dx(SUN_X) - v.tl.size.width / 2f, dy(SUN_Y + 12f), fade)
        }

        if (layout.nL == 0 && sel < 0) {
            val hint = measurer.measure("TAP THE BRAIN TO GROW A SUB BRAIN", hintStyle)
            drawText(hint, topLeft = Offset(dx(0f) - hint.size.width / 2f, dy(44f)))
        }
    }
}
