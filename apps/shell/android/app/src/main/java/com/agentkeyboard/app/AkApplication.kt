package com.agentkeyboard.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.util.Log
import com.agentkeyboard.core.device.DeviceTiers
import com.agentkeyboard.core.protocol.DeviceTier

/**
 * Runs in every process (main, `:agent`). Nothing heavy happens here: cold start of the IME process
 * is on the keyboard's critical path (NFR-P-03), so every subsystem is created lazily by its owner.
 */
class AkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Ak.init(this)
    }
}

object Ak {
    lateinit var app: Application
        private set

    /** Process-wide device facts, computed once. */
    val tier: DeviceTier by lazy { DeviceTiers.fromTotalMem(totalMemBytes, isLowRam) }
    val totalMemBytes: Long by lazy {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem
    }
    private val isLowRam: Boolean by lazy { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice }

    val flavor: String get() = if (BuildConfig.A11Y_ACTIONS) "full" else "play"

    val isAgentProcess: Boolean by lazy { processName().endsWith(":agent") }

    fun init(application: Application) {
        app = application
    }

    private fun processName(): String = Application.getProcessName() // API 28+; minSdk is 29
}

/**
 * Logging policy (SPEC §17/§18): message types, codes and timings only. User text, field contents,
 * notification bodies and agent output are never logged, in any build type. Verbose/debug calls are
 * stripped from release builds by R8 (see proguard-rules.pro).
 */
object AkLog {
    private const val TAG = "AgentKeyboard"
    fun d(msg: String) { Log.d(TAG, msg) }
    fun i(msg: String) { Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { Log.w(TAG, msg, t) }
}
