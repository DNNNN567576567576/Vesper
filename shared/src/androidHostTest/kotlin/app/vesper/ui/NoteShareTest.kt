package app.vesper.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteShareTest {
    private val fonts get() = createFontFamilyResolver(RuntimeEnvironment.getApplication())
    private fun img(title: String, body: String) = renderNoteImage(fonts, "poetry", title, body, FontFamily.Monospace)

    @Test fun heightIsTheSumOfItsPieces() {
        assertEquals(84 + 20 + 40 + 100 + 56 + 10 + 84, noteImageHeight(20, 0, 100, 10))
        assertTrue(noteImageHeight(20, 70, 100, 10) > noteImageHeight(20, 0, 100, 10))
    }

    @Test fun aLongPoemMakesATallerPictureAtAFixedWidth() {
        val short = img("T", "one line")
        val long = img("T", (1..40).joinToString("\n") { "line $it of a long poem" })
        assertEquals(SHARE_W, short.width); assertEquals(SHARE_W, long.width)
        assertTrue(long.height > short.height + 39 * 60)
    }

    @Test fun georgianAndNativeGraphics() {
        val b = img("სათაური", "ღამე მშვიდობის, მთვარევ. ".repeat(12))
        val px = IntArray(b.width * b.height).also { b.readPixels(it) }
        assertEquals(0xFF120A1C.toInt(), px[b.width * 20 + 20]) // the panel
        assertEquals(0xFFB69AFF.toInt(), px[0]) // the 2 px frame
        assertTrue("text drawn", px.toSet().size > 3)
        assertTrue(b.toPng().size > 1000)
    }
}
