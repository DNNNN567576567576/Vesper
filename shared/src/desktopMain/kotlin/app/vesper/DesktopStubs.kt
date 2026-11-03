package app.vesper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import app.vesper.data.BackupException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties

/** UI preferences in a properties file (M3 points it at %APPDATA%\Vesper). */
class PropertiesSettings(private val file: File) : Settings {
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    override fun getString(key: String): String? = synchronized(props) { props.getProperty(key) }
    override fun putString(key: String, value: String) = synchronized(props) { props.setProperty(key, value); save() }
    override fun getBoolean(key: String, default: Boolean) = getString(key)?.toBooleanStrictOrNull() ?: default
    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    private fun save() {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }
}

// shortcut: desktop Grimoire is text only; photos/videos show an empty frame. Real media comes in M6.
object DesktopShrineMedia : ShrineMedia {
    override fun isVideo(ref: String) = false
    override fun import(ref: String, video: Boolean): String = throw UnsupportedOperationException("media is phone only for now")
    override fun delete(name: String) {}
    override fun cached(name: String, widthPx: Int): ImageBitmap? = null
    override suspend fun load(name: String, widthPx: Int, cache: Boolean, video: Boolean): ImageBitmap? = null
    override suspend fun thumb(ref: String, edge: Int): ImageBitmap? = null

    @Composable
    override fun rememberPicker(onPick: (String) -> Unit): () -> Unit = {}

    @Composable
    override fun VideoPlayer(name: String, onClose: () -> Unit) = LaunchedEffect(Unit) { onClose() }
}

// shortcut: backups are phone only until the desktop vault lands (M6); the buttons do nothing here.
object DesktopVault : Vault {
    override val lastBackupAt: StateFlow<Long?> = MutableStateFlow(null)

    @Composable
    override fun rememberSavePicker(onPick: (String) -> Unit): (String) -> Unit = {}

    @Composable
    override fun rememberOpenPicker(onPick: (String) -> Unit): () -> Unit = {}

    override suspend fun write(target: String) = throw BackupException("backups are phone only for now.")
    override suspend fun restore(source: String) = throw BackupException("backups are phone only for now.")
    override fun restart() {}
}
