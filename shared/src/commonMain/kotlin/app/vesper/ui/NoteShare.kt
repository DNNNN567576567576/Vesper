package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

// A note rendered as a picture to share: dark panel, lilac frame, the world's name, serif title and body, a dim "vesper" in the corner.

internal const val SHARE_W = 1080
private const val PAD = 84
private const val BORDER = 2f
private val ShareBg = Color(0xFF120A1C)
private val ShareLilac = Color(0xFFB69AFF)
private val ShareTitle = Color(0xFFE6DCFF)
private val ShareBody = Color(0xFFD6CBF5)
private val ShareDim = Color(0xFF6B5A9A)

/** The picture's height for the measured pieces (px): padding, label, gaps, title, body and the footer line. */
internal fun noteImageHeight(labelH: Int, titleH: Int, bodyH: Int, footerH: Int): Int =
    PAD + labelH + 40 + (if (titleH > 0) titleH + 36 else 0) + bodyH + 56 + footerH + PAD

/** [label] is the world's name (any case), drawn uppercase and letter-spaced; text wraps at [SHARE_W] and the picture grows as tall as it needs. */
internal fun renderNoteImage(fonts: FontFamily.Resolver, label: String, title: String, body: String, pixelFont: FontFamily): ImageBitmap {
    val measurer = TextMeasurer(fonts, Density(1f), LayoutDirection.Ltr) // 1 sp = 1 px, whatever the screen
    val w = Constraints(maxWidth = SHARE_W - 2 * PAD)
    val labelS = TextStyle(fontFamily = pixelFont, fontSize = 20.sp, color = ShareLilac, letterSpacing = 6.sp)
    val titleS = TextStyle(fontFamily = FontFamily.Serif, fontSize = 56.sp, lineHeight = 72.sp, color = ShareTitle)
    val bodyS = TextStyle(fontFamily = FontFamily.Serif, fontSize = 40.sp, lineHeight = 64.sp, color = ShareBody) // 1.6
    val footS = TextStyle(fontFamily = pixelFont, fontSize = 16.sp, color = ShareDim, textAlign = TextAlign.End)
    val l = measurer.measure(AnnotatedString(label.uppercase()), labelS, constraints = w)
    val t = title.trim().let { if (it.isEmpty()) null else measurer.measure(AnnotatedString(it), titleS, constraints = w) }
    val b = measurer.measure(AnnotatedString(body.trim()), bodyS, constraints = w)
    val f = measurer.measure(AnnotatedString("vesper"), footS, constraints = w)
    val h = noteImageHeight(l.size.height, t?.size?.height ?: 0, b.size.height, f.size.height)
    val img = ImageBitmap(SHARE_W, h)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(SHARE_W.toFloat(), h.toFloat())) {
        drawRect(ShareBg)
        drawRect(ShareLilac, Offset(BORDER / 2, BORDER / 2), Size(SHARE_W - BORDER, h - BORDER), style = Stroke(BORDER))
        var y = PAD.toFloat()
        drawText(l, topLeft = Offset(PAD.toFloat(), y)); y += l.size.height + 40
        if (t != null) { drawText(t, topLeft = Offset(PAD.toFloat(), y)); y += t.size.height + 36 }
        drawText(b, topLeft = Offset(PAD.toFloat(), y)); y += b.size.height + 56
        drawText(f, topLeft = Offset(PAD.toFloat(), y))
    }
    return img
}
