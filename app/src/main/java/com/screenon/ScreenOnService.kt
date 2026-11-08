package com.screenon

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Service mode: holds a screen wake lock while the screen is on. The lock is released as soon as
 * the screen goes off (power button) and re-acquired when it comes back, so nothing is held — and
 * no CPU is used — while the device is asleep. No polling, no timers.
 */
class ScreenOnService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> release()
                Intent.ACTION_SCREEN_ON -> acquire()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            KeepAwake.disable(this) // clears state and stops this service
            return START_NOT_STICKY
        }
        // Must always be called promptly after startForegroundService(), even if we stop right after.
        goForeground()
        if (!KeepAwake.isServiceModeActive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (getSystemService(PowerManager::class.java).isInteractive) acquire()
        // If the system kills us under memory pressure, come back and keep working.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        release()
        unregisterReceiver(screenReceiver)
        KeepAwake.onServiceGone(this)
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout") // Intentionally indefinite; released on screen-off / stop.
    private fun acquire() {
        val wl = wakeLock ?: run {
            @Suppress("DEPRECATION") // Still the only way to hold the screen on without a window.
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
            getSystemService(PowerManager::class.java)
                .newWakeLock(flags, "screenon:keep")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
        }
        if (!wl.isHeld) wl.acquire()
    }

    private fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), immutable
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenOnService::class.java).setAction(ACTION_STOP), immutable
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_screen_on)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.turn_off), stop)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "keep_awake"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.screenon.STOP"

        @Volatile
        private var running = false

        /** True if alive in this process, or known to the system (incl. pending sticky restart). */
        fun isRunning(c: Context): Boolean {
            if (running) return true
            @Suppress("DEPRECATION") // Still returns the caller's own services.
            return c.getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == ScreenOnService::class.java.name }
        }
    }
}
