package com.localmind.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.localmind.MainActivity
import com.localmind.R

/**
 * Keeps the local API server alive while the screen is off so a desktop app or
 * another phone on the LAN can keep calling it.
 */
class ApiForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.api_service_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows the LAN address of the on-device LLM API"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ApiForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val port = getSharedPreferences("localmind", MODE_PRIVATE).getInt("api_port", 8080)

        return builder
            .setContentTitle(getString(R.string.api_service_title))
            .setContentText("http://<本机IP>:$port/v1/chat/completions")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, "停止", stop).build()
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "localmind_api"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "com.localmind.STOP_API"

        fun start(context: Context) {
            val intent = Intent(context, ApiForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ApiForegroundService::class.java))
        }
    }
}
