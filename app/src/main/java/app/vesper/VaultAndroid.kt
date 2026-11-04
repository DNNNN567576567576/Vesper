package app.vesper

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import app.vesper.data.restartApp
import app.vesper.data.restoreBackup
import app.vesper.data.writeBackup
import kotlinx.coroutines.flow.StateFlow

/** The Brain vault on Android: system document pickers and the zip backup in data/Backup.kt. Targets are uri strings. */
class AndroidVault(private val app: NotebookApp) : Vault {
    override val lastBackupAt: StateFlow<Long?> get() = app.lastBackupAt

    @Composable
    override fun rememberSavePicker(onPick: (String) -> Unit): (String) -> Unit {
        val l = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) onPick(uri.toString()) }
        return { name -> l.launch(name) }
    }

    @Composable
    override fun rememberOpenPicker(onPick: (String) -> Unit): () -> Unit {
        val l = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPick(uri.toString()) }
        return { l.launch(arrayOf("application/zip", "application/octet-stream")) }
    }

    override suspend fun write(target: String) = writeBackup(app, Uri.parse(target))
    override suspend fun restore(source: String) = app.sync.duringRestore { beforeSwap -> restoreBackup(app, Uri.parse(source), beforeSwap) }
    override fun restart() = restartApp(app)
}
