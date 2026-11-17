package app.vesper

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.ui.PageFont
import app.vesper.ui.PixelButton
import app.vesper.ui.PixelTheme
import app.vesper.ui.Px
import app.vesper.ui.TitleFont

/** The Intent that opens the ringing screen for an alarm. */
internal fun ringScreen(ctx: Context, hour: Int, minute: Int, label: String): Intent =
    Intent(ctx, AlarmRingActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(AlarmKeys.HOUR, hour).putExtra(AlarmKeys.MINUTE, minute).putExtra(AlarmKeys.LABEL, label)

/** Shown over the lock screen while an alarm rings: big time, label, Stop and Snooze. */
class AlarmRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        val time = AlarmLogic.timeLabel(intent.getIntExtra(AlarmKeys.HOUR, 0), intent.getIntExtra(AlarmKeys.MINUTE, 0))
        val label = intent.getStringExtra(AlarmKeys.LABEL).orEmpty()
        setContent {
            PixelTheme {
                // The service ends the ring (timeout, notification buttons): close with it.
                val ringing by AlarmService.ringing.collectAsStateWithLifecycle()
                LaunchedEffect(ringing) { if (ringing == null) finish() }
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF0B0612)).padding(horizontal = 28.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(time, style = TextStyle(fontFamily = TitleFont, fontSize = 110.sp, lineHeight = 120.sp, color = Px.Lavender))
                    if (label.isNotBlank()) {
                        Text(label, textAlign = TextAlign.Center, style = TextStyle(fontFamily = PageFont, fontSize = 34.sp, lineHeight = 36.sp, color = Px.Text))
                    }
                    Spacer(Modifier.height(64.dp))
                    PixelButton("STOP", { send(AlarmKeys.STOP) }, primary = true, modifier = Modifier.fillMaxWidth().height(72.dp))
                    Spacer(Modifier.height(16.dp))
                    PixelButton("SNOOZE 10 MIN", { send(AlarmKeys.SNOOZE) }, modifier = Modifier.fillMaxWidth().height(72.dp))
                }
            }
        }
    }

    private fun send(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action))
        finish()
    }
}
