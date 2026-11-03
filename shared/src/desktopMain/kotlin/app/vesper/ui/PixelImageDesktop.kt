package app.vesper.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
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

// shortcut: not pixel-compared against Android yet; check RotArt/Brain hashes on desktop in M3.
internal actual fun hardPaint(): Paint = Paint().also {
    it.isAntiAlias = false
    it.skiaPaint.isDither = false
}

// shortcut: desktop has no system back; M3 can map Esc here if the desktop shell wants it.
@Composable
internal actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {}

internal actual fun edgeToEdgeDialog() = DialogProperties(usePlatformDefaultWidth = false)
