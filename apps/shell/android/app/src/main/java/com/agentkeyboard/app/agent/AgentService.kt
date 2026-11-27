package com.agentkeyboard.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.MainActivity
import com.agentkeyboard.app.R
import com.agentkeyboard.core.protocol.PROTOCOL_VERSION

/**
 * Binder front of `:agent` (ADR-0003). It is deliberately thin: it checks the caller, hops every
 * call onto [AgentHub]'s thread, and owns the specialUse foreground state while a run is in flight
 * (SPEC §17). All behaviour lives in `core.agent.AgentRuntime`.
 */
class AgentService : Service() {

    private val binder = object : IAgentService.Stub() {
        override fun protocolVersion(): Int = PROTOCOL_VERSION

        override fun post(envelopeJson: String) {
            if (trusted()) AgentHub.post { it.post(envelopeJson) }
        }

        override fun recordClipboard(text: String, sensitiveSource: Boolean) {
            if (trusted()) AgentHub.post { it.recordClipboard(text, sensitiveSource) }
        }

        override fun focusChanged(fieldContextJson: String) {
            if (trusted()) AgentHub.post { it.focusChanged(fieldContextJson) }
        }

        override fun appendAudit(auditJson: String) {
            if (trusted()) AgentHub.post { it.appendAudit(auditJson) }
        }

        override fun register(callback: IAgentCallback) {
            if (trusted()) AgentHub.callbacks.register(callback)
        }

        override fun unregister(callback: IAgentCallback) {
            AgentHub.callbacks.unregister(callback)
        }

        /** Not exported, so only our UID can bind; checked again in case that ever changes. */
        private fun trusted(): Boolean = (Binder.getCallingUid() == Process.myUid()).also {
            if (!it) AkLog.w("rejected call from uid ${Binder.getCallingUid()}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        AgentHub.foreground = fg
        AgentHub.post { } // open the database and seed off the binder path, before the first request
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only ever started by startForegroundCompat(): promote within the 10 s window, every time.
        if (intent?.action == ACTION_FOREGROUND) setForeground(true)
        return START_NOT_STICKY // a killed draft is abandoned, never silently resumed
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) AgentHub.onTrimMemory() // Part 3: also unloads the model (§12.2)
    }

    override fun onDestroy() {
        if (AgentHub.foreground === fg) AgentHub.foreground = null
        super.onDestroy()
    }

    private var isForeground = false
    private val fg: (Boolean) -> Unit = ::setForeground

    private fun setForeground(on: Boolean) {
        if (on == isForeground) return
        if (on) {
            try {
                val n = notification(this)
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIFICATION_ID, n)
                isForeground = true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+): the run continues unprotected.
                AkLog.w("foreground not allowed now", e)
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf() // drops the "started" state only; stays alive while the IME is bound
            isForeground = false
        }
    }

    companion object {
        const val ACTION_FOREGROUND = "com.agentkeyboard.app.agent.FOREGROUND"
        private const val CHANNEL_ACTIVITY = "agent_activity"
        private const val NOTIFICATION_ID = 41

        /** Used when no service instance exists yet (e.g. a background reply in Part 3). */
        fun startForegroundCompat(context: Context) {
            try {
                context.startForegroundService(Intent(context, AgentService::class.java).setAction(ACTION_FOREGROUND))
            } catch (e: IllegalStateException) {
                AkLog.w("startForegroundService refused", e)
            }
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ACTIVITY) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ACTIVITY, context.getString(R.string.agent_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.agent_channel_desc)
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL_ACTIVITY)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(context.getString(R.string.agent_fgs_title))
                .setContentText(context.getString(R.string.agent_fgs_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
                .build()
        }
    }
}
