package app.vesper.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.vesper.App
import app.vesper.ui.PixelTheme
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
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Vesper",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
        ) {
            window.minimumSize = Dimension(800, 600)
            PixelTheme { App(host, null, {}, null, {}) }
        }
    }
    lock.release()
}
