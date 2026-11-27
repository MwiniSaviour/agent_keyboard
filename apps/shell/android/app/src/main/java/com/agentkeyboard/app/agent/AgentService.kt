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
import android.os.ParcelFileDescriptor
import android.os.Process
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.MainActivity
import com.agentkeyboard.app.R
import com.agentkeyboard.core.agent.AgentRuntime
import com.agentkeyboard.core.protocol.PROTOCOL_VERSION

/**
 * Binder front of `:agent` (ADR-0003). It is deliberately thin: it checks the caller, hops every
 * call onto [AgentHub]'s thread, and owns the foreground state: `specialUse` while a run is in flight
 * (SPEC §17), `dataSync` while a user-started model download/import runs (ADR-0011), both when both.
 * All behaviour lives in `core.agent.AgentRuntime` and `core.models.ModelService`.
 */
class AgentService : Service() {

    private val binder = object : IAgentService.Stub() {
        override fun protocolVersion(): Int = PROTOCOL_VERSION

        override fun post(envelopeJson: String, origin: String) {
            if (trusted() && origin in AgentRuntime.ORIGINS) AgentHub.post { it.post(envelopeJson, origin) }
        }

        override fun remoteReply(requestId: String, conversationKey: String, text: String) {
            if (trusted()) AgentHub.post { it.remoteReply(requestId, conversationKey, text) }
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

        override fun importModel(file: ParcelFileDescriptor, name: String, sizeBytes: Long) {
            if (!trusted()) {
                runCatching { file.close() }
                return
            }
            AgentHub.importModel(file, name, sizeBytes)
        }

        override fun register(callback: IAgentCallback, origin: String) {
            // The origin is the registration cookie: targeted replies go to that client only.
            if (trusted() && origin in AgentRuntime.ORIGINS) AgentHub.callbacks.register(callback, origin)
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
        if (intent?.action == ACTION_FOREGROUND) {
            // Mandatory after startForegroundService, even if the work already ended.
            setForeground(AgentHub.work.takeIf { it != 0 } ?: AgentHub.WORK_RUN)
            // F15: the work may have finished while this start was in flight; its "idle" found no
            // service to demote. Demote now instead of staying foreground until the next run.
            if (AgentHub.work == 0) setForeground(0)
        }
        return START_NOT_STICKY // a killed draft is abandoned, never silently resumed
    }

    /**
     * Android 15+: dataSync has a daily time budget. Drop that type now (the platform requires it
     * within seconds) and hold the queue; partial files stay and resume from the Models screen.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) {
            AkLog.w("dataSync time limit reached; pausing model transfers")
            setForeground(current and AgentHub.WORK_TRANSFER.inv())
            AgentHub.transfersTimedOut()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Drops background inference and unloads the resident model (SPEC §12.2).
        if (level >= TRIM_MEMORY_BACKGROUND) AgentHub.onTrimMemory()
    }

    override fun onDestroy() {
        if (AgentHub.foreground === fg) AgentHub.foreground = null
        super.onDestroy()
    }

    /** [AgentHub.WORK_RUN] / [AgentHub.WORK_TRANSFER] bits this service is currently foreground for. */
    private var current = 0
    private val fg: (Int) -> Unit = ::setForeground

    private fun setForeground(work: Int) {
        if (work == current) return
        if (work != 0) {
            try {
                val n = notification(this, work)
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, typesOf(work))
                else startForeground(NOTIFICATION_ID, n)
                current = work
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+): the work continues unprotected.
                AkLog.w("foreground not allowed now", e)
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf() // drops the "started" state only; stays alive while a client is bound
            current = 0
        }
    }

    private fun typesOf(work: Int): Int =
        (if (work and AgentHub.WORK_RUN != 0) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
            (if (work and AgentHub.WORK_TRANSFER != 0) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)

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

        private fun notification(context: Context, work: Int): Notification {
            val downloading = work == AgentHub.WORK_TRANSFER
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
                .setContentTitle(context.getString(if (downloading) R.string.agent_fgs_download_title else R.string.agent_fgs_title))
                .setContentText(context.getString(if (downloading) R.string.agent_fgs_download_text else R.string.agent_fgs_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
                .build()
        }
    }
}
