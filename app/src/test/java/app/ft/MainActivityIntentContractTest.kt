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
}
