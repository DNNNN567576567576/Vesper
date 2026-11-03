package app.vesper.data

/** [message] is short, lowercase and ready to show. [restartNeeded]: the live DB was already closed, restart the app. */
class BackupException(message: String, val restartNeeded: Boolean = false, cause: Throwable? = null) : Exception(message, cause)
