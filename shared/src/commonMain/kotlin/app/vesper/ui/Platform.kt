package app.vesper.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.window.DialogProperties

// Platform seams for the shared UI. Compose common can draw into an ImageBitmap and read it back (readPixels)
// but cannot create one from raw pixels or switch dithering off, so the platforms do those two things.

/** ARGB_8888 image from non-premultiplied 0xAARRGGBB [pixels], row-major, [w] x [h]. Call rarely (sprites), not per frame. */
internal expect fun imageBitmapOf(pixels: IntArray, w: Int, h: Int): ImageBitmap

/** A Paint with anti-aliasing and dithering off, like the old android Paints, so pixels come out identical. */
internal expect fun hardPaint(): Paint

/** System back (Android back gesture/button) while [enabled]. */
@Composable
internal expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Full-screen dialog drawn under the system bars (Android: decorFitsSystemWindows = false). */
internal expect fun edgeToEdgeDialog(): DialogProperties
