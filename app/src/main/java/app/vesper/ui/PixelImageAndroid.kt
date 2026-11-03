package app.vesper.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativePaint

// Platform seam for RotArt: the only place RotArt's pixel art touches android.graphics.Bitmap.
// Compose common can draw into an ImageBitmap and read it back (readPixels) but cannot create one from
// raw pixels or switch dithering off, so this file does those two things.
// At M3 both become `expect fun` in commonMain with these bodies as the androidMain `actual`s.

/** ARGB_8888 image from non-premultiplied 0xAARRGGBB [pixels], row-major, [w] x [h]. Call rarely (sprites), not per frame. */
internal fun imageBitmapOf(pixels: IntArray, w: Int, h: Int): ImageBitmap =
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * Compose's Paint() on Android has ANTI_ALIAS and DITHER set; the old android Paints had both off.
 * Dither shifts blended/gradient colours by one level per channel, so it must be off for identical pixels.
 */
internal fun hardPaint(): Paint = Paint().also {
    it.isAntiAlias = false
    it.nativePaint.isDither = false
}

/*
Intended desktop actual (desktopMain, Skia via org.jetbrains.skia), same contract: unpremultiplied ARGB in,
an ImageBitmap that draws like Android's Bitmap.createBitmap(int[], ...).

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal actual fun imageBitmapOf(pixels: IntArray, w: Int, h: Int): ImageBitmap {
    // Skia BGRA_8888 little-endian bytes == 0xAARRGGBB ints, the same layout Android uses.
    val bytes = ByteArray(w * h * 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels)
    val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    // toComposeImageBitmap copies into a Skia Bitmap; Skia premultiplies when it draws an UNPREMUL source.
    return Image.makeRaster(info, bytes, w * 4).toComposeImageBitmap()
}
// Desktop: reach the org.jetbrains.skia.Paint (asFrameworkPaint() / the desktop platform-paint accessor) and set
// isDither = false the same way, in case Compose desktop enables dither too (not checked yet).
internal actual fun hardPaint(): Paint = Paint().also { it.isAntiAlias = false; it.asFrameworkPaint().isDither = false }

// Pix itself needs no desktop code: ImageBitmap(w, h) + Canvas(imageBitmap) are common Compose and are
// backed by a mutable Skia Bitmap on desktop. Verify with RotArtPixelTest's hashes (desktop may differ by
// Skia version; compare visually if they do).
*/
