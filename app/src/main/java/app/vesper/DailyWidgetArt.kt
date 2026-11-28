package app.vesper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import app.vesper.ui.CANDLE_H
import app.vesper.ui.CANDLE_W
import app.vesper.ui.candlePixels
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the Daily widget's candles sit, in dp: [rows] gives the cells per row (each row centred, the whole block centred in the body),
 * every cell [cellW] x [cellH] with candles [scale] dp per pixel. [shown] items are drawn; [more] > 0 adds a final "+more" cell.
 */
data class WidgetGrid(val rows: List<Int>, val scale: Float, val cellW: Float, val cellH: Float, val shown: Int, val more: Int)

const val WIDGET_PAD = 9f      // dp between the widget edge and its contents (the header and cells carry their own padding, so it may touch the frame ring)
const val WIDGET_HEADER = 28f  // dp, the header row's height
private const val MIN_CELL_W = 48f
private const val MIN_SCALE = 1.5f
private const val MAX_SCALE = 6f
private const val MAX_BODY_BYTES = 2_500_000f

/** The name's text size (dp) under a candle of [u] dp per pixel. */
fun widgetNameSize(u: Float) = (4.5f * u).coerceIn(11f, 20f)
/** A cell's height (dp): the flame's glow room, the 20-row candle, and two lines of name. */
private fun cellNeed(u: Float) = (CANDLE_H + 2) * u + 2f + 2f * widgetNameSize(u) + 2f

/** The body's room (dp) inside a widget [wDp] x [hDp]. */
fun widgetBody(wDp: Float, hDp: Float) = Pair(wDp - 2 * WIDGET_PAD, hDp - 2 * WIDGET_PAD - WIDGET_HEADER)

/** [n] cells in rows of at most [cols], as even as possible (the longer rows first): 7 in 4 -> 4+3, 10 in 4 -> 4+3+3. */
fun balancedRows(n: Int, cols: Int): List<Int> {
    if (n <= 0) return emptyList()
    val r = ceil(n / cols.toFloat()).toInt()
    return List(r) { if (it < n % r) n / r + 1 else n / r }
}

private fun fitCells(n: Int, bw: Float, bh: Float): WidgetGrid? {
    if (n <= 0) return WidgetGrid(emptyList(), MAX_SCALE, bw, 0f, 0, 0)
    var best: WidgetGrid? = null
    for (c in 1..min(max(1, floor(bw / MIN_CELL_W).toInt()), n)) {
        val rows = balancedRows(n, c); val cw = bw / rows.max()
        val u = generateSequence(MAX_SCALE) { it - 0.5f }.takeWhile { it >= MIN_SCALE }.firstOrNull { 18f * it <= cw && rows.size * cellNeed(it) <= bh } ?: continue
        val g = WidgetGrid(rows, u, cw, cellNeed(u), n, 0)
        if (best == null || g.scale > best.scale || (g.scale == best.scale && g.rows.size < best.rows.size)) best = g
    }
    return best
}

/**
 * How [count] candles fill a widget [wDp] x [hDp]: the column count with the biggest candles wins, rows balanced and centred. Nothing scrolls:
 * if even [MIN_SCALE] cannot hold them all, as many as fit are shown plus a "+N" cell.
 */
fun widgetLayout(count: Int, wDp: Float, hDp: Float): WidgetGrid {
    val (bw, bh) = widgetBody(wDp, hDp)
    fitCells(count, bw, bh)?.let { return it }
    for (k in count - 1 downTo 0) fitCells(k + 1, bw, bh)?.let { return it.copy(shown = k, more = count - k) }
    return WidgetGrid(listOf(1), MIN_SCALE, bw, cellNeed(MIN_SCALE), 0, count) // a body too tiny for even one cell
}

/** The density the body bitmap is drawn at: [d], lowered so the picture stays under ~2.5 MB (RemoteViews' memory limit). */
fun widgetBodyDensity(bwDp: Float, bhDp: Float, d: Float) = min(d, sqrt(MAX_BODY_BYTES / 4f / (bwDp * bhDp)))

