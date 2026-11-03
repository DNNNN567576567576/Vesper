package app.vesper.data

import android.content.Context
import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NotesViewModel.move writes all positions in `useWriterConnection { immediateTransaction { dao calls } }` (KMP Room)
 * instead of Android's withTransaction. On the phone's compatibility-mode DB (no driver) the DAO calls must join that
 * transaction (no deadlock) and a failure must roll every position back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReorderTransactionTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .addCallback(SYNC_TRIGGERS_CALLBACK).build()
    private val dao = db.noteDao()

    private suspend fun move(ids: List<Long>, failAfter: Int = Int.MAX_VALUE) =
        db.useWriterConnection { c ->
            c.immediateTransaction {
                ids.forEachIndexed { i, id ->
                    if (i == failAfter) error("boom")
                    dao.setPosition(id, i)
                }
            }
        }

    @Test fun commitsAllAndRollsBackOnFailure() = runBlocking(Dispatchers.IO) {
        withTimeout(10_000) {
            val ids = (1..3).map { dao.insert(Note(title = "n$it", content = "", position = 10 + it)) }
            move(ids.reversed())
            assertEquals(listOf(2, 1, 0), ids.map { dao.get(it)!!.position })

            val failed = runCatching { move(ids, failAfter = 2) }
            assertTrue(failed.isFailure)
            assertEquals(listOf(2, 1, 0), ids.map { dao.get(it)!!.position }) // first two writes were rolled back
        }
        db.close()
    }
}
