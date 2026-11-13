package com.screenon

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
                Intent.ACTION_SCREEN_ON -> {
                    acquire()
                    // Catches a timer that ran out while the screen was off.
                    KeepAwake.refresh(context)
                }
            }
        }
    }

    /** Ends the session at low battery; the reading itself happens in [KeepAwake.refresh]. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (KeepAwake.state.value.batteryGuard) KeepAwake.refresh(context)
        }
    }

    // While the screen is on the CPU is awake, so a plain handler ends the session to the second
    // without needing exact-alarm access. (The alarm from KeepAwake.schedule is the backup.)
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { KeepAwake.refresh(this) }

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
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must always be called promptly after startForegroundService(), even if we stop right after.
        goForeground()
        if (!KeepAwake.isServiceModeActive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (getSystemService(PowerManager::class.java).isInteractive) acquire()
        handler.removeCallbacks(expire)
        KeepAwake.endsAt(this)?.let { end ->
            handler.postDelayed(expire, (end - System.currentTimeMillis()).coerceAtLeast(0L) + 250L)
        }
        // If the system kills us under memory pressure, come back and keep working.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(expire)
        release()
        unregisterReceiver(screenReceiver)
        unregisterReceiver(batteryReceiver)
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
        val notification = Notifications.status(this, KeepAwake.endsAt(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_SERVICE, notification)
        }
    }

    companion object {

        @Volatile
        private var running = false

        /** Rebuilds the notification and timer after the session length changed. */
        fun update(c: Context) {
            if (!running) return
            try {
                c.startService(Intent(c, ScreenOnService::class.java))
            } catch (_: IllegalStateException) {
                // Not allowed from the background; the old timer still ends via the alarm.
            }
        }

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