/** The widget's pictures: RemoteViews cannot use a custom font, so the text and candles are drawn here, with the app's own fonts and candle. */
object WidgetArt {
    private const val FONT_DIR = "composeResources/app.vesper.res/font/" // where the shared module packs its fonts in the APK
    private val fonts = HashMap<String, Typeface>()
    private fun font(ctx: Context, file: String): Typeface = synchronized(fonts) {
        fonts.getOrPut(file) { try { Typeface.createFromAsset(ctx.assets, "$FONT_DIR$file.ttf") } catch (e: Exception) { Typeface.DEFAULT } }
    }

    private const val LILAC = 0xFFC9B2FF.toInt()
    private const val COUNT = 0xFFB69AFF.toInt()
    private const val DIM = 0xFF8E7FB0.toInt()

    private const val FRAME_EDGE = 0xFF9A6CFF.toInt()
    private const val FRAME_LINE = 0xFF3A2066.toInt()
    private const val PANEL = 0xFF0B0614.toInt()
    private const val FRAME_INSET = 2f // dp of transparent margin, so the launcher's own rounded clipping never touches the pixel corners
    private const val FRAME_UNIT = 2f  // dp per frame pixel

    /** Frame cell depth: 0 outside the stepped corners, 1 bright edge, 2 dark inner line, 3 panel. Corner cells (0,0) (1,0) (0,1) are cut: two steps. */
    internal fun frameDepth(x: Int, y: Int, cols: Int, rows: Int): Int {
        fun inside(a: Int, b: Int): Boolean {
            if (a < 0 || b < 0 || a >= cols || b >= rows) return false
            val dx = minOf(a, cols - 1 - a); val dy = minOf(b, rows - 1 - b)
            return !(dx < 2 && dy < 2 && dx + dy < 2)
        }
        if (!inside(x, y)) return 0
        val n = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
        if (n.any { !inside(x + it.first, y + it.second) }) return 1
        // the dark line: inside cells that touch an edge cell (4-neighbour)
        if (n.any { (dx, dy) -> n.any { !inside(x + dx + it.first, y + dy + it.second) } && inside(x + dx, y + dy) }) return 2
        return 3
    }

    /** The widget's pixel frame and panel in one hard-edged picture of [wDp] x [hDp] (no anti-aliasing), re-drawn for each size; capped like the body. */
    fun frame(wDp: Float, hDp: Float, d: Float): Bitmap {
        val fd = min(d, sqrt(MAX_BODY_BYTES * 1.6f / 4f / (wDp * hDp)))
        val w = (wDp * fd).toInt().coerceAtLeast(1); val h = (hDp * fd).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val x0 = FRAME_INSET * fd; val y0 = FRAME_INSET * fd; val fw = w - 2 * x0; val fh = h - 2 * y0
        val cols = max(5, Math.round(fw / (FRAME_UNIT * fd))); val rows = max(5, Math.round(fh / (FRAME_UNIT * fd)))
        val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
        val colors = intArrayOf(0, FRAME_EDGE, FRAME_LINE, PANEL)
        for (y in 0 until rows) for (x in 0 until cols) {
            val depth = frameDepth(x, y, cols, rows); if (depth == 0) continue
            p.color = colors[depth]
            c.drawRect(Math.round(x0 + fw * x / cols).toFloat(), Math.round(y0 + fh * y / rows).toFloat(), Math.round(x0 + fw * (x + 1) / cols).toFloat(), Math.round(y0 + fh * (y + 1) / rows).toFloat(), p)
        }
        return bmp
    }

