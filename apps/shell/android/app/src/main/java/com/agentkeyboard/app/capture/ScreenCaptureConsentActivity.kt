package com.agentkeyboard.app.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.agentkeyboard.app.AkLog

/**
 * Invisible trampoline for the system "Start recording or casting?" dialog (MediaProjection consent
 * must come from an Activity). The keyboard closes while Android shows it: that is the platform's
 * doing, and it guarantees the user sees exactly what they agree to. On OK the token goes straight to
 * [ScreenCaptureService]; it is single-use and never stored.
 */
class ScreenCaptureConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // recreated mid-dialog: the pending result still arrives
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            @Suppress("DEPRECATION") // platform Activity result API; no AndroidX dependency on this path
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST)
        } catch (e: RuntimeException) {
            AkLog.w("screen capture dialog unavailable: ${e::class.java.simpleName}")
            ScreenCapture.onConsentDenied()
            finish()
        }
    }

    @Deprecated("platform Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return
        if (resultCode == RESULT_OK && data != null) {
            val start = Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_START)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            try {
                startForegroundService(start)
            } catch (e: RuntimeException) {
                AkLog.w("screen capture service refused: ${e::class.java.simpleName}")
                ScreenCapture.onConsentDenied()
            }
        } else {
            ScreenCapture.onConsentDenied()
        }
        finish()
    }

    private companion object {
        const val REQUEST = 7
    }
}
