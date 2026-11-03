package app.vesper.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Room 2.8's SupportSQLiteDriver matches the requested file against the helper's name by splitting on '/' only,
 * so on Windows (backslash paths under Robolectric) every createDatabase fails. Reporting the full path as the
 * name makes the check pass; the file opened is the same one. Test-only; Android paths always use '/'.
 */
private class FullPathNameFactory : SupportSQLiteOpenHelper.Factory {
    private val real = FrameworkSQLiteOpenHelperFactory()
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = real.create(configuration)
        val fullPath = configuration.name?.let { configuration.context.getDatabasePath(it).absolutePath }
        return object : SupportSQLiteOpenHelper by helper {
            override val databaseName: String? get() = fullPath
        }
    }
}

fun migrationTestHelper() = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FullPathNameFactory(),
)
