package app.ft.core

/** Do not alter system orientation for unrelated windows, IMEs or system dialogs. */
internal object MirrorOrientationPolicy {
    const val PORTRAIT = 0
    const val LANDSCAPE = 1
    fun desired(targetPackage: String, homePackage: String, observedPackage: String?): Int? =
        when (observedPackage) {
            homePackage -> PORTRAIT
            targetPackage -> if (targetPackage == homePackage) PORTRAIT else LANDSCAPE
            else -> null
        }
}
