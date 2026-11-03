package app.vesper

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vesper.data.Note
import app.vesper.ui.PixelTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Pending launcher-shortcut action, consumed by the composition. */
    private var request by mutableStateOf<String?>(null)

    /** Id of a note just created from a share; the composition opens it in the editor. */
    private var sharedNote by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        // Only a fresh launch acts on the intent; a rotation must not reopen the editor.
        if (savedInstanceState == null) intent?.let(::handle)
        val host = application as NotebookApp
        setContent {
            PixelTheme {
                App(host, request = request, onRequestHandled = { request = null }, sharedNote = sharedNote, onSharedHandled = { sharedNote = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) { request = intent.action; return }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) return
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val app = application as NotebookApp
        app.appScope.launch {
            try {
                val dao = app.db.noteDao()
                val id = dao.insert(Note(title = title, content = text, position = (dao.minPosition() ?: 1) - 1))
                sharedNote = id
            } catch (e: Exception) { app.reportError("save shared text", e) }
        }
    }
}
