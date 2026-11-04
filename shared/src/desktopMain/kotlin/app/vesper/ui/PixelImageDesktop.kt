package app.vesper.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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

private val backHandlers = mutableListOf<() -> Unit>()

/** Esc: runs the most recently composed enabled [BackHandler]; false when none is active (key passes through). */
fun handleDesktopBack(): Boolean {
    val h = backHandlers.lastOrNull() ?: return false
    h()
    return true
}

// shortcut: order = registration order, not strictly composition-tree order; fine while only one is enabled at a time.
@Composable
internal actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val h: () -> Unit = { latest() }
        if (enabled) backHandlers.add(h)
        onDispose { backHandlers.remove(h) }
    }
}

internal actual fun edgeToEdgeDialog() = DialogProperties(usePlatformDefaultWidth = false)
