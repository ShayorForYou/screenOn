package com.screenon

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.screenon.KeepAwake.Mode
import com.screenon.KeepAwake.Result
import com.screenon.ui.ScreenOnScreen
import com.screenon.ui.theme.ScreenonTheme

class MainActivity : ComponentActivity() {

    /** Set when we sent the user to grant WRITE_SETTINGS; finished in onResume. */
    private var enableAfterGrant = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Both modes work either way; the status and reminder are just hidden if denied. The
            // mode to turn on was saved before asking, so it survives the activity being recreated.
            handle(KeepAwake.enable(this, KeepAwake.state.value.mode))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        KeepAwake.refresh(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val state by KeepAwake.state.collectAsState()
            ScreenonTheme {
                ScreenOnScreen(
                    state = state,
                    onToggle = ::toggle,
                    onModeChange = { handle(KeepAwake.setMode(this, it)) },
                    onDurationChange = { KeepAwake.setDuration(this, it) },
                    onBatteryGuardChange = { KeepAwake.setBatteryGuard(this, it) },
                    onGrant = ::requestWriteSettings,
                    onPreciseTimer = ::requestExactAlarms,
                    onExpire = { KeepAwake.refresh(this) },
                    onAddTile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ::addTile else null,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Picks up anything that changed while we were away (timeout edited in Settings, etc.).
        val s = KeepAwake.refresh(this)
        // Re-arms the timer, e.g. as an exact alarm once that access was just granted.
        KeepAwake.schedule(this)
        if (enableAfterGrant) {
            enableAfterGrant = false
            if (s.canWriteSettings && !s.active) enable(Mode.TIMEOUT)
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_ENABLE_SERVICE -> enable(Mode.SERVICE)
            ACTION_GRANT_AND_ENABLE -> {
                if (KeepAwake.canWriteSettings(this)) enable(Mode.TIMEOUT)
                else requestWriteSettings(enableAfter = true)
            }
        }
    }

    private fun toggle() {
        val s = KeepAwake.refresh(this)
        if (s.active) handle(KeepAwake.disable(this)) else enable(s.mode)
    }

    private fun enable(mode: Mode) {
        // Timeout mode first needs settings access; ask for that before anything else.
        if (mode == Mode.TIMEOUT && !KeepAwake.canWriteSettings(this)) {
            handle(KeepAwake.enable(this, mode))
            return
        }
        if (shouldAskForNotifications()) {
            KeepAwake.setMode(this, mode) // read back when the answer comes in
            getPreferences(MODE_PRIVATE).edit { putBoolean(K_ASKED_NOTIFICATIONS, true) }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        handle(KeepAwake.enable(this, mode))
    }

    /**
     * Asked once, the first time either mode is turned on: the status shows when the screen will
     * sleep again and the reminder catches a forgotten session. A "no" is respected; it can still
     * be changed in the system's app settings.
     */
    private fun shouldAskForNotifications(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean(K_ASKED_NOTIFICATIONS, false)

    private fun handle(result: Result) {
        when (result) {
            Result.OK -> Unit
            Result.NEEDS_PERMISSION -> requestWriteSettings(
                enableAfter = !KeepAwake.state.value.active
            )
            Result.NEEDS_FOREGROUND, Result.FAILED ->
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestWriteSettings() = requestWriteSettings(enableAfter = false)

    private fun requestWriteSettings(enableAfter: Boolean) {
        enableAfterGrant = enableAfter
        try {
            startActivity(KeepAwake.writeSettingsIntent(this))
        } catch (_: Exception) {
            enableAfterGrant = false
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(KeepAwake.exactAlarmIntent(this))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, ScreenOnTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_screen_on),
            mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            ) {
                KeepAwake.setTileAdded(this, true)
            }
        }
    }

    companion object {
        const val ACTION_ENABLE_SERVICE = "com.screenon.ENABLE_SERVICE"
        const val ACTION_GRANT_AND_ENABLE = "com.screenon.GRANT_AND_ENABLE"
        private const val K_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
