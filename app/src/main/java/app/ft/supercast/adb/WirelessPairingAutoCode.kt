package app.ft.supercast.adb

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.ft.FTTouchService

/** Explicit one-shot consent: only while pairing Settings is open, max 90s. */
internal object WirelessPairingAutoCode {
    private val capture = WirelessPairingWindowCapture { SystemClock.elapsedRealtime() }

    fun arm(context: Context): Boolean {
        if (!FTTouchService.enabled) return false
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        val pkg = context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            ?: return false // Unknown OEM Settings package: never broaden capture.
        capture.arm(pkg)
        return true
    }

    fun cancel() = capture.cancel()

    fun onEvent(event: AccessibilityEvent?, root: AccessibilityNodeInfo?, context: Context) {
        if (event == null || root == null) return
        val port = WirelessPairingService.currentPort() ?: return
        val texts = ArrayList<String>(32)
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12 || visited++ > 160) return
            node.text?.toString()?.takeIf { it.length <= 120 }?.let { texts.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
                if (visited > 160) return
            }
        }
        walk(root, 0)
        val code = capture.codeForOpenedSystemDialog(event.packageName?.toString(), texts, port)
            ?: return
        WirelessPairingService.submitAutoCode(context, port, code)
    }
}
