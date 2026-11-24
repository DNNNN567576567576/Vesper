package app.vesper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.vesper.data.BIBLE_THING
import app.vesper.data.BibleCard
import app.vesper.data.BibleLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 2.7.9: the Bible brain on the map and its canvas: the info strip's words, hit tests, what a tap does, and the cards' colours. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BibleTest {
    private fun card(uid: String, name: String = uid, x: Float = 0f, y: Float = 0f, locked: Boolean = false, kind: String = "person") =
        BibleCard(name = name, x = x, y = y, locked = locked, kind = kind, uid = uid)
    private fun link(from: String, to: String, label: String) = BibleLink(fromUid = from, toUid = to, label = label)

    private val abraham = card("abraham", "Abraham"); private val sarah = card("sarah", "Sarah"); private val hagar = card("hagar", "Hagar")
    private val isaac = card("isaac", "Isaac"); private val ishmael = card("ishmael", "Ishmael"); private val jacob = card("jacob", "Jacob")
    private val esau = card("esau", "Esau"); private val noah = card("noah", "Noah"); private val ark = card("ark", "The ark", kind = BIBLE_THING)
    private val cards = listOf(abraham, sarah, hagar, isaac, ishmael, jacob, esau, noah, ark)
    private val links = listOf(
        link("sarah", "abraham", "wife"), link("hagar", "sarah", "servant"),
        link("isaac", "abraham", "son"), link("isaac", "sarah", "son"), link("ishmael", "abraham", "son"), link("ishmael", "hagar", "son"),
        link("jacob", "isaac", "son"), link("esau", "isaac", "son"), link("noah", "ark", "built"),
    )

    @Test fun infoStripWords() {
        assertEquals("Isaac: son of Abraham, Sarah · has son Jacob, Esau", bibleWords(isaac, cards, links))
        assertEquals("Abraham: has wife Sarah · has son Isaac, Ishmael", bibleWords(abraham, cards, links))
        assertEquals("Hagar: servant of Sarah · has son Ishmael", bibleWords(hagar, cards, links))
        assertEquals("Noah: built The ark", bibleWords(noah, cards, links))
        assertEquals("The ark: built by Noah", bibleWords(ark, cards, links))
        assertEquals("Esau: son of Isaac", bibleWords(esau, cards, links))
        assertEquals("Lot: no links yet", bibleWords(card("lot", "Lot"), cards, links))
        // a link to a card that is gone is left out; the same name twice reads once
        assertEquals("Esau: son of Isaac", bibleWords(esau, cards, links + link("esau", "ghost", "son") + link("esau", "isaac", "son")))
    }

    @Test fun linkWordsReadBothWays() {
        assertEquals("son of", outWords("son")); assertEquals("has son", inWords("son"))
        assertEquals("son of", outWords(" son of ")); assertEquals("has son", inWords("son of"))
        assertEquals("built", outWords("built")); assertEquals("built by", inWords("built"))
        assertEquals("anointed", outWords("anointed")); assertEquals("anointed by", inWords("anointed"))
        assertEquals("married to", outWords("married to")); assertEquals("married to", inWords("married to"))
        assertTrue(isDeed("Built")); assertFalse(isDeed("wife"))
    }

    @Test fun hitTestsFindTheTopmostCard() {
        val boxes = listOf(boxAround(Offset(0f, 0f), Size(80f, 30f)), boxAround(Offset(30f, 0f), Size(80f, 30f)))
        assertEquals(1, topBoxAt(boxes, Offset(20f, 0f))) // both under it: the one drawn last
        assertEquals(0, topBoxAt(boxes, Offset(-35f, 10f)))
        assertEquals(0, topBoxAt(boxes, Offset(-42f, 0f))) // a little slack round the edge
        assertEquals(-1, topBoxAt(boxes, Offset(0f, 40f)))
    }

    @Test fun moveModeTaps() {
        val at = Offset(12f, -7f)
        assertEquals(BibleTap.Create(at), bibleTap(BibleMode.Move, null, null, null, null, at)) // tap empty creates, right there
        assertEquals(BibleTap.Create(Offset(BIBLE_HALF, -BIBLE_HALF)), bibleTap(BibleMode.Move, null, null, null, null, Offset(9999f, -9999f))) // never off the canvas
        assertEquals(BibleTap.Select("isaac"), bibleTap(BibleMode.Move, "isaac", null, null, null, at)) // tap card selects
        assertEquals(BibleTap.Select("sarah"), bibleTap(BibleMode.Move, "sarah", null, "isaac", null, at))
        assertEquals(BibleTap.Select(null), bibleTap(BibleMode.Move, "isaac", null, "isaac", null, at)) // again: hides it
        assertEquals(BibleTap.Select(null), bibleTap(BibleMode.Move, null, null, "isaac", null, at)) // empty with one shown: only hides it
        assertEquals(BibleTap.EditLink("l"), bibleTap(BibleMode.Move, null, "l", null, null, at))
        assertEquals(BibleTap.Select("isaac"), bibleTap(BibleMode.Move, "isaac", "l", null, null, at)) // a card beats a label under it
    }

    @Test fun linkFlow() {
        val at = Offset.Zero
        var from: String? = null
        fun tap(card: String?) = bibleTap(BibleMode.Link, card, null, null, from, at).also { if (it is BibleTap.Pick) from = it.uid }
        assertEquals(BibleTap.Pick("isaac"), tap("isaac"))
        assertEquals(BibleTap.Link("isaac", "abraham"), tap("abraham"))
        from = "isaac"
        assertEquals(BibleTap.Pick(null), tap("isaac")) // the same card again lets go
        assertNull(from)
        from = "isaac"
        assertEquals(BibleTap.Pick(null), tap(null)) // empty space lets go, never makes a card
        assertEquals(BibleTap.EditLink("l"), bibleTap(BibleMode.Link, null, "l", null, null, at))
    }

    @Test fun lockModeAndDrags() {
        assertEquals(BibleTap.Lock("ark"), bibleTap(BibleMode.Lock, "ark", null, null, null, Offset.Zero))
        assertNull(bibleTap(BibleMode.Lock, null, null, null, null, Offset.Zero)) // nothing is made in Lock mode
        assertNull(bibleTap(BibleMode.Lock, null, "l", null, null, Offset.Zero))
        assertTrue(canDrag(BibleMode.Move, isaac))
        assertFalse("a locked card stays", canDrag(BibleMode.Move, isaac.copy(locked = true)))
        assertFalse(canDrag(BibleMode.Link, isaac)); assertFalse(canDrag(BibleMode.Lock, isaac))
        // 2.8.1: a drag that starts on a locked card holds still: no card move and no pan that carries it along
        assertTrue(dragHeld(isaac.copy(locked = true))); assertFalse(dragHeld(isaac)); assertFalse(dragHeld(null))
    }

    @Test fun cameraStartsOnTheCards() {
        assertEquals(Offset.Zero, bibleHome(emptyList()))
        assertEquals(Offset(50f, 10f), bibleHome(listOf(card("a", x = 0f, y = -20f), card("b", x = 100f, y = 40f))))
    }

    /** A tap on the bible brain (top right) reaches it only when it is drawn; it never steals the daily brain's tap. */
    @Test fun tapsOnTheBibleBrain() {
        val l = buildLayout(emptyList())
        val u = 2.28f * 2.75f * 0.6f; val dp = 2.75f
        fun tap(w: Offset, bible: Boolean = true) = l.mapHit(w, u, nodeScale(0.6f), dp, true, bible).first
        assertEquals(MapHit.Bible, tap(BIBLE_AT))
        assertEquals(MapHit.Bible, tap(BIBLE_AT + Offset(-4f, 3f)))
        assertEquals(MapHit.Daily, tap(DAILY_AT))
        assertEquals(MapHit.Learn, tap(LEARN_AT))
        assertEquals(MapHit.Empty, tap(BIBLE_AT, bible = false))
        assertEquals(BIBLE_AT, RESERVED_AT[1])
        assertEquals(Offset(-DAILY_AT.x, DAILY_AT.y), BIBLE_AT) // the daily brain's mirror
    }

    /** The bible brain and its name are in the home view's boxes, so the fitted map never cuts them off. */
    @Test fun theHomeViewMakesRoomForTheBibleBrain() {
        val boxes = overviewBoxes(buildLayout(emptyList()), Size(40f, 20f), 1f, 1f)
        assertTrue(boxes.any { it[0] <= BIBLE_AT.x && it[2] >= BIBLE_AT.x && it[1] <= BIBLE_AT.y && it[3] >= BIBLE_AT.y })
        assertTrue("its name sits above it", boxes.any { it[3] <= BIBLE_AT.y && it[0] <= BIBLE_AT.x && it[2] >= BIBLE_AT.x })
    }

    /** Every kind of card paints only dark purple, lilac, white and black: never pink. */
    @Test fun cardsArePurpleBlackAndWhiteOnly() {
        val w = 300; val h = 80
        val target = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(2.75f), LayoutDirection.Ltr, Canvas(target), Size(w.toFloat(), h.toFloat())) {
            drawCard(floatArrayOf(10f, 20f, 70f, 50f), thing = false, locked = false, hot = false, picked = false)
            drawCard(floatArrayOf(80f, 20f, 140f, 50f), thing = true, locked = false, hot = false, picked = false)
            drawCard(floatArrayOf(150f, 20f, 210f, 50f), thing = false, locked = true, hot = true, picked = false)
            drawCard(floatArrayOf(220f, 20f, 290f, 50f), thing = true, locked = true, hot = true, picked = true)
        }
        val px = IntArray(w * h).also { target.readPixels(it) }
        val painted = px.filter { (it ushr 24) != 0 }
        assertTrue("the cards drew (${painted.size} px)", painted.size > 4000)
        for (p in painted) assertTrue("pink pixel %08x".format(p), (p shr 16 and 0xFF) <= (p and 0xFF) + 8)
        assertTrue("white for a locked card's border and lock", painted.any { it == 0xFFFFFFFF.toInt() })
        assertTrue("the dark purple fill", painted.any { it == 0xFF2A1450.toInt() })
    }
}
