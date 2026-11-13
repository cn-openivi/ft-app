package app.ft.supercast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source contract test: shell helper and main app MUST use the same provider authority. */
class SuperCastIpcContractTest {
    private fun source(path: String): String {
        val working = File(System.getProperty("user.dir"))
        val candidates = listOf(File(working, path), File(working, "../$path"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Missing source for SuperCast IPC contract: $path")
    }

    @Test fun helperMatchesRegisteredProviderAuthority() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        val provider = source("app/src/main/java/app/ft/supercast/SuperCastProvider.kt")
        val helper = source("server/src/main/java/app/ft/server/Bridge.java")
        val authority = Regex("""android:authorities="([^"]+\.supercast)" """.trim())
            .find(manifest)?.groupValues?.get(1) ?: error("Manifest provider missing")
        val appAuthority = Regex("""const val AUTHORITY = "([^"]+)" """.trim())
            .find(provider)?.groupValues?.get(1) ?: error("App constant missing")
        val helperAuthority = Regex("""AUTHORITY = "([^"]+)" """.trim())
            .find(helper)?.groupValues?.get(1) ?: error("Helper constant missing")
        assertEquals(authority, appAuthority)
        assertEquals(authority, helperAuthority)
        assertTrue(authority.startsWith("app.ft."))
    }
}
