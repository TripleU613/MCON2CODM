package com.tripleu.mcon2codm

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
import androidx.core.app.NotificationCompat

class BridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            // BridgeRunner.stop calls the shell (network IO) — off main thread.
            Thread {
                BridgeRunner.stop(applicationContext)
            }.apply { isDaemon = true }.start()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()

        val devicePath = intent?.getStringExtra(EXTRA_DEVICE_PATH)
        if (devicePath.isNullOrBlank()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // BridgeRunner.start opens an ADB shell over TLS → MUST run off main.
        Thread {
            BridgeRunner.start(applicationContext, devicePath)
        }.apply { isDaemon = true }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Thread {
            BridgeRunner.stop(applicationContext)
        }.apply { isDaemon = true }.start()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Bridge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "MCON → Xbox bridge is active" }
            nm.createNotificationChannel(ch)
        }
        val stopIntent = Intent(this, BridgeService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Xbox spoof active")
            .setContentText("Controller events bridged as Xbox Wireless Controller")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(0, "Stop", stopPi)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        const val CHANNEL_ID = "bridge"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.tripleu.mcon2codm.STOP"
        const val EXTRA_DEVICE_PATH = "device_path"

        fun start(ctx: Context, devicePath: String) {
            val i = Intent(ctx, BridgeService::class.java)
                .putExtra(EXTRA_DEVICE_PATH, devicePath)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }
}
