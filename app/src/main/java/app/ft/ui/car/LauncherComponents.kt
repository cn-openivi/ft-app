package app.ft.ui.car

/** A package may publish multiple distinct Android LAUNCHER activities. */
internal object LauncherComponents {
    fun key(pkg: String, activity: String): String = "$pkg/$activity"

    fun <T> uniqueByComponent(
        entries: List<T>,
        packageName: (T) -> String,
        activityName: (T) -> String
    ): List<T> = entries.distinctBy { packageName(it) to activityName(it) }
}
