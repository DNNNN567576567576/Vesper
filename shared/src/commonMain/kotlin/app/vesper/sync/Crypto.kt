package app.vesper.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val MAGIC = "VSP1"
private const val NONCE_BYTES = 12
private const val TAG_BITS = 128
private const val KDF = "PBKDF2WithHmacSHA256"
const val KDF_ITERATIONS = 600_000
const val MIN_PASSPHRASE = 12
const val VAULT_FILE = "vault.vsp"

private val random = SecureRandom()
fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

private fun aad(name: String) = (MAGIC + name).toByteArray()

private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, name: String, input: ByteArray, offset: Int = 0): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(aad(name))
        doFinal(input, offset, input.size - offset)
    }

/** VSP1 | 12-byte nonce | AES-256-GCM(gzip(plain)) + tag. [name] is authenticated, so a file can't pass as another. */
fun seal(key: ByteArray, name: String, plain: ByteArray): ByteArray {
    val zipped = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(plain) } }.toByteArray()
    val nonce = randomBytes(NONCE_BYTES)
    return MAGIC.toByteArray() + nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce, name, zipped)
}

/** Inverse of [seal]; a wrong key, a changed byte or another file's name all fail the tag. */
fun open(key: ByteArray, name: String, file: ByteArray): ByteArray {
    val head = MAGIC.length + NONCE_BYTES
    if (file.size < head || String(file, 0, MAGIC.length) != MAGIC) throw SyncException("A file on Drive isn't a Vesper file. Nothing was changed.")
    val zipped = try {
        gcm(Cipher.DECRYPT_MODE, key, file.copyOfRange(MAGIC.length, head), name, file, head)
    } catch (_: AEADBadTagException) {
        throw SyncException("A file on Drive can't be read: it was changed, or it uses another passphrase. Nothing was changed here.")
    }
    return GZIPInputStream(zipped.inputStream()).use { it.readBytes() }
}

/** vault.vsp. The GCM tag over [wrapped] is the passphrase check. The params live here so they can change later. */
@Serializable
data class VaultFile(val v: Int = 1, val iterations: Int, val salt: String, val nonce: String, val wrapped: String)

private val b64 = Base64.getEncoder()
private val unb64 = Base64.getDecoder()

private fun deriveKek(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
    // Phone and PC keyboards may compose accented letters differently.
    val chars = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toCharArray()
    val spec = PBEKeySpec(chars, salt, iterations, 256)
    try { return SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded } finally { spec.clearPassword(); chars.fill(' ') }
}

fun newDataKey() = randomBytes(32)

fun wrapKey(dataKey: ByteArray, passphrase: String, iterations: Int = KDF_ITERATIONS): ByteArray {
    val salt = randomBytes(16)
    val nonce = randomBytes(NONCE_BYTES)
    val wrapped = gcm(Cipher.ENCRYPT_MODE, deriveKek(passphrase, salt, iterations), nonce, VAULT_FILE, dataKey)
    val e = { b: ByteArray -> b64.encodeToString(b) }
    return Json.encodeToString(VaultFile.serializer(), VaultFile(iterations = iterations, salt = e(salt), nonce = e(nonce), wrapped = e(wrapped))).toByteArray()
}

/** Throws [SyncException] on a wrong passphrase; the caller has changed nothing yet at that point. */
fun unwrapKey(vault: ByteArray, passphrase: String): ByteArray {
    val f = try {
        Json { ignoreUnknownKeys = true }.decodeFromString(VaultFile.serializer(), String(vault))
    } catch (_: IllegalArgumentException) {
        throw SyncException("The vault on Drive is damaged.")
    }
    // Bounds, so a damaged file can't hang the app in the KDF.
    if (f.v != 1 || f.iterations !in 100_000..10_000_000) throw SyncException("The vault on Drive is damaged, or from a newer Vesper.")
    return try {
        gcm(Cipher.DECRYPT_MODE, deriveKek(passphrase, unb64.decode(f.salt), f.iterations), unb64.decode(f.nonce), VAULT_FILE, unb64.decode(f.wrapped))
    } catch (_: AEADBadTagException) {
        throw SyncException("Wrong passphrase. Nothing was changed.")
    }
}
