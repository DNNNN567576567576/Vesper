package app.vesper.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fewer KDF rounds than production, except where the production count itself is under test. */
class SyncCryptoTest {
    private val key = newDataKey()
    private val plain = "secret note text ".repeat(50).toByteArray()
    private val pass = "correct horse battery staple"

    @Test fun roundTripHasTheWireFormat() {
        val file = seal(key, "snap-a.vsp", plain)
        assertEquals("VSP1", String(file, 0, 4))
        assertFalse(String(file, Charsets.ISO_8859_1).contains("secret"))
        assertArrayEquals(plain, open(key, "snap-a.vsp", file))
        // a fresh nonce per write
        assertFalse(seal(key, "snap-a.vsp", plain).contentEquals(file))
    }

    @Test fun everyTamperedByteIsDetected() {
        val file = seal(key, "snap-a.vsp", "short".toByteArray())
        for (i in file.indices) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows(SyncException::class.java) { open(key, "snap-a.vsp", bad) }
        }
        assertThrows(SyncException::class.java) { open(key, "snap-a.vsp", file.copyOf(file.size - 1)) }
        assertThrows(SyncException::class.java) { open(key, "snap-a.vsp", ByteArray(3)) }
    }

    @Test fun aFileCannotPassAsAnother() {
        val file = seal(key, "snap-a.vsp", plain)
        assertThrows(SyncException::class.java) { open(key, "snap-b.vsp", file) }
    }

    @Test fun anotherKeyCannotOpen() {
        val file = seal(key, "snap-a.vsp", plain)
        assertThrows(SyncException::class.java) { open(newDataKey(), "snap-a.vsp", file) }
    }

    @Test fun vaultOpensOnlyWithItsPassphrase() {
        val vault = wrapKey(key, pass, 100_000)
        assertArrayEquals(key, unwrapKey(vault, pass))
        val wrong = assertThrows(SyncException::class.java) { unwrapKey(vault, "$pass!") }
        assertEquals("Wrong passphrase. Nothing was changed.", wrong.message)
    }

    @Test fun vaultHoldsTheProductionCostAndNoKeyMaterial() {
        val vault = String(wrapKey(key, pass))
        assertTrue(vault, vault.contains("\"iterations\":600000"))
        assertFalse(vault.contains(pass))
        assertTrue(java.util.Base64.getDecoder().decode(Regex("\"salt\":\"([^\"]+)\"").find(vault)!!.groupValues[1]).size == 16)
    }

    @Test fun composedAndDecomposedAccentsAreTheSamePassphrase() {
        val vault = wrapKey(key, "café café café", 100_000)
        assertArrayEquals(key, unwrapKey(vault, "café café café"))
    }

    @Test fun damagedVaultCostIsRefusedBeforeTheKdf() {
        val vault = String(wrapKey(key, pass, 100_000)).replace("\"iterations\":100000", "\"iterations\":2000000000").toByteArray()
        assertThrows(SyncException::class.java) { unwrapKey(vault, pass) }
    }
}
