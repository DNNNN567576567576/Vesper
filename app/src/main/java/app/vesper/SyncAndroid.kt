package app.vesper

import android.app.Activity
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import app.vesper.sync.DRIVE_SCOPE
import app.vesper.sync.SignInNeeded
import app.vesper.sync.SyncException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "vesper_sync_wrap"
private const val IV_BYTES = 12

/** AuthorizationClient: matched by package + signing SHA-1, so no client id or token lives in the app. State is in noBackupFilesDir. */
class AndroidSyncPlatform(private val app: NotebookApp) : SyncPlatform {
    override val available = true
    override val stateDir = File(app.noBackupFilesDir, "sync")
    private val keyFile = File(stateDir, "key")
    private val signedInFile = File(stateDir, "signed-in")
    private val client get() = Identity.getAuthorizationClient(app)
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_SCOPE))).build()

    override fun isSignedIn() = signedInFile.exists()

    // shortcut: only drops our marker, so signing in again is silent and stays on the same account; revoke via AuthorizationClient if accounts ever need switching.
    override fun signOut() { signedInFile.delete() }

    private fun markSignedIn() { stateDir.mkdirs(); signedInFile.writeText("") }

    private fun authorize(): AuthorizationResult = try {
        Tasks.await(client.authorize(request), 30, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        throw SyncException("Google sign-in took too long. Try again.")
    } catch (e: ExecutionException) {
        // 10 = DEVELOPER_ERROR: package or SHA-1 doesn't match the client in the Cloud console.
        throw SyncException("Google refused the sign-in (code ${(e.cause as? ApiException)?.statusCode ?: "unknown"}).")
    }

    override fun accessToken(): String {
        val r = authorize()
        if (r.hasResolution()) { signedInFile.delete(); throw SignInNeeded() }
        return r.accessToken ?: throw SignInNeeded()
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    override fun loadKey(): ByteArray? {
        val f = keyFile.takeIf { it.exists() }?.readBytes() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, f, 0, IV_BYTES))
        return c.doFinal(f, IV_BYTES, f.size - IV_BYTES)
    }

    override fun storeKey(key: ByteArray?) {
        if (key == null) { keyFile.delete(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        stateDir.mkdirs()
        keyFile.writeBytes(c.iv + c.doFinal(key))
    }

    @Composable
    override fun rememberSignIn(onDone: (Throwable?) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) { markSignedIn(); onDone(null) } else onDone(SyncException("Sign-in was cancelled."))
        }
        return {
            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { authorize() }
                    val pending = r.pendingIntent
                    if (r.hasResolution() && pending != null) launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    else { markSignedIn(); onDone(null) }
                } catch (e: SyncException) { onDone(e) }
            }
        }
    }
}
