package app.ft

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static security contract; runtime third-party Intent tests remain necessary. */
class MainActivityIntentContractTest {
    @Test fun exportedActivityDoesNotTrustArbitraryExtras() {
        val cwd = File(System.getProperty("user.dir"))
        val path = "app/src/main/java/app/ft/MainActivity.kt"
        val source = listOf(File(cwd, path), File(cwd, "../$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("MainActivity missing")
        val handler = source.substringAfter("private fun handleIntent(intent: Intent?)")
            .substringBefore("private fun pickAndroidAuto()")
        assertTrue(handler.contains("intent.action != Intent.ACTION_VIEW"))
        assertTrue(handler.contains("link.scheme != Backup.SCHEME"))
        assertTrue(handler.contains("link.host != Backup.LOOK_HOST"))
        assertFalse(handler.contains("getBooleanExtra("))
        assertFalse(handler.contains("getIntExtra("))
        assertFalse(handler.contains("getStringExtra("))
    }
    @Test fun carLifeConsentUsesNonExportedInteractiveShareActivity() {
        val cwd = File(System.getProperty("user.dir"))
        fun src(path: String) = listOf(File(cwd, path), File(cwd, "../$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
        val car = src("app/src/main/java/app/ft/carlife/CarLifeService.kt")
        val start = car.substringAfter("private fun askForScreenShare()")
            .substringBefore("fun volume(up:")
        assertTrue(start.contains("Intent(this, ShareActivity::class.java)"))
        assertTrue(start.contains("putExtra(ShareActivity.EXTRA_INTERACTIVE, true)"))
        assertFalse(start.contains("Intent(this, MainActivity::class.java)"))
        val share = src("app/src/main/java/app/ft/ShareActivity.kt")
        assertTrue(share.contains("CarControls.shareDeclined(this, quiet = !interactive)"))
        // Android 10+ may reject background Activity launch; tapping the
        // foreground notification must offer an explicit safe consent path.
        assertTrue(car.contains("PendingIntent.getActivity(this, 182"))
        assertTrue(car.contains("Lang.str(R.string.car_allow_share)"))
        val manifest = src("app/src/main/AndroidManifest.xml")
        val entry = manifest.substringAfter("android:name=\".ShareActivity\"").substringBefore("/>")
        assertTrue(entry.contains("android:exported=\"false\""))
        val main = src("app/src/main/java/app/ft/MainActivity.kt")
        assertFalse(main.contains("private fun requestMirror()"))
    }

}
