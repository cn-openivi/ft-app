package app.ft

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-only regression guards. Runtime and instrumented tests remain pending. */
class FT182SourceRegressionContractTest {
    private fun src(path: String): String {
        val root = File(System.getProperty("user.dir"))
        return listOf(File(root, path), File(root, "../$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing file: $path")
    }

    @Test fun permissionsAreNotPromptedFromActivityOnCreate() {
        val main = src("app/src/main/java/app/ft/MainActivity.kt")
        assertFalse(main.contains("requestRuntimePermissions()"))
        val home = src("app/src/main/java/app/ft/ui/home/HomeScreen.kt")
        assertTrue(home.contains("missingConnectionPermissions(context)"))
        assertTrue(home.contains("connectionPermissionLauncher.launch(missing)"))
    }

    @Test fun carMediaRefreshesAfterPhoneGrant() {
        val music = src("app/src/main/java/app/ft/ui/car/CarMusic.kt")
        val video = src("app/src/main/java/app/ft/ui/car/CarVideos.kt")
        assertTrue(music.contains("permissionVersion.collectAsState()"))
        assertTrue(video.contains("permissionVersion.collectAsState()"))
    }

    @Test fun untrustedBluetoothDeviceDoesNotStartCar() {
        val receiver = src("app/src/main/java/app/ft/CarTriggerReceiver.kt")
        assertFalse(receiver.contains("want.isEmpty() ||"))
        assertTrue(receiver.contains("if (matched)"))
    }

    @Test fun webKeyboardSafelyQuotesInsertedText() {
        val browser = src("app/src/main/java/app/ft/ui/car/CarBrowser.kt")
        assertTrue(browser.contains("JSONObject.quote(text)"))
    }

    @Test fun iccoaClusterSettingCannotClaimUnverifiedSupport() {
        val settings = src("app/src/main/java/app/ft/ui/settings/SettingsScreen.kt")
        assertTrue(settings.contains("aa_cluster_iccoa_unverified"))
        assertTrue(settings.contains("CarProtocol.CARLIFE"))
    }
}
