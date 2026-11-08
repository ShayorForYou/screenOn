package com.screenon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.net.toUri
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the "keep screen on" feature, shared by the UI, the tile and the service.
 *
 * Persistent state lives in SharedPreferences so that it survives process death; the system itself
 * (screen-timeout setting / running service) is always treated as the authority and preferences are
 * reconciled against it on every [refresh], which covers changes made while the app wasn't running.
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
    )

    private const val PREFS = "keep_awake"
    private const val K_MODE = "mode"
    private const val K_ACTIVE = "active_mode"
    private const val K_ORIGINAL = "original_timeout"
    private const val K_APPLIED = "applied_timeout"
    private const val K_TILE = "tile_added"

    /** Largest value the setting accepts; on most devices this means "never". */
    private const val MAX_TIMEOUT = Int.MAX_VALUE

    /** Only used if the real previous value is unknowable (e.g. app data was cleared while active). */
    private const val FALLBACK_TIMEOUT = 60_000

    private val _state = MutableStateFlow(State(false, Mode.TIMEOUT, null, false, false))
    val state: StateFlow<State> = _state

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun canWriteSettings(c: Context): Boolean = Settings.System.canWrite(c)

    fun writeSettingsIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${c.packageName}".toUri())

    /** Re-syncs persisted state with reality and publishes it. Cheap: one settings read at most. */
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

    fun enable(c: Context, mode: Mode): Result {
        reconcile(c)
        val p = prefs(c)
        if (p.getString(K_ACTIVE, null) != null) return Result.OK.also { publish(c) }
        p.edit { putString(K_MODE, mode.name) }
        val result = when (mode) {
            Mode.TIMEOUT -> enableTimeout(c, p)
            Mode.SERVICE -> enableService(c, p)
        }
        publish(c)
        return result
    }

    fun disable(c: Context): Result {
        val p = prefs(c)
        val result = when (p.getString(K_ACTIVE, null)) {
            Mode.TIMEOUT.name -> disableTimeout(c, p)
            Mode.SERVICE.name -> {
                clearActive(p)
                c.stopService(Intent(c, ScreenOnService::class.java))
                Result.OK
            }
            else -> Result.OK
        }
        publish(c)
        return result
    }

    fun setTileAdded(c: Context, added: Boolean) {
        prefs(c).edit { putBoolean(K_TILE, added) }
        publish(c, notifyTile = false)
    }

    internal fun isServiceModeActive(c: Context) =
        prefs(c).getString(K_ACTIVE, null) == Mode.SERVICE.name

    /** Called by the service if it gets destroyed while still marked active (e.g. stopped by the system). */
    internal fun onServiceGone(c: Context) {
        val p = prefs(c)
        if (p.getString(K_ACTIVE, null) == Mode.SERVICE.name) {
            clearActive(p)
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
        }
        if (!writeTimeout(c, MAX_TIMEOUT)) {
            clearActive(p)
            return Result.FAILED
        }
        // Some OEMs clamp or rewrite the value; remember what was actually stored so that
        // user-change detection compares against the real value.
        val applied = readTimeout(c)
        if (applied == null || (applied == current && current != MAX_TIMEOUT)) {
            clearActive(p)
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
            clearActive(p)
            return Result.OK
        }
        if (!canWriteSettings(c)) return Result.NEEDS_PERMISSION
        if (!writeTimeout(c, p.getInt(K_ORIGINAL, FALLBACK_TIMEOUT))) return Result.FAILED
        clearActive(p)
        return Result.OK
    }

    // ---- service mode ------------------------------------------------------------------------

    private fun enableService(c: Context, p: SharedPreferences): Result {
        p.edit(commit = true) { putString(K_ACTIVE, Mode.SERVICE.name) }
        return try {
            ContextCompat.startForegroundService(c, Intent(c, ScreenOnService::class.java))
            Result.OK
        } catch (_: IllegalStateException) {
            // Android 12+: not allowed to start a foreground service from the background.
            clearActive(p)
            Result.NEEDS_FOREGROUND
        } catch (_: SecurityException) {
            clearActive(p)
            Result.FAILED
        }
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun reconcile(c: Context) {
        val p = prefs(c)
        when (p.getString(K_ACTIVE, null)) {
            Mode.TIMEOUT.name -> {
                val current = readTimeout(c)
                // Timeout changed by someone else: the feature is effectively off, and the user's
                // new value becomes the baseline — never restore the stale one over it.
                if (current != null && current != p.getInt(K_APPLIED, MAX_TIMEOUT)) clearActive(p)
            }
            Mode.SERVICE.name -> {
                // Process killed / force-stopped / rebooted: no wake lock is held anymore.
                if (!ScreenOnService.isRunning(c)) clearActive(p)
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
                    }
                }
            }
        }
    }

    private fun clearActive(p: SharedPreferences) = p.edit(commit = true) {
        remove(K_ACTIVE)
        remove(K_ORIGINAL)
        remove(K_APPLIED)
    }

    private fun publish(c: Context, notifyTile: Boolean = true): State {
        val p = prefs(c)
        val activeMode = p.getString(K_ACTIVE, null)?.let(::modeOf)
        val new = State(
            active = activeMode != null,
            mode = activeMode ?: modeOf(p.getString(K_MODE, null)),
            restoreTimeout = if (activeMode == Mode.TIMEOUT) p.getInt(K_ORIGINAL, FALLBACK_TIMEOUT) else null,
            canWriteSettings = canWriteSettings(c),
            tileAdded = p.getBoolean(K_TILE, false),
        )
        val old = _state.value
        _state.value = new
        if (notifyTile && (old.active != new.active || old.mode != new.mode)) {
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

    private fun modeOf(name: String?) = Mode.entries.firstOrNull { it.name == name } ?: Mode.TIMEOUT

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
