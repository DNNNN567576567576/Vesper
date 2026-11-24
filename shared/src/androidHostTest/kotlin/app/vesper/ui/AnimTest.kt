package app.vesper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.8.2: no sounds any more, and the small animations are exact at rest and inside their bounds. */
class AnimTest {
    private fun root() = listOf(File("."), File("..")).first { File(it, "shared/src/commonMain").exists() }
    private fun sources() = root().walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "kts") && "/build/" !in it.invariantSeparatorsPath && it.name != "AnimTest.kt" }.toList()

    @Test fun noSoundsOrAudioLeft() {
        val banned = listOf("Sfx", "Sounds", "brainSounds", "AudioTrack", "SoundPool", "javax.sound", "ToneGenerator", "initSounds")
        for (f in sources()) {
            val code = f.readText()
            for (b in banned) assertFalse("${f.name} still mentions $b", code.contains(b))
        }
        assertFalse(File(root(), "shared/src/commonMain/kotlin/app/vesper/ui/Sounds.kt").exists())
    }

    @Test fun popGrowsFromSixtyPercentAndSettles() {
        assertEquals(0.6f, popScale(0f), 1e-6f)
        assertEquals(1f, popScale(POP_S), 1e-6f)
        assertEquals(1f, popScale(5f), 0f)
        assertTrue(popScale(0.1f) in 0.6f..1f)
    }

    @Test fun tapSquishIsAboutFivePercentAndRestsAtOne() {
        assertEquals(1f, tapSquish(9f), 0f)
        assertEquals(0.95f, tapSquish(TAP_S / 2f), 1e-4f)
        assertEquals(0f, tapGlow(0f), 1e-6f)
        assertEquals(1f, tapGlow(TAP_S / 2f), 1e-4f)
    }

    @Test fun birthsPopNewKeysAndDriftOldOnes() {
        val b = Births()
        b.update(listOf("a", "b"), 1f)
        assertEquals(1f, b.pop("a", 1f), 0f) // there at the start: no pop, a drift instead
        assertEquals(0f, b.drift("a", 1f), 1e-6f)
        assertEquals(1f, b.drift("a", 1f + DRIFT_S), 1e-6f)
        b.update(listOf("a", "b", "c"), 5f)
        assertEquals(0.6f, b.pop("c", 5f), 1e-6f)
        assertEquals(1f, b.pop("c", 6f), 1e-6f)
        assertEquals(1f, b.drift("c", 5f), 0f)
    }
}
