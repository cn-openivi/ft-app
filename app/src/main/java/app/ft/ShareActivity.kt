package app.ft

import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.ft.projection.CarControls
import app.ft.core.DiagLog

class ShareActivity : ComponentActivity() {
    companion object {
        // Only the app can launch this non-exported Activity.
        // No external Intent extra on exported MainActivity authorizes consent.
        const val EXTRA_INTERACTIVE = "app.ft.share.INTERACTIVE"
    }

    private val interactive: Boolean
        get() = intent?.getBooleanExtra(EXTRA_INTERACTIVE, false) == true

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val app = application as FTApp
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            app.mirrorResultCode = r.resultCode
            app.mirrorData = r.data
            app.mirrorGranted.value = true
            DiagLog.i("App", if (interactive) "screen sharing consent accepted" else "quiet screen sharing consent accepted")
            CarControls.startAudio(this)
        } else {
            DiagLog.w("App", "screen sharing was not allowed")
            CarControls.shareDeclined(this, quiet = !interactive)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            CarControls.shareDeclined(this, quiet = !interactive)
            finish()
            return
        }
        runCatching {
            val request = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent()
            consent.launch(request)
        }.onFailure {
            DiagLog.w("App", "could not ask for screen sharing: ${it.message}")
            CarControls.shareDeclined(this, quiet = !interactive)
            finish()
        }
    }
}
