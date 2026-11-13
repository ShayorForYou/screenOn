package com.screenon

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Date

/**
 * Single source of truth for the "keep screen on" feature, shared by the UI, the tile, the service
 * and the receivers.
 *
 * Persistent state lives in SharedPreferences so that it survives process death; the system itself
 * (screen-timeout setting / running service / boot count / battery) is always treated as the
 * authority and preferences are reconciled against it on every [refresh], which covers changes made
 * while the app wasn't running.
 *
 * A session ends by itself when its timer runs out, when the battery runs low (if enabled) or when
 * the phone restarts. A restart always counts as "off", so timeout mode never outlives a reboot.
 */
object KeepAwake {

    enum class Mode { TIMEOUT, SERVICE }

    enum class Result { OK, NEEDS_PERMISSION, NEEDS_FOREGROUND, FAILED }

    data class State(
        val active: Boolean,
        /** Active mode while on, otherwise the mode that will be used next. */
        val mode: Mode,
        /** Timeout (ms) that will be restored when timeout mode is turned off. */
        val restoreTimeout: Int?,
        val canWriteSettings: Boolean,
        val tileAdded: Boolean,
        /** Chosen session length in minutes; 0 means until turned off. */
        val duration: Int,
        /** Wall-clock start and end of the current session when it has a timer. */
        val startedAt: Long?,
        val endsAt: Long?,
        /** Turn off automatically when the battery drops to [BATTERY_THRESHOLD] (unless charging). */
        val batteryGuard: Boolean,
        /** Whether the timer can fire to the minute (exact alarms allowed). */
        val preciseTimer: Boolean,
    )

    /** Offered session lengths in minutes; 0 means until turned off. */
    val DURATIONS = listOf(0, 15, 30, 60, 120)

    /** Battery percentage at or below which the battery guard ends the session. */
    const val BATTERY_THRESHOLD = 15

    private const val PREFS = "keep_awake"
    private const val K_MODE = "mode"
    private const val K_ACTIVE = "active_mode"
    private const val K_ORIGINAL = "original_timeout"
    private const val K_APPLIED = "applied_timeout"
    private const val K_TILE = "tile_added"
    private const val K_DURATION = "duration_minutes"
    private const val K_GUARD = "battery_guard"
    private const val K_START = "session_start"
    private const val K_END = "session_end"
    private const val K_ARMED = "battery_armed"
    private const val K_BOOT = "session_boot"
    private const val K_SINCE = "session_since"
    private const val K_REMIND = "remind_at"

    /** Largest value the setting accepts; on most devices this means "never". */
    private const val MAX_TIMEOUT = Int.MAX_VALUE

    /** Only used if the real previous value is unknowable (e.g. app data was cleared while active). */
    private const val FALLBACK_TIMEOUT = 60_000

    /** Timeout mode has nothing running, so it looks at the battery this often while active. */
    private const val BATTERY_CHECK_MS = 5 * 60_000L

    /** Delivery window for inexact alarms (the system may widen it). */
    private const val ALARM_WINDOW_MS = 60_000L

    /**
     * How often a session without a timer reminds that it is still on. Longer than the longest
     * timer on offer, so it only speaks up once a session has clearly outlasted any planned use.
     */
    private const val REMINDER_MS = 2 * 60 * 60_000L

    /** Whether this process has brought the notifications in line with the state yet. */
    @Volatile
    private var notificationsSynced = false

    private val _state = MutableStateFlow(
        State(false, Mode.TIMEOUT, null, false, false, 0, null, null, true, true)
    )
    val state: StateFlow<State> = _state

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun canWriteSettings(c: Context): Boolean = Settings.System.canWrite(c)

    fun writeSettingsIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${c.packageName}".toUri())

