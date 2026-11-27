package com.agentkeyboard.app.system

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.agentkeyboard.app.Ak
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.MainActivity
import com.agentkeyboard.app.a11y.ScreenReaderService
import com.agentkeyboard.app.ime.AgentImeService
import com.agentkeyboard.app.notify.AkNotificationListener
import com.agentkeyboard.core.protocol.SystemSettingsTarget
import com.agentkeyboard.core.protocol.SystemStatus

/** System integration status and deep links for the companion's setup checklist (SPEC §17.2). */
object SystemSettings {

    fun status(context: Context, modelsInstalled: Boolean): SystemStatus {
        val imeId = ComponentName(context, AgentImeService::class.java).flattenToShortString()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == context.packageName && it.serviceName == AgentImeService::class.java.name }
        val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.flattenToShortString() == imeId } ?: false
        return SystemStatus(
            imeEnabled = enabled,
            imeSelected = selected,
            notificationAccess = notificationAccess(context),
            screenReading = accessibilityEnabled(context),
            flavor = Ak.flavor,
            tier = Ak.tier,
            totalRamMb = (Ak.totalMemBytes / (1024 * 1024)).toInt(),
            modelsInstalled = modelsInstalled,
        )
    }

    fun notificationAccess(context: Context): Boolean {
        val cn = ComponentName(context, AkNotificationListener::class.java)
        return (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationListenerAccessGranted(cn)
    }

    private fun accessibilityEnabled(context: Context): Boolean {
        val cn = ComponentName(context, ScreenReaderService::class.java)
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == cn } ?: false
    }

    fun open(context: Context, target: SystemSettingsTarget) {
        if (target == SystemSettingsTarget.IME_PICKER) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            return
        }
        val intent = when (target) {
            SystemSettingsTarget.IME_SETTINGS -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            SystemSettingsTarget.NOTIFICATION_ACCESS -> if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, AkNotificationListener::class.java).flattenToString())
            } else {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }
            SystemSettingsTarget.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            SystemSettingsTarget.IME_PICKER -> return
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // Some OEM builds drop the detail screens: fall back to the top-level Settings page.
            AkLog.w("settings screen missing for ${target.wire}")
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Opens a companion route (e.g. `/agents/new`); the route was validated by the protocol decoder. */
    fun openCompanion(context: Context, route: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("https://ak.local$route"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }
}
