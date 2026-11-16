package app.vesper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class SoundsTest {
    @Test fun everySoundIsShortQuietAndUnclipped() {
        for (s in Sfx.entries) {
            val pcm = synth(s)
            assertEquals("$s length", (sfxSeconds(s) * SFX_RATE).roundToInt(), pcm.size)
            assertTrue("$s is under half a second", pcm.size <= SFX_RATE / 2)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$s is silent", peak > 1000)
            assertTrue("$s peak $peak is too loud", peak <= (0.25f * 32767).toInt())
            assertTrue("$s clips", pcm.none { it == Short.MAX_VALUE || it == Short.MIN_VALUE })
            assertTrue("$s must start and end at rest (no click)", abs(pcm.first().toInt()) < 200 && abs(pcm.last().toInt()) < 200)
            assertTrue("$s is not deterministic", pcm.contentEquals(synth(s)))
        }
    }
}