    fun canScheduleExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    @RequiresApi(Build.VERSION_CODES.S)
    fun exactAlarmIntent(c: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${c.packageName}".toUri())

    /** Re-syncs persisted state with reality and publishes it. Cheap: a settings and battery read. */
    fun refresh(c: Context): State {
        reconcile(c)
        return publish(c)
    }

    fun toggle(c: Context): Result {
        val s = refresh(c)
        return if (s.active) disable(c) else enable(c, s.mode)
    }

    /** Changes the mode; if currently active, switches over to the new mode right away. */
    fun setMode(c: Context, mode: Mode): Result {
        val s = refresh(c)
        if (s.mode == mode) return Result.OK
        if (s.active) {
            val r = disable(c)
            if (r != Result.OK) return r
            return enable(c, mode)
        }
        prefs(c).edit { putString(K_MODE, mode.name) }
        publish(c)
        return Result.OK
    }

    /** Sets the session length. While active, the timer restarts from now with the new length. */
    fun setDuration(c: Context, minutes: Int) {
        val p = prefs(c)
        p.edit { putInt(K_DURATION, minutes) }
        val active = p.getString(K_ACTIVE, null)
        if (active != null) {
            p.edit(commit = true) { putTimer(minutes) }
            schedule(c)
            if (active == Mode.SERVICE.name) ScreenOnService.update(c)
        }
        publish(c)
    }

    fun setBatteryGuard(c: Context, on: Boolean) {
        val p = prefs(c)
        p.edit(commit = true) {
            putBoolean(K_GUARD, on)
            // Turned on while already low: only trip after the battery has been above the line.
            if (on) putBoolean(K_ARMED, battery(c)?.let { it.level > BATTERY_THRESHOLD } ?: true)
        }
        schedule(c)
        publish(c)
    }

    fun enable(c: Context, mode: Mode): Result {
        reconcile(c)
        val p = prefs(c)
        if (p.getString(K_ACTIVE, null) != null) return Result.OK.also { publish(c) }
        p.edit { putString(K_MODE, mode.name) }
        val result = when (mode) {
            Mode.TIMEOUT -> enableTimeout(c, p)
            Mode.SERVICE -> enableService(c, p)
        }
        if (result == Result.OK) schedule(c)
        publish(c)
        return result
    }

    fun disable(c: Context): Result {
        val result = endSession(c, prefs(c))
        publish(c)
        return result
    }

    fun setTileAdded(c: Context, added: Boolean) {
        prefs(c).edit { putBoolean(K_TILE, added) }
        publish(c, notifyTile = false)
    }

    /**
     * BOOT_COMPLETED arrived. Android 15+ also sends it when the app is first launched after a
     * force-stop, so a session is only ended when it really belongs to an earlier boot (reconcile
     * compares boot counts). Without a boot count to go on, a boot broadcast is taken at its word.
     */
    fun onBoot(c: Context) {
        reconcile(c)
        val p = prefs(c)
        if (p.getString(K_ACTIVE, null) != null && bootCount(c) == -1) endSession(c, p)
        schedule(c) // a force-stop cleared the alarm
        publish(c)
    }

    /** The alarm went off: end the session if it is due, else remind if that is due, then re-arm. */
    fun onAlarm(c: Context) {
        reconcile(c) // ends the session if its time is up or the battery is low
        val p = prefs(c)
        val remindAt = p.getLong(K_REMIND, 0L)
        val now = System.currentTimeMillis()
        if (p.getString(K_ACTIVE, null) != null && remindAt in 1L..now) {
            // Counted from now, so a phone left asleep overnight gets one reminder, not a backlog.
            p.edit { putLong(K_REMIND, now + REMINDER_MS) }
            Notifications.remind(c, p.getLong(K_SINCE, remindAt - REMINDER_MS))
        }
        publish(c)
        schedule(c)
    }

    /**
     * Arms (or cancels) the single alarm that ends the session on time, reminds about a session
     * without a timer and, in timeout mode, looks at the battery now and then. The alarm never wakes
     * the device: while the screen is off nothing needs to happen, and a pending alarm is delivered
     * as soon as it comes back on.
     */
    fun schedule(c: Context) {
        val p = prefs(c)
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(c)
        val active = p.getString(K_ACTIVE, null)
        val end = p.getLong(K_END, 0L).takeIf { active != null && it > 0L }
        val remind = p.getLong(K_REMIND, 0L).takeIf { active != null && it > 0L }
        // Wake lock mode watches the battery from its service; timeout mode has nothing running.
        val check = if (active == Mode.TIMEOUT.name && p.getBoolean(K_GUARD, true)) {
            System.currentTimeMillis() + BATTERY_CHECK_MS
        } else null
        val at = listOfNotNull(end, remind, check).minOrNull()
        if (at == null) {
            am.cancel(pi)
            return
        }
        try {
            if (at == end && canScheduleExact(c)) am.setExact(AlarmManager.RTC, at, pi)
            else am.setWindow(AlarmManager.RTC, at, ALARM_WINDOW_MS, pi)
        } catch (_: SecurityException) {
            // Exact alarms revoked between the check and the call.
            am.setWindow(AlarmManager.RTC, at, ALARM_WINDOW_MS, pi)
        }
    }

    internal fun isServiceModeActive(c: Context) =
        prefs(c).getString(K_ACTIVE, null) == Mode.SERVICE.name

    /** End of the current timed session (wall clock), if any. */
    internal fun endsAt(c: Context): Long? {
        val p = prefs(c)
        return p.getLong(K_END, 0L).takeIf { it > 0L && p.getString(K_ACTIVE, null) != null }
    }

    /** Called by the service if it gets destroyed while still marked active (e.g. stopped by the system). */
    internal fun onServiceGone(c: Context) {
        val p = prefs(c)
        if (p.getString(K_ACTIVE, null) == Mode.SERVICE.name) {
            clearActive(c, p)
            publish(c)
        }
    }

    // ---- timeout mode ------------------------------------------------------------------------

    private fun enableTimeout(c: Context, p: SharedPreferences): Result {
        if (!canWriteSettings(c)) return Result.NEEDS_PERMISSION
        val current = readTimeout(c) ?: return Result.FAILED
        // Persist the user's value *before* touching the setting, synchronously, so a crash or
        // process kill in between can never lose it.
        val original = if (current == MAX_TIMEOUT) FALLBACK_TIMEOUT else current
        p.edit(commit = true) {
            putString(K_ACTIVE, Mode.TIMEOUT.name)
            putInt(K_ORIGINAL, original)
            putInt(K_APPLIED, MAX_TIMEOUT)
            putSession(c, p)
        }
        if (!writeTimeout(c, MAX_TIMEOUT)) {
            clearActive(c, p)
            return Result.FAILED
        }
        // Some OEMs clamp or rewrite the value; remember what was actually stored so that
        // user-change detection compares against the real value.
        val applied = readTimeout(c)
        if (applied == null || (applied == current && current != MAX_TIMEOUT)) {
            clearActive(c, p)
            return Result.FAILED
        }
        p.edit(commit = true) { putInt(K_APPLIED, applied) }
        return Result.OK
    }

    private fun disableTimeout(c: Context, p: SharedPreferences): Result {
        val applied = p.getInt(K_APPLIED, MAX_TIMEOUT)
        val current = readTimeout(c)
        if (current != null && current != applied) {
            // The user picked a new timeout while we were active: respect it, don't overwrite.
            clearActive(c, p)
            return Result.OK
        }
        if (!canWriteSettings(c)) return Result.NEEDS_PERMISSION
        if (!writeTimeout(c, p.getInt(K_ORIGINAL, FALLBACK_TIMEOUT))) return Result.FAILED
        clearActive(c, p)
        return Result.OK
    }

    // ---- service mode ------------------------------------------------------------------------

    private fun enableService(c: Context, p: SharedPreferences): Result {
        // Written before starting so the service already sees the timer for its notification.
        p.edit(commit = true) {
            putString(K_ACTIVE, Mode.SERVICE.name)
            putSession(c, p)
        }
        return try {
            ContextCompat.startForegroundService(c, Intent(c, ScreenOnService::class.java))
            Result.OK
        } catch (_: IllegalStateException) {
            // Android 12+: not allowed to start a foreground service from the background.
            clearActive(c, p)
            Result.NEEDS_FOREGROUND
        } catch (_: SecurityException) {
            clearActive(c, p)
            Result.FAILED
        }
    }

    // ---- sessions ----------------------------------------------------------------------------

    private fun SharedPreferences.Editor.putSession(c: Context, p: SharedPreferences) {
        putInt(K_BOOT, bootCount(c))
        putBoolean(K_ARMED, battery(c)?.let { it.level > BATTERY_THRESHOLD } ?: true)
        putLong(K_SINCE, System.currentTimeMillis())
        putTimer(p.getInt(K_DURATION, 0))
    }

    /** A timer ends the session; without one, a reminder comes round every [REMINDER_MS]. */
    private fun SharedPreferences.Editor.putTimer(minutes: Int) {
        val now = System.currentTimeMillis()
        if (minutes > 0) {
            putLong(K_START, now)
            putLong(K_END, now + minutes * 60_000L)
            remove(K_REMIND)
        } else {
            remove(K_START)
            remove(K_END)
            putLong(K_REMIND, now + REMINDER_MS)
        }
    }

    /** Turns off whatever is active, without publishing. */
    private fun endSession(c: Context, p: SharedPreferences): Result =
        when (p.getString(K_ACTIVE, null)) {
            Mode.TIMEOUT.name -> disableTimeout(c, p)
            Mode.SERVICE.name -> {
                clearActive(c, p)
                c.stopService(Intent(c, ScreenOnService::class.java))
                Result.OK
            }
            else -> Result.OK
        }

    /** True when the active session should end by itself: restart, timer or low battery. */
    private fun shouldEnd(c: Context, p: SharedPreferences): Boolean {
        val boot = bootCount(c)
        val sessionBoot = p.getInt(K_BOOT, -1)
        if (boot != -1 && sessionBoot != -1 && boot != sessionBoot) return true

        val end = p.getLong(K_END, 0L)
        if (end > 0L && System.currentTimeMillis() >= end) return true

        if (p.getBoolean(K_GUARD, true)) {
            val b = battery(c) ?: return false
            if (b.level > BATTERY_THRESHOLD) {
                if (!p.getBoolean(K_ARMED, true)) p.edit { putBoolean(K_ARMED, true) }
            } else if (!b.plugged && p.getBoolean(K_ARMED, true)) {
                return true
            }
        }
        return false
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun reconcile(c: Context) {
        val p = prefs(c)
        when (p.getString(K_ACTIVE, null)) {
            Mode.TIMEOUT.name -> {
                val current = readTimeout(c)
                // Timeout changed by someone else: the feature is effectively off, and the user's
                // new value becomes the baseline — never restore the stale one over it.
                if (current != null && current != p.getInt(K_APPLIED, MAX_TIMEOUT)) clearActive(c, p)
            }
            Mode.SERVICE.name -> {
                // Process killed / force-stopped / rebooted: no wake lock is held anymore.
                if (!ScreenOnService.isRunning(c)) clearActive(c, p)
            }
            null -> {
                // Timeout is maxed but we have no record (data cleared or reinstalled while active).
                // Adopt it so the user can still turn it off from here.
                if (readTimeout(c) == MAX_TIMEOUT) {
                    p.edit(commit = true) {
                        putString(K_ACTIVE, Mode.TIMEOUT.name)
                        putString(K_MODE, Mode.TIMEOUT.name)
                        putInt(K_ORIGINAL, FALLBACK_TIMEOUT)
                        putInt(K_APPLIED, MAX_TIMEOUT)
                        putInt(K_BOOT, bootCount(c))
                    }
                }
            }
        }
        // Covers a restart even when the boot broadcast never arrives (some OEMs block it).
        if (p.getString(K_ACTIVE, null) != null && shouldEnd(c, p)) endSession(c, p)
        // A session without a timer that has no reminder yet (adopted above, or started by an
        // older version): count from now.
        if (p.getString(K_ACTIVE, null) != null && !p.contains(K_END) && !p.contains(K_REMIND)) {
            val now = System.currentTimeMillis()
            p.edit {
                if (!p.contains(K_SINCE)) putLong(K_SINCE, now)
                putLong(K_REMIND, now + REMINDER_MS)
            }
        }
    }

    private fun clearActive(c: Context, p: SharedPreferences) {
        p.edit(commit = true) {
            remove(K_ACTIVE)
            remove(K_ORIGINAL)
            remove(K_APPLIED)
            remove(K_START)
            remove(K_END)
            remove(K_ARMED)
            remove(K_BOOT)
            remove(K_SINCE)
            remove(K_REMIND)
        }
        c.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(c))
    }

    private fun publish(c: Context, notifyTile: Boolean = true): State {
        val p = prefs(c)
        val activeMode = p.getString(K_ACTIVE, null)?.let(::modeOf)
        val active = activeMode != null
        val new = State(
            active = active,
            mode = activeMode ?: modeOf(p.getString(K_MODE, null)),
            restoreTimeout = if (activeMode == Mode.TIMEOUT) p.getInt(K_ORIGINAL, FALLBACK_TIMEOUT) else null,
            canWriteSettings = canWriteSettings(c),
            tileAdded = p.getBoolean(K_TILE, false),
            duration = p.getInt(K_DURATION, 0),
            startedAt = p.getLong(K_START, 0L).takeIf { active && it > 0L },
            endsAt = p.getLong(K_END, 0L).takeIf { active && it > 0L },
            batteryGuard = p.getBoolean(K_GUARD, true),
            preciseTimer = canScheduleExact(c),
        )
        val old = _state.value
        _state.value = new
        val changed = old.active != new.active || old.mode != new.mode || old.endsAt != new.endsAt
        // Only on change (and once per process), so frequent refreshes never re-post anything.
        if (changed || !notificationsSynced) {
            notificationsSynced = true
            Notifications.sync(c, new)
        }
        if (notifyTile && changed) {
            try {
                TileService.requestListeningState(
                    c, ComponentName(c, ScreenOnTileService::class.java)
                )
            } catch (_: Exception) {
                // Tile not added / SystemUI unavailable: nothing to update.
            }
        }
        return new
    }

    private fun alarmIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 0, Intent(c, AutoOffReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun modeOf(name: String?) = Mode.entries.firstOrNull { it.name == name } ?: Mode.TIMEOUT

    private fun bootCount(c: Context): Int =
        Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private class Battery(val level: Int, val plugged: Boolean)

    /** Reads the sticky battery broadcast; no receiver is actually registered. */
    private fun battery(c: Context): Battery? {
        val i = try {
            c.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        } ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return Battery(level * 100 / scale, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    private fun readTimeout(c: Context): Int? = try {
        Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
    } catch (_: Settings.SettingNotFoundException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    private fun writeTimeout(c: Context, value: Int): Boolean = try {
        Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, value)
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Clock time in the user's 12/24-hour format, e.g. "3:45 PM". */
    fun formatClock(c: Context, millis: Long): String = DateFormat.getTimeFormat(c).format(Date(millis))

    fun formatTimeout(c: Context, ms: Int): String {
        val r = c.resources
        val totalSec = ms / 1000
        return when {
            totalSec < 60 -> r.getQuantityString(R.plurals.seconds, totalSec, totalSec)
            totalSec % 3600 == 0 -> (totalSec / 3600).let { r.getQuantityString(R.plurals.hours, it, it) }
            totalSec % 60 == 0 -> (totalSec / 60).let { r.getQuantityString(R.plurals.minutes, it, it) }
            else -> r.getQuantityString(R.plurals.minutes, totalSec / 60, totalSec / 60) + " " +
                r.getQuantityString(R.plurals.seconds, totalSec % 60, totalSec % 60)
        }
    }
}
