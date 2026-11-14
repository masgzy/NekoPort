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
                scope.launch {
                    runCatching { manager.stopAll() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
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
        val notification = buildNotification(manager)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
        val runningSites = manager.state.value.sites.count { it.running }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(
                getString(
                    R.string.notification_running_text,
                    runningSites,
                    if (ftpRunning) getString(R.string.site_status_running).lowercase() else getString(R.string.site_status_stopped).lowercase(),
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
        private const val CHANNEL_ID = "nekoport_server"
        private const val NOTIFICATION_ID = 42

        fun start(context: android.content.Context) {
            val intent = Intent(context, ServerService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