    /** One 3 x 3 unit corner of the crisp frame ([u] device pixels per unit, no anti-aliasing): two steps, bright edge, dark line, panel. Flipped for the other corners. */
    fun corner(u: Int, flipX: Boolean, flipY: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(3 * u, 3 * u, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
        val colors = intArrayOf(0, FRAME_EDGE, FRAME_LINE, PANEL)
        for (y in 0..2) for (x in 0..2) {
            val depth = frameDepth(if (flipX) 2 - x else x, if (flipY) 2 - y else y, 6, 6); if (depth == 0) continue
            p.color = colors[depth]; c.drawRect((x * u).toFloat(), (y * u).toFloat(), ((x + 1) * u).toFloat(), ((y + 1) * u).toFloat(), p)
        }
        return bmp
    }

    /** Frame pixel unit: 3 dp rounded to whole device pixels, so every step is a hard-edged block. */
    fun unitPx(d: Float) = Math.round(3f * d).coerceAtLeast(1)

    /** Transparent margin (px) around the frame so a launcher clipping widgets to a corner radius of [r] px never touches the stepped corner's outer points. */
    fun insetPx(u: Int, r: Float): Int {
        fun inside(x: Float, y: Float, i: Int) = Math.hypot((r - x - i).toDouble(), (r - y - i).toDouble()) <= r - 2.0
        var i = 0
        while (i < r.toInt() && !(inside(2f * u, 0f, i) && inside(u.toFloat(), u.toFloat(), i) && inside(0f, 2f * u, i))) i++
        return i
    }

    /** Android 12+: sizes the crisp frame's views (edge/line strips, panel, corners) in exact device pixels and sets the corner pictures; solid strips never blur. */
    @androidx.annotation.RequiresApi(31)
    fun layoutFrame(v: android.widget.RemoteViews, d: Float, radiusPx: Float) {
        val u = unitPx(d); val px = android.util.TypedValue.COMPLEX_UNIT_PX; val all = android.view.ViewGroup.LayoutParams.MATCH_PARENT.toFloat()
        val L = android.widget.RemoteViews.MARGIN_LEFT; val T = android.widget.RemoteViews.MARGIN_TOP
        val R_ = android.widget.RemoteViews.MARGIN_RIGHT; val B = android.widget.RemoteViews.MARGIN_BOTTOM
        fun margin(id: Int, type: Int, n: Int) = v.setViewLayoutMargin(id, type, n.toFloat(), px)
        val inset = insetPx(u, max(radiusPx, 22f * d))
        for (t in intArrayOf(L, T, R_, B)) { margin(R.id.fc_card, t, inset); margin(R.id.fc_panel, t, u) }
        for (id in intArrayOf(R.id.fc_top_edge, R.id.fc_top_line, R.id.fc_bottom_edge, R.id.fc_bottom_line)) {
            v.setViewLayoutWidth(id, all, px); v.setViewLayoutHeight(id, u.toFloat(), px); margin(id, L, 3 * u - 1); margin(id, R_, 3 * u - 1) // 1 px under the corners: no seam
        }
        margin(R.id.fc_top_line, T, u); margin(R.id.fc_bottom_line, B, u)
        for (id in intArrayOf(R.id.fc_left_edge, R.id.fc_left_line, R.id.fc_right_edge, R.id.fc_right_line)) {
            v.setViewLayoutWidth(id, u.toFloat(), px); v.setViewLayoutHeight(id, all, px); margin(id, T, 3 * u - 1); margin(id, B, 3 * u - 1)
        }
        margin(R.id.fc_left_line, L, u); margin(R.id.fc_right_line, R_, u)
        for (id in intArrayOf(R.id.fc_tl, R.id.fc_tr, R.id.fc_bl, R.id.fc_br)) { v.setViewLayoutWidth(id, 3f * u, px); v.setViewLayoutHeight(id, 3f * u, px) }
        v.setImageViewBitmap(R.id.fc_tl, corner(u, false, false)); v.setImageViewBitmap(R.id.fc_tr, corner(u, true, false))
        v.setImageViewBitmap(R.id.fc_bl, corner(u, false, true)); v.setImageViewBitmap(R.id.fc_br, corner(u, true, true))
    }

    private fun paint(tf: Typeface, px: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = px; this.color = color }

    /** The header: "daily" in the title font, and the count "2 / 7"; all of it opens Vesper on the Daily card. */
    fun header(ctx: Context, wDp: Float, lit: Int, total: Int, d: Float): Bitmap {
        val w = (wDp * d).toInt().coerceAtLeast(1); val h = (WIDGET_HEADER * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val title = paint(font(ctx, "jacquard24"), 26f * d, LILAC).apply { setShadowLayer(6f * d, 0f, 0f, 0x99C9B2FF.toInt()) }
        val tm = title.fontMetrics
        c.drawText("daily", 4f * d, h / 2f - (tm.ascent + tm.descent) / 2f, title)
        if (total > 0) {
            val count = paint(font(ctx, "pressstart2p"), 9f * d, COUNT)
            c.drawText("$lit / $total", 4f * d + title.measureText("daily") + 12f * d, h / 2f - (count.ascent() + count.descent()) / 2f + 2f * d, count)
        }
        return bmp
    }

    /** The whole body in one picture: every candle with its name, laid out as [g] says (rows centred, the block centred), at [d] pixels per dp (see [widgetBodyDensity]). */
    fun body(ctx: Context, items: List<Pair<String, Boolean>>, g: WidgetGrid, bwDp: Float, bhDp: Float, d: Float): Bitmap {
        val w = (bwDp * d).toInt().coerceAtLeast(1); val h = (bhDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val cw = g.cellW * d; val ch = g.cellH * d
        val top = (h - g.rows.size * ch) / 2f
        var i = 0
        g.rows.forEachIndexed { r, len ->
            repeat(len) { k ->
                c.save(); c.translate(floor((w - len * cw) / 2f + k * cw), floor(top + r * ch))
                if (i < g.shown) items[i].let { drawCell(c, ctx, it.first, it.second, g.cellW, g.cellH, g.scale, d) } else drawMore(c, ctx, g.more, g.cellW, g.cellH, g.scale, d)
                c.restore(); i++
            }
        }
        return bmp
    }

    private fun drawMore(c: Canvas, ctx: Context, more: Int, wDp: Float, hDp: Float, u: Float, d: Float) {
        val text = paint(font(ctx, "vt323"), widgetNameSize(u) * 1.5f * d, DIM).apply { textAlign = Paint.Align.CENTER }
        c.drawText("+$more", wDp * d / 2f, hDp * d / 2f - (text.ascent() + text.descent()) / 2f, text)
    }

    /** One cell at the canvas origin: the candle (the Daily card's own pixels, and its glow when lit) and its name under it, the pair centred in [wDp] x [hDp]. */
    private fun drawCell(c: Canvas, ctx: Context, name: String, lit: Boolean, wDp: Float, hDp: Float, u: Float, d: Float) {
        val w = (wDp * d).toInt().coerceAtLeast(1); val h = (hDp * d).toInt().coerceAtLeast(1)
        val px = max(1f, floor(u * d))
        val text = paint(font(ctx, "vt323"), widgetNameSize(u) * d, if (lit) Color.WHITE else DIM)
        val lines = StaticLayout.Builder.obtain(name, 0, name.length, text, (w - 6f * d).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
        val lineH = text.fontMetrics.let { it.descent - it.ascent }
        val glowRoom = 2f * px; val gap = 2f * d
        val top = max(0f, floor((h - (glowRoom + CANDLE_H * px + gap + 2 * lineH)) / 2f))
        val left = floor((w - CANDLE_W * px) / 2f); val candleTop = top + glowRoom
        if (lit) {
            val cx = left + 4.5f * px; val cy = candleTop + 4f * px; val r = 9f * px
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(cx, cy, r, intArrayOf(0x52B69AFF, 0), null, Shader.TileMode.CLAMP) }
            c.drawCircle(cx, cy, r, glow)
        }
        val p = Paint()
        for ((x, y, rgb) in candlePixels(lit, 1)) { p.color = 0xFF000000.toInt() or rgb; c.drawRect(left + x * px, candleTop + y * px, left + (x + 1) * px, candleTop + (y + 1) * px, p) }
        c.save(); c.translate((w - lines.width) / 2f, candleTop + CANDLE_H * px + gap); lines.draw(c); c.restore()
    }

    /** The body of a widget with no candles: a cold candle and a friendly line. */
    fun empty(ctx: Context, wDp: Float, hDp: Float, d: Float): Bitmap {
        val w = (wDp * d).toInt().coerceAtLeast(1); val h = (hDp * d).toInt().coerceAtLeast(1)
        val u = min(3f, max(1f, (h / d - 24f) / (CANDLE_H + 4))) // the candle shrinks in a very low widget
        val px = max(1f, floor(u * d)); val text = paint(font(ctx, "pressstart2p"), 8f * d, DIM)
        val msg = StaticLayout.Builder.obtain("no daily candles yet", 0, 20, text, (w - 12f * d).toInt().coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val gap = 8f * d; val top = max(0f, (h - (CANDLE_H * px + gap + msg.height)) / 2f); val left = floor((w - CANDLE_W * px) / 2f)
        val p = Paint()
        for ((x, y, rgb) in candlePixels(false)) { p.color = 0xFF000000.toInt() or rgb; c.drawRect(left + x * px, top + y * px, left + (x + 1) * px, top + (y + 1) * px, p) }
        c.save(); c.translate(0f, top + CANDLE_H * px + gap); msg.draw(c); c.restore()
        return bmp
    }
}
