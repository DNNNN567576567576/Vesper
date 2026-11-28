package app.vesper.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BibleReaderTest {
    private val sample = "# comment\n\n1:1|In the beginning.\n1:2|And Cain slew Abel.\nbad line\n2:1|Cain's mark.\n"

    @Test fun parseSkipsCommentsBlanksAndBadLines() {
        val v = parseBible(sample)
        assertEquals(listOf(Verse(1, 1, "In the beginning."), Verse(1, 2, "And Cain slew Abel."), Verse(2, 1, "Cain's mark.")), v)
    }

    @Test fun searchIsCaseInsensitiveTrimmedAndEmptyFindsNothing() {
        val v = parseBible(sample)
        assertEquals(2, searchVerses(v, "  cain ").size)
        assertTrue(searchVerses(v, "   ").isEmpty())
        assertEquals(listOf(0..3, 9..12), matchRanges("Cain and CAIN", " cain"))
    }

    @Test fun nameSpansAreWholeWordAndCaseSensitive() {
        assertEquals(listOf(4..7, 14..17), nameSpans("And Cain slew Abel; Cainan, cain.", setOf("Cain", "Abel")))
    }

    @Test fun onlyTheFirstOccurrencePerChapterIsStyled() {
        val f = firstSpans(listOf("God saw God.", "And God said; Cain"), setOf("God", "Cain"))
        assertEquals(listOf(setOf(0..2), setOf(14..17)), f)
    }

    @Test fun georgianNamesMatchByStemInAnyForm() {
        val stems = setOf(kaStem("კაენი"), kaStem("აბელი"), kaStem("ადამი"), kaStem("ადა"))
        assertEquals(listOf("კაენ", "აბელ", "ადამ", "ადა"), stems.toList())
        val t = "კაენმა აბელს ადამისა ადას; კაენი."
        assertEquals(mapOf(0..5 to "კაენ", 7..11 to "აბელ", 13..19 to "ადამ", 21..24 to "ადა", 27..31 to "კაენ"), kaNameSpans(t, stems))
        assertEquals(listOf(setOf(0..5, 7..11, 13..19, 21..24), emptySet()), firstKeyed(listOf(t, "აბელი და აბელს")) { kaNameSpans(it, stems) })
    }

    @Test fun realFileHasAllOfGenesis() {
        val f = File("src/commonMain/composeResources/files/bible/genesis_kjv.txt")
        val v = parseBible(f.readText())
        assertEquals(1533, v.size)
        assertEquals(50, v.maxOf { it.chapter })
        assertEquals(listOf(31, 25, 24, 26), (1..4).map { c -> v.count { it.chapter == c } })
    }
}
