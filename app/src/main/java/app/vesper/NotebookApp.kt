package app.vesper

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.room.Room
import app.vesper.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotebookApp : Application() {
    val db: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "pixel-notebook.db").build()
    }

    /**
     * Writes that must survive leaving a screen (saves, deletes) run here rather than in a
     * ViewModel scope, so closing the editor can never cancel an in-flight write.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun reportError(what: String, e: Throwable) {
        Log.e("Vesper", what, e)
        appScope.launch(Dispatchers.Main) {
            Toast.makeText(this@NotebookApp, "Could not $what", Toast.LENGTH_SHORT).show()
        }
    }
}
