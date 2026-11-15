package io.github.masgzy.nekoport.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.masgzy.nekoport.MainActivity
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service keeping the HTTP / FTP servers alive while the app is
 * not in the foreground.
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = ServerManager.get(this)
        when (intent?.action) {
            ACTION_STOP -> {
                // Stop everything off the main thread: stopAll waits up to
                // several seconds per process (SIGTERM grace period).
                // userInitiated = the cron KEEP_ALIVE watchdog must not
                // restart everything a few seconds after this.
                scope.launch {
                    runCatching { manager.stopAll(userInitiated = true) }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                // A null intent means START_STICKY rebuilt this service after
                // a process death (the app-side start path always carries
                // ACTION_START). That path used to unconditionally startAll() -
                // it ignored userStoppedServices, so services the user had
                // deliberately stopped resurrected themselves after an LMK
                // kill. The check MUST run before startAll is queued: the
                // startAll coroutine clears the flag, so testing afterwards
                // would race and lose the suppression.
                if (intent == null && manager.userStoppedServices) {
                    AppLog.i("Service", "sticky restart suppressed: user stopped the services")
                    // startForeground first (restarting a foreground service
                    // still owes the system a notification), then go away.
                    startForegroundCompat()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                // The notification must appear immediately (main thread), but
                // the actual service startup is blocking work: launch it on
                // the service IO scope instead of freezing onStartCommand.
                startForegroundCompat()
                // (Re)start all configured services - startAll is idempotent.
                scope.launch { runCatching { manager.startAll() } }
                scope.launch {
                    runCatching { manager.refresh() }
                    notifyState(manager)
                }
                return START_STICKY
            }
        }
    }

    private fun startForegroundCompat() {
        val manager = ServerManager.get(this)
        // buildNotification formats user-visible strings; a bad format spec
        // must never take the app down with it.
        val notification = runCatching { buildNotification(manager) }.getOrElse {
            AppLog.w("Service", "notification build failed: ${it.message}")
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_running_title))
                .setOngoing(true)
                .setSilent(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (e: Exception) {
                // Some OEM skins reject the typed call even with the manifest
                // declaration; fall back to the untyped variant instead of
                // crashing (FTP / MySQL toggles call this on every start).
                AppLog.w("Service", "typed startForeground failed: ${e.message}")
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(manager: ServerManager): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ftpRunning = manager.state.value.ftpRunning
        val mariaRunning = manager.state.value.mariaRunning
        val runningSites = manager.state.value.sites.count { it.running }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(
                getString(
                    R.string.notification_running_text,
                    runningSites,
                    if (ftpRunning) getString(R.string.site_status_running).lowercase() else getString(R.string.site_status_stopped).lowercase(),
                    if (mariaRunning) getString(R.string.site_status_running).lowercase() else getString(R.string.site_status_stopped).lowercase(),
                )
            )
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.notification_stop_action), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyState(manager: ServerManager) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(manager))
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_server),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.github.masgzy.nekoport.action.STOP"
        /** App-side starts (switches, boot receiver) - distinguishes them from
         *  the null-intent START_STICKY rebuild, whose startAll() must respect
         *  userStoppedServices. */
        const val ACTION_START = "io.github.masgzy.nekoport.action.START"
        private const val CHANNEL_ID = "nekoport_server"
        private const val NOTIFICATION_ID = 42

        fun start(context: android.content.Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
