package app.vesper.data

import android.content.Context
import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Bible canvas's DAO on a real Room DB with the sync triggers. Runs on the JVM (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BibleDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .addCallback(SYNC_TRIGGERS_CALLBACK).build()
    private val dao = db.bibleDao()

    @After fun close() = db.close()

    private suspend fun card(uid: String) = dao.observeCards().first().single { it.uid == uid }

    @Test fun cardsAreMadeEditedAndStamped(): Unit = runBlocking {
        val a = BibleCard(name = "Abraham", x = 12.5f, y = -40f)
        val id = dao.insertCard(a)
        var row = card(a.uid)
        assertEquals(listOf("Abraham", "", BIBLE_PERSON, 12.5f, -40f, false), listOf(row.name, row.note, row.kind, row.x, row.y, row.locked))
        assertTrue(row.syncAt > 0)
        var last = row.syncAt

        fun bumped(r: BibleCard) { assertTrue("syncAt rises", r.syncAt > last); last = r.syncAt }
        dao.renameCard(id, "Abram"); row = card(a.uid); assertEquals("Abram", row.name); bumped(row)
        dao.setNote(id, "left Ur"); row = card(a.uid); assertEquals("left Ur", row.note); bumped(row)
        dao.setKind(id, BIBLE_THING); row = card(a.uid); assertEquals(BIBLE_THING, row.kind); bumped(row)
        dao.moveCard(id, 1f, 2f); row = card(a.uid); assertEquals(1f to 2f, row.x to row.y); bumped(row)
        assertEquals(a.uid, row.uid)
    }

    @Test fun aLockedCardNeverMoves(): Unit = runBlocking {
        val ark = BibleCard(name = "The ark", kind = BIBLE_THING, x = 5f, y = 5f)
        val id = dao.insertCard(ark)
        dao.setLocked(id, true)
        val locked = card(ark.uid)
        dao.moveCard(id, 99f, 99f)
        assertEquals(locked, card(ark.uid)) // not even its clock moved
        dao.setLocked(id, false)
        dao.moveCard(id, 99f, 99f)
        assertEquals(99f to 99f, card(ark.uid).let { it.x to it.y })
    }

    @Test fun linksAreMadeRenamedAndDeleted(): Unit = runBlocking {
        val id = dao.insertLink(BibleLink(fromUid = "isaac", toUid = "abraham", label = "son"))
        dao.renameLink(id, "son of")
        val l = dao.observeLinks().first().single()
        assertEquals(listOf("isaac", "abraham", "son of"), listOf(l.fromUid, l.toUid, l.label))
        dao.deleteLink(id)
        assertTrue(dao.observeLinks().first().isEmpty())
        assertTrue(db.syncDao().tombstones().any { it.tbl == "bible_links" && it.uid == l.uid })
    }

    /** What BibleViewModel.deleteCard does: its links (either way) and the card go together, each with a tombstone; other links stay. */
    @Test fun deletingACardDeletesItsLinks(): Unit = runBlocking {
        val (abraham, isaac, jacob) = listOf("Abraham", "Isaac", "Jacob").map { BibleCard(name = it, x = 0f, y = 0f) }
        listOf(abraham, isaac, jacob).forEach { dao.insertCard(it) }
        dao.insertLink(BibleLink(fromUid = isaac.uid, toUid = abraham.uid, label = "son", uid = "l1"))
        dao.insertLink(BibleLink(fromUid = jacob.uid, toUid = isaac.uid, label = "son", uid = "l2"))
        dao.insertLink(BibleLink(fromUid = jacob.uid, toUid = abraham.uid, label = "grandson", uid = "l3"))
        val id = card(isaac.uid).id
        db.useWriterConnection { w -> w.immediateTransaction { dao.deleteLinksOf(isaac.uid); dao.deleteCardRow(id) } }
        assertEquals(listOf("Abraham", "Jacob"), dao.observeCards().first().map { it.name })
        assertEquals(listOf("l3"), dao.observeLinks().first().map { it.uid })
        assertEquals(setOf("bible_links:l1", "bible_links:l2", "bible_cards:${isaac.uid}"), db.syncDao().tombstones().map { "${it.tbl}:${it.uid}" }.toSet())
    }
}
