package app.vesper.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.window.DialogProperties

internal actual fun imageBitmapOf(pixels: IntArray, w: Int, h: Int): ImageBitmap =
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * Compose's Paint() on Android has ANTI_ALIAS and DITHER set; the old android Paints had both off.
 * Dither shifts blended/gradient colours by one level per channel, so it must be off for identical pixels.
 */
internal actual fun hardPaint(): Paint = Paint().also {
    it.isAntiAlias = false
    it.nativePaint.isDither = false
}

@Composable
internal actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

internal actual fun edgeToEdgeDialog() = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
