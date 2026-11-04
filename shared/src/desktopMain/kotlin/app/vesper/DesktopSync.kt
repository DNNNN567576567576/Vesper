package app.vesper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import app.vesper.sync.DRIVE_SCOPE
import app.vesper.sync.SignInNeeded
import app.vesper.sync.SyncException
import app.vesper.sync.plainNetworkErrors
import app.vesper.sync.randomBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.awt.Desktop
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
private val b64url = Base64.getUrlEncoder().withoutPadding()
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/**
 * Installed-app OAuth: loopback redirect + PKCE, refresh token and data key as plain files in [stateDir].
 * shortcut: not DPAPI, because the plaintext DB sits next to them anyway; encrypt all three together if that changes.
 */
class DesktopSyncPlatform(override val stateDir: File) : SyncPlatform {
    private val tokenFile = File(stateDir, "token")
    private val keyFile = File(stateDir, "key")
    private var cached: Pair<String, Long>? = null

    /** Client id and secret, packed into the jar at build time. Never logged. */
    private val client: Pair<String, String>? by lazy {
        javaClass.getResourceAsStream("/oauth-client.json")?.use { s ->
            runCatching {
                val c = Json.parseToJsonElement(s.readBytes().decodeToString()).jsonObject.getValue("installed").jsonObject
                c.getValue("client_id").jsonPrimitive.content to c.getValue("client_secret").jsonPrimitive.content
            }.getOrNull()
        }
    }

    override val available get() = client != null
    override fun isSignedIn() = tokenFile.exists()

    private fun tokenRequest(form: String): Map<String, kotlinx.serialization.json.JsonElement> = plainNetworkErrors {
        val c = URL(TOKEN_URL).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
            when (c.responseCode) {
                200 -> Json.parseToJsonElement(c.inputStream.use { it.readBytes() }.decodeToString()).jsonObject
                400, 401 -> throw SignInNeeded() // invalid_grant: refresh tokens last 7 days while the Google app is in Testing
                else -> throw SyncException("Google sign-in is having trouble (${c.responseCode}). Try again later.")
            }
        } finally {
            c.disconnect()
        }
    }

    @Synchronized
    override fun accessToken(): String {
        cached?.let { (token, until) -> if (System.currentTimeMillis() < until) return token }
        val (id, secret) = client ?: throw SyncException("This build has no Google client.")
        val refresh = tokenFile.takeIf { it.exists() }?.readText() ?: throw SignInNeeded()
        val r = try {
            tokenRequest("grant_type=refresh_token&refresh_token=${enc(refresh)}&client_id=${enc(id)}&client_secret=${enc(secret)}")
        } catch (e: SignInNeeded) {
            tokenFile.delete()
            throw e
        }
        val token = r.getValue("access_token").jsonPrimitive.content
        cached = token to System.currentTimeMillis() + (r.getValue("expires_in").jsonPrimitive.long - 60) * 1000
        return token
    }

    private fun signIn() {
        val (id, secret) = client ?: throw SyncException("This build has no Google client.")
        val verifier = b64url.encodeToString(randomBytes(32))
        val challenge = b64url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64url.encodeToString(randomBytes(16))
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 180_000
            val redirect = "http://127.0.0.1:${server.localPort}"
            Desktop.getDesktop().browse(URI("$AUTH_URL?client_id=${enc(id)}&redirect_uri=${enc(redirect)}&response_type=code&scope=${enc(DRIVE_SCOPE)}" +
                "&code_challenge=$challenge&code_challenge_method=S256&state=$state&access_type=offline&prompt=consent"))
            val code = try { awaitCode(server, state) } catch (_: SocketTimeoutException) { throw SyncException("Sign-in timed out. Try again.") }
            val r = tokenRequest("grant_type=authorization_code&code=${enc(code)}&client_id=${enc(id)}&client_secret=${enc(secret)}&code_verifier=$verifier&redirect_uri=${enc(redirect)}")
            stateDir.mkdirs()
            tokenFile.writeText(r["refresh_token"]?.jsonPrimitive?.content ?: throw SyncException("Google gave no sign-in token. Try again."))
        }
    }

    /** Ignores anything that isn't the browser coming back with our state (favicon requests, strays). */
    private fun awaitCode(server: ServerSocket, state: String): String {
        while (true) {
            server.accept().use { s ->
                s.soTimeout = 2_000 // browsers open idle sockets ahead of time; one must not block the real request behind it
                val target = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull()?.split(" ")?.getOrNull(1).orEmpty()
                val q = target.substringAfter('?', "").split("&").associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
                val ours = q["state"] == state
                val done = ours && ("code" in q || "error" in q)
                val page = if (done && "code" in q) "Signed in. You can close this tab and go back to Vesper." else "Vesper sign-in did not finish."
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n$page".toByteArray())
                if (done) return q["code"] ?: throw SyncException("Sign-in was cancelled.")
            }
        }
    }

    override fun loadKey(): ByteArray? = keyFile.takeIf { it.exists() }?.readBytes()

    override fun storeKey(key: ByteArray?) {
        if (key == null) keyFile.delete() else { stateDir.mkdirs(); keyFile.writeBytes(key) }
    }

    @Composable
    override fun rememberSignIn(onDone: (Throwable?) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        return {
            scope.launch(Dispatchers.IO) { onDone(runCatching { signIn() }.exceptionOrNull()) }
        }
    }
}
