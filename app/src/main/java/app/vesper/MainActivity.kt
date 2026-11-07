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
import app.vesper.ui.PixelTheme
import app.vesper.ui.SharedText

class MainActivity : ComponentActivity() {
    /** Text just shared to Vesper; the composition asks where to keep it and clears this. */
    private var shared by mutableStateOf<SharedText?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        // Only a fresh launch acts on the intent; a rotation must not ask again.
        if (savedInstanceState == null) intent?.let(::handle)
        val host = application as NotebookApp
        setContent {
            PixelTheme {
                App(host, shared = shared, onSharedHandled = { shared = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) return
        shared = SharedText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }
}
