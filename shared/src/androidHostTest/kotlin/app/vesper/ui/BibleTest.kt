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

    @Test fun marriageIsJustTheWordMarried() {
        assertTrue(isMarriage("married")); assertTrue(isMarriage(" Married ")); assertFalse(isMarriage("married to")); assertFalse(isMarriage("wife"))
        assertEquals("married to", outWords("married")); assertEquals("married to", inWords(" Married "))
        val two = listOf(card("adam", "Adam"), card("eva", "Eva")); val m = listOf(link("adam", "eva", "married"))
        assertEquals("Adam: married to Eva", bibleWords(two[0], two, m)); assertEquals("Eva: married to Adam", bibleWords(two[1], two, m))
    }

    @Test fun quickChoicesReadRight() {
        assertEquals("father of", outWords("father of")); assertEquals("has father", inWords("father of"))
        assertEquals("brother of", outWords("brother")); assertEquals("has brother", inWords("brother"))
        assertEquals("killed", outWords("killed")); assertEquals("killed by", inWords("killed"))
        val ab = listOf(card("a", "A"), card("b", "B")); val f = listOf(link("a", "b", "father of"))
        assertEquals("A: father of B", bibleWords(ab[0], ab, f)); assertEquals("B: child of A", bibleWords(ab[1], ab, f))
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

    private val eden = listOf(card("god", "God", x = 300f, y = 0f), card("adam", "Adam", x = 10f, y = 40f), card("eva", "Eva", x = 200f, y = 40f),
        card("cain", "Cain", x = 5f, y = 500f), card("abel", "Abel", x = 9f, y = 700f), card("seth", "Seth", x = 77f, y = 123f, locked = true), card("lone", "Lone", x = 1f, y = 2f))
    private val edenLinks = listOf(
        link("god", "adam", "created"), link("adam", "eva", "married"), link("cain", "adam", "son of"), link("cain", "eva", "son of"),
        link("abel", "adam", "son of"), link("abel", "eva", "son of"), link("cain", "abel", "killed"), link("god", "seth", "created"),
    )

    @Test fun parentLinksBothWays() {
        assertEquals("cain" to "adam", parentEnds(link("cain", "adam", " Son of ")))
        assertEquals("abel" to "adam", parentEnds(link("adam", "abel", "father of")))
        assertEquals("abel" to "eva", parentEnds(link("eva", "abel", "Mother of")))
        assertEquals("b" to "a", parentEnds(link("a", "b", "parent of")))
        assertEquals("b" to "a", parentEnds(link("b", "a", "child of")))
        assertNull(parentEnds(link("a", "b", "son"))); assertNull(parentEnds(link("a", "b", "killed"))); assertNull(parentEnds(link("a", "a", "son of")))
        assertEquals(setOf("adam", "eva"), parentsMap(edenLinks)["abel"]); assertEquals(setOf("adam", "eva"), parentsMap(edenLinks)["cain"])
        assertEquals(setOf("cain", "abel"), marriedParents(edenLinks).keys)
        assertEquals(setOf("adam"), parentsMap(listOf(link("adam", "x", "father of")))["x"]) // a lone parent is no ring
        assertTrue(marriedParents(listOf(link("adam", "x", "father of"), link("eva", "x", "mother of"))).isEmpty()) // two parents not married: no ring
    }

    @Test fun ringFlow() {
        val at = Offset.Zero
        assertEquals(BibleTap.PickRing("m"), bibleTap(BibleMode.Link, null, "m", null, null, at, ring = "m"))
        assertEquals(BibleTap.Child("m", "cain"), bibleTap(BibleMode.Link, "cain", null, null, null, at, ringFrom = "m"))
        assertEquals(BibleTap.PickRing(null), bibleTap(BibleMode.Link, null, "m", null, null, at, ring = "m", ringFrom = "m")) // same ring lets go
        assertEquals(BibleTap.PickRing(null), bibleTap(BibleMode.Link, null, null, null, null, at, ringFrom = "m")) // empty space too
        assertEquals(BibleTap.PickRing("n"), bibleTap(BibleMode.Link, null, "n", null, null, at, ring = "n", ringFrom = "m"))
        assertEquals(BibleTap.EditLink("m"), bibleTap(BibleMode.Move, null, "m", null, null, at, ring = "m")) // Move mode: the link menu as before
        assertEquals(BibleTap.EditLink("l"), bibleTap(BibleMode.Link, null, "l", null, null, at)) // a plain label still edits
    }

    @Test fun siblingsAndParentsInWords() {
        assertEquals("Abel: son of Adam and Eva · brother of Cain · killed by Cain", bibleWords(eden[4], eden, edenLinks))
        assertEquals("Cain: son of Adam and Eva · brother of Abel · killed Abel", bibleWords(eden[3], eden, edenLinks))
        assertEquals("Adam: married to Eva · created by God · has son Cain, Abel", bibleWords(eden[1], eden, edenLinks))
        val kids = listOf(card("p", "P"), card("q", "Q"), card("d", "Daughter"), card("s", "Son"), card("c", "Kid"))
        val ls = listOf(link("d", "p", "daughter of"), link("s", "p", "son of"), link("c", "p", "child of"), link("p", "q", "married"))
        assertEquals("Daughter: daughter of P · sister of Son and Kid", bibleWords(kids[2], kids, ls))
        assertEquals("Son: son of P · brother of Daughter and Kid", bibleWords(kids[3], kids, ls))
        assertEquals("Kid: child of P · sibling of Daughter and Son", bibleWords(kids[4], kids, ls))
    }

    @Test fun siblingLinksHideOnlyBetweenCardsSharingAParent() {
        val ls = listOf(link("c", "a", "son of"), link("d", "a", "son of"), link("c", "d", "brother"), link("c", "x", "sister of"), link("c", "d", "friend"))
        val par = parentsMap(ls)
        assertTrue(siblingLinkHidden(ls[2], par))
        assertFalse(siblingLinkHidden(ls[3], par)) // x has no shared parent: drawn as today
        assertFalse(siblingLinkHidden(ls[4], par)) // not a sibling word
        assertFalse(siblingLinkHidden(ls[2], emptyMap()))
    }

    @Test fun momentsStarFollowsShownLanguage() {
        val c = BibleCard(name = "A", x = 0f, y = 0f, uid = "a", moments = "Gen 1:1 — x")
        assertTrue(hasMoments(c, false)); assertFalse(hasMoments(c, true))
        assertFalse(hasMoments(c.copy(moments = " \n "), false))
    }

    @Test fun parentsReadInMarriageOrder() {
        val fam = listOf(card("a", "Adam", x = 10f), card("e", "Eva", x = 200f), card("c", "Cain"), card("u", "Uri", x = 50f), card("v", "Vic", x = 5f), card("k", "Kid"))
        assertEquals("Cain: child of Eva and Adam", bibleWords(fam[2], fam, listOf(link("c", "a", "child of"), link("c", "e", "child of"), link("e", "a", "married"))))
        assertEquals("Cain: child of Adam and Eva", bibleWords(fam[2], fam, listOf(link("c", "e", "child of"), link("c", "a", "child of"), link("a", "e", "married"))))
        assertEquals("Kid: child of Vic and Uri", bibleWords(fam[5], fam, listOf(link("k", "u", "child of"), link("k", "v", "child of"))))
    }

    @Test fun deedCurveBulgesAndClamps() {
        val same = deedCurve(Offset(0f, 0f), Offset(200f, 10f)) // same row: down by 22% of the distance
        assertEquals(Offset(0f, 1f), same.side); assertTrue(same.c.y > 5f + 40f); assertTrue(same.apex.y > 5f)
        assertEquals(Offset(0f, 1f), deedCurve(Offset(200f, 0f), Offset(0f, 0f)).side) // leftwards still down
        val vert = deedCurve(Offset(0f, 0f), Offset(0f, 100f)) // going down the screen, the right of travel is -x
        assertEquals(-1f, vert.side.x, 1e-4f); assertEquals(0f, vert.side.y, 1e-4f)
        assertEquals(14f, deedCurve(Offset(0f, 0f), Offset(10f, 0f)).c.y, 1e-3f) // min
        assertEquals(60f, deedCurve(Offset(0f, 0f), Offset(1000f, 0f)).c.y, 1e-3f) // max
        assertEquals(96f, deedCurve(Offset(0f, 0f), Offset(1000f, 0f), k = 1).c.y, 1e-3f) // the second deed bulges more
        assertEquals(30f, deedCurve(Offset(0f, 0f), Offset(1000f, 0f)).apex.y, 1e-3f) // apex is halfway to the control point
    }

    @Test fun deedLinksAreNeitherMarriageNorParents() {
        assertTrue(isDeedLink(link("god", "adam", "created"))); assertTrue(isDeedLink(link("cain", "abel", "killed")))
        assertFalse(isDeedLink(link("a", "b", "married"))); assertFalse(isDeedLink(link("a", "b", "son of"))); assertFalse(isDeedLink(link("a", "b", "father of")))
        assertFalse(isDeedLink(link("a", "b", "wife")))
    }

    @Test fun tidyMakesAFamilyTree() {
        val sizes = eden.map { Size(60f, 20f) }
        val out = bibleTidy(eden, edenLinks, sizes)
        fun p(u: String) = out.getValue(u)
        assertFalse("locked stays", "seth" in out); assertFalse("no links stays", "lone" in out)
        assertTrue(p("god").y < p("adam").y); assertEquals(p("adam").y, p("eva").y, 0.01f)
        assertTrue("Adam, the husband found left, stays left", p("adam").x < p("eva").x)
        assertEquals(60f + 96f, p("eva").x - p("adam").x, 0.01f) // spouses: 96dp edge to edge
        assertTrue("siblings >= 64dp apart", kotlin.math.abs(p("cain").x - p("abel").x) >= 60f + 64f - 0.01f)
        assertEquals(p("adam").y + 130f, p("cain").y, 0.01f); assertEquals(p("cain").y, p("abel").y, 0.01f)
        assertEquals((p("adam").x + p("eva").x) / 2f, (p("cain").x + p("abel").x) / 2f, 0.01f) // the children centred under the ring
        for (a in out.keys) for (b in out.keys) if (a < b && p(a).y == p(b).y) assertTrue("$a/$b overlap", kotlin.math.abs(p(a).x - p(b).x) >= 60f + 64f - 0.01f)
        assertEquals(0f, p("god").y, 0.01f) // the top row sits at the highest tidied card's y
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

    @Test fun stackedLabelOffsetsCentreOnTheMidpoint() {
        assertEquals(listOf(0f), stackOffsets(1, 10f))
        assertEquals(listOf(-5f, 5f), stackOffsets(2, 10f))
        assertEquals(listOf(-10f, 0f, 10f), stackOffsets(3, 10f))
    }

    // 3.1.0: verse references, the Georgian names and words, and the Georgian relation line
    @Test fun verseRefsFindGenesisAndGeorgianReferences() {
        val t = "Lamech took two wives (Gen 4:19), see Genesis 2:24, Gen. 4:8 and დაბ 4:19-20 or დაბადება 2:24; not Foo 3:4 or 12:30."
        assertEquals(listOf("Gen 4:19", "Genesis 2:24", "Gen. 4:8", "დაბ 4:19-20", "დაბადება 2:24"), verseRefs(t).map { t.substring(it.first, it.last + 1) })
        assertEquals(4 to 19, verseOf("დაბ 4:19-20"))
        assertTrue(verseRefs("nothing here").isEmpty())
    }

    @Test fun georgianNamesAndLabelsFallBack() {
        assertEquals("აბელი", kaName(card("abel", "Abel")))
        assertEquals("აბელი", kaName(card("abel", " ABEL ")))
        assertEquals("ჰაბილი", kaName(BibleCard(name = "Abel", nameKa = "ჰაბილი", x = 0f, y = 0f)))
        assertEquals("", kaName(card("z", "Zebulun")))
        assertEquals("მოკლა", kaLabel(link("a", "b", "Killed")))
        assertEquals("ძმა", kaLabel(BibleLink(fromUid = "a", toUid = "b", label = "killed", labelKa = "ძმა")))
        assertEquals("", kaLabel(link("a", "b", "betrayed")))
        assertEquals("Zebulun" to KA_MISSING, shownName(card("z", "Zebulun"), true))
        assertEquals("აბელი" to KA_GUESS, shownName(card("abel", "Abel"), true))
        assertEquals("Abel" to 1f, shownName(card("abel", "Abel"), false))
    }

    @Test fun georgianRelationsForAbel() {
        val adam = card("adam", "Adam", x = 0f); val eve = card("eve", "Eve", x = 50f)
        val cain = card("cain", "Cain"); val abel = card("abel", "Abel")
        val cs = listOf(adam, eve, cain, abel)
        val ls = listOf(
            link("adam", "eve", "married"), link("cain", "adam", "son of"), link("cain", "eve", "son of"),
            link("abel", "adam", "son of"), link("abel", "eve", "son of"), link("cain", "abel", "killed"),
        )
        assertEquals("აბელი — მშობლები: ადამი, ევა · ძმა: კაენი · მოკლა: ← კაენი", bibleWordsKa(abel, cs, ls))
        assertEquals("ადამი — მეუღლე: ევა", bibleWordsKa(adam, cs, listOf(ls[0])))
        assertEquals("აბელი — ბმები ჯერ არ არის", bibleWordsKa(abel, cs, emptyList()))
    }

    private val fam = listOf(card("god", "God"), card("adam", "Adam"), card("eva", "Eva"), card("cain", "Cain"), card("abel", "Abel"))
    private val famLinks = listOf(
        link("adam", "eva", "married"), link("adam", "cain", "father of"), link("eva", "cain", "mother of"),
        link("adam", "abel", "father of"), link("eva", "abel", "mother of"),
        link("cain", "abel", "brother"), link("cain", "abel", "killed"), link("god", "adam", "created"), link("god", "eva", "made"),
    )

    @Test fun siblingLinksMergeWithDerivedSiblings() {
        assertEquals("Abel: child of Adam and Eva · brother of Cain · killed by Cain", bibleWords(fam[4], fam, famLinks))
        assertEquals("Cain: child of Adam and Eva · brother of Abel · killed Abel", bibleWords(fam[3], fam, famLinks))
        // without shared parents the link alone makes them siblings; "sister of" works too
        assertEquals("Cain: sister of Abel", bibleWords(fam[3], fam, listOf(link("cain", "abel", "Sister of"))))
        assertEquals("Abel: sister of Cain", bibleWords(fam[4], fam, listOf(link("cain", "abel", "Sister of"))))
        assertEquals("აბელი — მშობლები: ადამი, ევა · ძმა: კაენი · მოკლა: ← კაენი", bibleWordsKa(fam[4], fam, famLinks))
    }

    @Test fun lineageGoesBackToTheTop() {
        assertEquals(setOf("abel", "adam", "eva", "god"), lineageOf("abel", fam, famLinks))
        assertEquals(setOf("god"), lineageOf("god", fam, famLinks))
        // a parent loop does not hang
        assertEquals(setOf("a", "b"), lineageOf("a", listOf(card("a"), card("b")), listOf(link("a", "b", "son of"), link("b", "a", "son of"))))
    }

    @Test fun childCurveLeavesAndArrivesVertically() {
        val ring = Offset(10f, 20f); val top = Offset(90f, 120f)
        val c = childCurve(ring, top)
        assertEquals(ring, c.at(0f)); assertEquals(top, c.at(1f))
        assertEquals(0f, c.p1.x - ring.x); assertEquals(0f, c.p2.x - top.x) // control points straight above/below: vertical tangents
        assertTrue(c.p1.y > ring.y && c.p2.y < top.y)
    }

    @Test fun bowedLinkKeepsEndsAndClampsBow() {
        val a = Offset(0f, 0f)
        val short = bowedLink(a, Offset(100f, 0f))
        assertEquals(a, short.at(0f)); assertEquals(Offset(100f, 0f), short.at(1f))
        assertEquals(8f, kotlin.math.abs(short.apex.y), 0.001f) // 8% of 100
        assertEquals(20f, kotlin.math.abs(bowedLink(a, Offset(1000f, 0f)).apex.y), 0.001f) // clamped
        assertEquals(a, bowedLink(a, a).apex) // no length, no bow
    }

    @Test fun findMatchesWordStartsInBothLanguages() {
        val cs = listOf(card("l", "Lamech"), card("t", "Tubal-Cain"), card("a", "Adam"), card("n", "Cain's wife"), card("k", "x").copy(nameKa = "ლამექი"))
        assertEquals(listOf("l"), bibleFind("LAM", cs).map { it.uid }) // English prefix, case-insensitive
        assertEquals(listOf("l", "k"), bibleFind("ლამ", cs).map { it.uid }) // Georgian prefix (built-in guess for Lamech, nameKa for k)
        assertEquals(listOf("t", "n"), bibleFind("cain", cs).map { it.uid }) // a later word starts with it
        assertTrue(bibleFind("ain", cs).isEmpty() && bibleFind("  ", cs).isEmpty()) // not mid-word; blank = nothing
    }
}
