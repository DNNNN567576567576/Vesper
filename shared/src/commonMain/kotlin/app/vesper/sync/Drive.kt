package app.vesper.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class RemoteFile(val id: String, val name: String, val version: String = "", val createdTime: String = "")

/** The app's private Drive folder. Blocking. */
interface SyncTransport {
    fun list(): List<RemoteFile>
    fun download(id: String): ByteArray
    fun create(name: String, bytes: ByteArray): RemoteFile
    fun update(id: String, bytes: ByteArray): RemoteFile
    fun delete(id: String)
}

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
private const val API = "https://www.googleapis.com"
private const val FIELDS = "id,name,version,createdTime"

@Serializable private data class FileList(val files: List<RemoteFile> = emptyList())
private val json = Json { ignoreUnknownKeys = true }

/**
 * Drive v3 over appDataFolder. Error bodies are never read into messages or logs.
 * shortcut: one page of 1000 files; the folder holds one vault plus one file per device.
 */
class DriveTransport(private val token: String) : SyncTransport {
    private fun call(method: String, url: String, contentType: String? = null, body: ByteArray? = null, override: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            override?.let { c.setRequestProperty("X-HTTP-Method-Override", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            when (val code = c.responseCode) {
                in 200..299 -> return c.inputStream.use { it.readBytes() }
                401 -> throw SignInNeeded()
                403 -> throw SyncException("Google Drive refused access (403). Try again later.")
                429, in 500..599 -> throw SyncException("Google Drive is busy ($code). Try again later.")
                else -> throw SyncException("Google Drive answered with an error ($code).")
            }
        } finally {
            c.disconnect()
        }
    }

    override fun list(): List<RemoteFile> =
        json.decodeFromString(FileList.serializer(), String(call("GET", "$API/drive/v3/files?spaces=appDataFolder&pageSize=1000&fields=files($FIELDS)"))).files

    override fun download(id: String) = call("GET", "$API/drive/v3/files/$id?alt=media")

    override fun create(name: String, bytes: ByteArray): RemoteFile {
        val boundary = "vesper-${randomBytes(12).joinToString("") { "%02x".format(it) }}"
        val body = ByteArrayOutputStream().apply {
            val meta = """{"name":${Json.encodeToString(String.serializer(), name)},"parents":["appDataFolder"]}"""
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val out = call("POST", "$API/upload/drive/v3/files?uploadType=multipart&fields=$FIELDS", "multipart/related; boundary=$boundary", body)
        return json.decodeFromString(RemoteFile.serializer(), String(out))
    }

    // HttpURLConnection has no PATCH. Anonymous probe of this endpoint on 2026-11-04: POST alone 404, POST with this header 401, real PATCH 401.
    override fun update(id: String, bytes: ByteArray): RemoteFile {
        val out = call("POST", "$API/upload/drive/v3/files/$id?uploadType=media&fields=$FIELDS", "application/octet-stream", bytes, override = "PATCH")
        return json.decodeFromString(RemoteFile.serializer(), String(out))
    }

    override fun delete(id: String) { call("DELETE", "$API/drive/v3/files/$id") }
}

/** Turns a network failure into plain words. */
inline fun <T> plainNetworkErrors(block: () -> T): T = try {
    block()
} catch (_: IOException) {
    throw SyncException("No connection to Google. Check the network and try again.")
}
