package app.vesper

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.min

/** Grimoire media on Android: files in `filesDir/shrine`, picks are content:// uri strings. */
class AndroidShrineMedia(private val app: Application) : ShrineMedia {
    override fun isVideo(ref: String) = app.contentResolver.getType(Uri.parse(ref))?.startsWith("video/") == true

    override fun import(ref: String, video: Boolean): String =
        if (video) ShrineFiles.importVideo(app, Uri.parse(ref)) else ShrineFiles.importPhoto(app, Uri.parse(ref))

    override fun delete(name: String) {
        ShrineFiles.file(app, name).delete()
    }

    override fun cached(name: String, widthPx: Int) = ShrineBitmaps.cached(name, widthPx)?.asImageBitmap()

    override suspend fun load(name: String, widthPx: Int, cache: Boolean, video: Boolean) =
        ShrineBitmaps.load(ShrineFiles.file(app, name), widthPx, cache, video)?.asImageBitmap()

    override suspend fun thumb(ref: String, edge: Int) = ShrineBitmaps.thumb(app, Uri.parse(ref), edge)?.asImageBitmap()

    @Composable
    override fun rememberPicker(onPick: (String) -> Unit): () -> Unit {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onPick(uri.toString()) }
        return { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    }

    @Composable
    override fun VideoPlayer(name: String, onClose: () -> Unit) {
        val file = ShrineFiles.file(app, name)
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xFF040206)), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { c ->
                        android.widget.VideoView(c).apply {
                            setMediaController(android.widget.MediaController(c).also { it.setAnchorView(this) })
                            setOnErrorListener { _, _, _ -> onClose(); true }
                            setVideoPath(file.path)
                            start()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Shrine photo storage: `filesDir/shrine/<uuid>.jpg`. */
object ShrineFiles {
    private const val MAX_EDGE = 2048

    fun file(app: Application, name: String) = File(File(app.filesDir, "shrine"), name)

    /** Blocking: call off the main thread. Copies the video as is (no re-encoding); returns the stored file name. */
    fun importVideo(app: Application, uri: Uri): String {
        val dir = File(app.filesDir, "shrine").apply { mkdirs() }
        val name = UUID.randomUUID().toString() + ".mp4"
        val out = File(dir, name)
        try {
            val stream = app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open video")
            stream.use { i -> out.outputStream().use { i.copyTo(it) } }
        } catch (e: Exception) {
            out.delete(); throw e
        }
        return name
    }

    /** Blocking: call off the main thread. Returns the stored file name. */
    fun importPhoto(app: Application, uri: Uri): String {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Bounds-only decoding always returns null, so check that the stream opened, not the decode result.
        val stream = resolver.openInputStream(uri) ?: throw java.io.IOException("cannot open photo")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw java.io.IOException("not an image")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        var bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw java.io.IOException("cannot decode photo")

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge > MAX_EDGE) { val k = MAX_EDGE.toFloat() / longEdge; m.postScale(k, k) }
        if (!m.isIdentity) {
            val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (t !== bmp) bmp.recycle()
            bmp = t
        }

        val dir = File(app.filesDir, "shrine").apply { mkdirs() }
        val name = UUID.randomUUID().toString() + ".jpg"
        val out = File(dir, name)
        try {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            out.delete(); throw e
        } finally {
            bmp.recycle()
        }
        return name
    }
}

private object ShrineBitmaps {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(name: String, w: Int): Bitmap? = cache.get("$name@$w")

    /** Decodes [file] downsampled to roughly [widthPx] wide. Returns null if it can't be read. */
    suspend fun load(file: File, widthPx: Int, cache: Boolean, video: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${file.name}@$widthPx"
        if (cache) this@ShrineBitmaps.cache.get(key)?.let { return@withContext it }
        try {
            if (video) {
                val f = frame { it.setDataSource(file.path) }
                if (f != null && cache) this@ShrineBitmaps.cache.put(key, f)
                return@withContext f
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null && cache) this@ShrineBitmaps.cache.put(key, bmp)
            bmp
        } catch (e: Throwable) {
            null // OutOfMemory or a corrupt file must never take the app down
        }
    }

    /** First frame of a video, or null if it can't be read. */
    private fun frame(source: (android.media.MediaMetadataRetriever) -> Unit): Bitmap? {
        val mmr = android.media.MediaMetadataRetriever()
        return try { source(mmr); mmr.getFrameAtTime(0) } catch (e: Throwable) { null } finally { try { mmr.release() } catch (e: Throwable) {} }
    }

    /** Small thumbnail of a picked (content://) photo or video for the attachment chip. */
    suspend fun thumb(context: android.content.Context, uri: Uri, edge: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val r = context.contentResolver
            if (r.getType(uri)?.startsWith("video/") == true) return@withContext frame { it.setDataSource(context, uri) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
            r.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Throwable) {
            null
        }
    }
}
