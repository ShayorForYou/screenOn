package com.screenon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * The status notification (both modes) and the "still on" reminder. Everything here is built on
 * demand and handed to the system; nothing is kept in memory and no countdown is driven from the
 * app — the system draws the chronometer itself.
 */
internal object Notifications {

    /** Wake lock mode: the foreground service's own notification. */
    const val ID_SERVICE = 1

    /**
     * Timeout mode: a plain notification, as nothing is running. Kept apart from [ID_SERVICE] so a
     * service that is still shutting down can't take it with it when switching modes.
     */
    private const val ID_TIMEOUT = 2
    private const val ID_REMINDER = 3

    private const val CHANNEL_STATUS = "keep_awake"
    private const val CHANNEL_REMINDER = "still_on"

    /** The session as it stands: shown in the status bar in both modes. */
    fun status(c: Context, endsAt: Long?): Notification {
        ensureChannels(c)
        return NotificationCompat.Builder(c, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_screen_on)
            .setContentTitle(c.getString(R.string.notif_title))
            .setContentText(
                if (endsAt != null) c.getString(R.string.notif_text_timed, KeepAwake.formatClock(c, endsAt))
                else c.getString(R.string.notif_text)
            )
            .setContentIntent(openApp(c))
            .addAction(0, c.getString(R.string.turn_off), turnOff(c))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .apply {
                // A live countdown in the header, drawn by the system: no updates from us.
                if (endsAt != null) {
                    setWhen(endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                } else {
                    setShowWhen(false)
                }
            }
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Brings the notifications in line with [s]. Wake lock mode's status belongs to its service,
     * so only timeout mode's is posted here. Without notification access the system drops the
     * posts, which is fine: the feature itself works the same.
     */
    fun sync(c: Context, s: KeepAwake.State) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        try {
            if (s.active && s.mode == KeepAwake.Mode.TIMEOUT) nm.notify(ID_TIMEOUT, status(c, s.endsAt))
            else nm.cancel(ID_TIMEOUT)
            if (!s.active || s.endsAt != null) nm.cancel(ID_REMINDER)
        } catch (_: SecurityException) {
            // Notification access withdrawn mid-call.
        }
    }

    /** A nudge that a session without a timer is still keeping the screen on. */
    fun remind(c: Context, since: Long) {
        ensureChannels(c)
        val hours = ((System.currentTimeMillis() - since) / 3_600_000L).toInt().coerceAtLeast(1)
        // A timeout-mode session can last days if the phone isn't restarted.
        val length = if (hours < 48) c.resources.getQuantityString(R.plurals.hours, hours, hours)
        else (hours / 24).let { c.resources.getQuantityString(R.plurals.days, it, it) }
        val n = NotificationCompat.Builder(c, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_screen_on)
            .setContentTitle(c.getString(R.string.reminder_title))
            .setContentText(c.getString(R.string.reminder_text, length))
            .setContentIntent(openApp(c))
            .addAction(0, c.getString(R.string.turn_off), turnOff(c))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java)?.notify(ID_REMINDER, n)
        } catch (_: SecurityException) {
            // No notification access: nothing to show.
        }
    }

    private fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Creating an existing channel is a no-op apart from refreshing its name and description.
        c.getSystemService(NotificationManager::class.java)?.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_STATUS, c.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) },
                NotificationChannel(
                    CHANNEL_REMINDER, c.getString(R.string.channel_reminder), NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = c.getString(R.string.channel_reminder_desc)
                    setShowBadge(false)
                },
            )
        )
    }

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openApp(c: Context): PendingIntent =
        PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), FLAGS)

    private fun turnOff(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 1, Intent(c, AutoOffReceiver::class.java).setAction(AutoOffReceiver.ACTION_TURN_OFF), FLAGS
    )
}
