package app.vesper.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.vesper.App
import app.vesper.ui.handleDesktopBack
import app.vesper.ui.PixelTheme
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess

private val dataDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Vesper")

fun main() {
    dataDir.mkdirs()
    // One writer per DB: the lock lives as long as the process; a second instance exits quietly.
    val lock = RandomAccessFile(File(dataDir, "app.lock"), "rw").channel.tryLock() ?: exitProcess(0)
    val host = DesktopHost(dataDir)
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Vesper",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
            onPreviewKeyEvent = { it.key == Key.Escape && it.type == KeyEventType.KeyDown && handleDesktopBack() },
        ) {
            window.minimumSize = Dimension(800, 600)
            PixelTheme { App(host, null, {}) }
        }
    }
    // The closing editor's last save and the last sync run on appScope; give them a moment before the JVM ends.
    host.sync.shutdown()
    runBlocking { withTimeoutOrNull(5_000) { host.appScope.coroutineContext.job.children.forEach { it.join() } } }
    lock.release()
    exitProcess(0)
}
