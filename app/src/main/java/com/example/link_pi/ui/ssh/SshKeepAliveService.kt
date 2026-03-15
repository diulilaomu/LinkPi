package com.example.link_pi.ui.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.link_pi.MainActivity
import com.example.link_pi.R

/**
 * Foreground service that keeps the process alive while SSH sessions or
 * MiniApps are active. Uses reference counting — starts on first client,
 * stops when all clients are removed.
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keepalive_channel"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_ADD = "add"
        private const val ACTION_REMOVE = "remove"
        private const val EXTRA_CLIENT_ID = "client_id"
        private const val EXTRA_LABEL = "label"

        // In-memory client set — static so we can read it from onStartCommand
        private val clients = mutableMapOf<String, String>() // id → label

        fun addClient(context: Context, clientId: String, label: String) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_ADD
                putExtra(EXTRA_CLIENT_ID, clientId)
                putExtra(EXTRA_LABEL, label)
            }
            context.startForegroundService(intent)
        }

        fun removeClient(context: Context, clientId: String) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_REMOVE
                putExtra(EXTRA_CLIENT_ID, clientId)
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val clientId = intent?.getStringExtra(EXTRA_CLIENT_ID)
        when (intent?.action) {
            ACTION_ADD -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
                if (clientId != null) clients[clientId] = label
            }
            ACTION_REMOVE -> {
                if (clientId != null) clients.remove(clientId)
                if (clients.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        clients.clear()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "后台保活",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "SSH 或应用运行中" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val labels = clients.values.toList()
        val text = when {
            labels.isEmpty() -> "运行中"
            labels.size == 1 -> labels.first()
            else -> labels.joinToString("、")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LinkPi 运行中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinkPi:KeepAlive").apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
