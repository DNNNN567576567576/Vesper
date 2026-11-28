package app.vesper

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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

    /** The widget's "open" tap: show the Brain tab with the Daily card; the composition clears it. */
    private var openDaily by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        // Only a fresh launch acts on the intent; a rotation must not ask again.
        intent?.let(::openFromWidget) // also after the system revived a killed task, when savedInstanceState is set
        if (savedInstanceState == null) intent?.let(::handle)
        val host = application as NotebookApp
        val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        host.askNotifications = { if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        setContent {
            PixelTheme {
                App(host, shared = shared, onSharedHandled = { shared = null }, openDaily = openDaily, onOpenDailyHandled = { openDaily = false })
            }
        }
    }

    override fun onDestroy() {
        (application as NotebookApp).askNotifications = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromWidget(intent)
        handle(intent)
    }

    /** The widget's "open" tap; the extra is used up, so a rotation does not open the card again. */
    private fun openFromWidget(intent: Intent) {
        if (intent.action == DailyWidget.ACTION_OPEN_DAILY || intent.getStringExtra("open") == "daily") { openDaily = true; intent.removeExtra("open"); intent.action = null }
    }

    private fun handle(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) return
        shared = SharedText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }
}
