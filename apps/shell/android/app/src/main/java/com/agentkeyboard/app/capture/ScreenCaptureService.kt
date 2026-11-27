package com.agentkeyboard.app.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import com.agentkeyboard.app.Ak
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.R
import com.agentkeyboard.core.capture.CaptureGeometry
import com.agentkeyboard.core.capture.FrameSignature
import com.agentkeyboard.core.capture.FrameThrottle
import java.io.ByteArrayOutputStream

/**
 * Real-time screen pixels for the agent (ADR-0010), via MediaProjection.
 *
 * How it stays cheap (the Cluely lesson: always ready, rarely working):
 *  - The compositor downscales into a small VirtualDisplay (≤ 800–1200 px long side by tier), so no
 *    full-resolution frame ever reaches us.
 *  - A VirtualDisplay only produces frames when the screen changes; [FrameThrottle] caps checks at
 *    4/s while it is busy and 1/s once it settles, and frames are dropped before any pixel is read
 *    unless [ScreenCapture.wantsFrames] (keyboard up or Autopilot armed, no secure field).
 *  - Only the app region above the keyboard is fingerprinted ([FrameSignature], ~25 k samples) and,
 *    when it actually changed, JPEG-encoded into the in-memory ring.
 *
 * How it stays private: frames live in RAM only (30 s TTL, zeroed on eviction), are purged on
 * secure fields, blocked apps and `FLAG_SECURE` (black) frames, and never leave the device. Android
 * shows its own capture indicator, and our notification has a Stop action.
 *
 * Android 14+: the service must already be a `mediaProjection` foreground service when
 * [MediaProjectionManager.getMediaProjection] is called; the consent token is single-use, so every
 * session asks again (by design: the user sees the system dialog each time).
 */
class ScreenCaptureService : Service() {

    private val thread = HandlerThread("ak-capture", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var surface: CaptureGeometry.Surface? = null
    private var realHeight = 0
    private var statusBarPx = 0
    private var navBarPx = 0
    private val throttle = FrameThrottle()
    private var scratch: Bitmap? = null // capture thread only

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = shutdown()
    }

    override fun onCreate() {
        super.onCreate()
        thread.start()
        handler = Handler(thread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = resultData(intent)
                if (code != Activity.RESULT_OK || data == null || !promote()) {
                    ScreenCapture.onProjectionStopped()
                    stopSelf()
                } else {
                    start(code, data)
                }
            }
            else -> shutdown() // ACTION_STOP, or a restart with no intent: never capture without fresh consent
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        thread.quitSafely()
        super.onDestroy()
    }

    private fun promote(): Boolean = try {
        val n = notification(this)
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) // minSdk 29
        true
    } catch (e: RuntimeException) { // ForegroundServiceStartNotAllowed / missing type permission
        AkLog.w("screen capture foreground refused: ${e::class.java.simpleName}")
        false
    }

    private fun start(code: Int, data: Intent) {
        if (projection != null) releaseProjection() // a second consent replaces the running session
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: RuntimeException) { // SecurityException: token reused or FGS type missing
            AkLog.w("getMediaProjection failed: ${e::class.java.simpleName}"); null
        } ?: return shutdown()
        projection = p
        p.registerCallback(callback, handler) // required before createVirtualDisplay on API 34+

        val metrics = realMetrics()
        realHeight = metrics.heightPixels
        val s = CaptureGeometry.surface(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, CaptureGeometry.maxLongSide(Ak.tier))
        surface = s
        val r = ImageReader.newInstance(s.width, s.height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, handler)
        reader = r
        display = try {
            p.createVirtualDisplay("ak-screen", s.width, s.height, s.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
        } catch (e: RuntimeException) {
            AkLog.w("createVirtualDisplay failed: ${e::class.java.simpleName}"); null
        }
        if (display == null) return shutdown()
        throttle.reset()
        ScreenCapture.onProjectionStarted()
    }

    /** Capture thread. Returns as early as possible; pixels are only read for frames worth keeping. */
    private fun onFrame(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        try {
            if (!ScreenCapture.wantsFrames) return
            if (!throttle.shouldCheck(System.currentTimeMillis())) return
            val s = surface ?: return
            val region = CaptureGeometry.appRegion(s, statusBarPx, ScreenCapture.keyboardTopPx, navBarPx, realHeight) ?: return
            val plane = image.planes[0]
            val buf = plane.buffer
            val row = plane.rowStride
            val px = plane.pixelStride
            val sig = FrameSignature.of(region.left, region.top, region.right, region.bottom) { x, y ->
                val i = y * row + x * px
                FrameSignature.luma(buf.get(i).toInt() and 0xFF, buf.get(i + 1).toInt() and 0xFF, buf.get(i + 2).toInt() and 0xFF)
            }
            when (throttle.admit(sig)) {
                FrameThrottle.Verdict.BLANK -> ScreenCapture.onBlankFrame(true)
                FrameThrottle.Verdict.UNCHANGED -> { ScreenCapture.onBlankFrame(false); ScreenCapture.ring.touchLatest() }
                FrameThrottle.Verdict.THROTTLED -> Unit
                FrameThrottle.Verdict.STORE -> {
                    ScreenCapture.onBlankFrame(false)
                    val bmpWidth = row / px
                    val full = scratch?.takeIf { it.width == bmpWidth && it.height == s.height }
                        ?: Bitmap.createBitmap(bmpWidth, s.height, Bitmap.Config.ARGB_8888).also { scratch?.recycle(); scratch = it }
                    buf.rewind()
                    full.copyPixelsFromBuffer(buf)
                    val crop = Bitmap.createBitmap(full, region.left, region.top, region.width, region.height)
                    val out = ByteArrayOutputStream(64 * 1024)
                    crop.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    if (crop !== full) crop.recycle()
                    // Re-check: a secure field may have appeared while we were encoding.
                    if (ScreenCapture.wantsFrames) ScreenCapture.ring.add(out.toByteArray(), region.width, region.height)
                }
            }
        } catch (e: RuntimeException) {
            AkLog.w("frame dropped: ${e::class.java.simpleName}")
        } finally {
            image.close()
        }
    }

    private fun shutdown() {
        handler.post { scratch?.recycle(); scratch = null }
        releaseProjection()
        ScreenCapture.onProjectionStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseProjection() {
        val d = display
        val r = reader
        display = null
        reader = null
        handler.post { d?.release(); r?.close() }
        projection?.let { p ->
            projection = null
            p.unregisterCallback(callback)
            p.stop()
        }
    }

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val insets = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            statusBarPx = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            navBarPx = insets.bottom
            dm.widthPixels = m.bounds.width()
            dm.heightPixels = m.bounds.height()
            dm.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
        }
        return dm
    }

    private fun resultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

    companion object {
        const val ACTION_START = "com.agentkeyboard.app.capture.START"
        const val ACTION_STOP = "com.agentkeyboard.app.capture.STOP"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        private const val CHANNEL = "screen_capture"
        private const val NOTIFICATION_ID = 42
        private const val JPEG_QUALITY = 72

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.capture_channel_desc)
                        setShowBadge(false)
                    },
                )
            }
            val stop = PendingIntent.getService(
                context, 0, Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(context.getString(R.string.capture_fgs_title))
                .setContentText(context.getString(R.string.capture_fgs_text))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel), context.getString(R.string.capture_stop), stop).build())
                .setContentIntent(stop)
                .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
                .build()
        }
    }
}
