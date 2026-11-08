package com.screenon

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile. Only bound while the shade is open; no work happens otherwise. */
class ScreenOnTileService : TileService() {

    override fun onTileAdded() = KeepAwake.setTileAdded(this, true)

    override fun onTileRemoved() = KeepAwake.setTileAdded(this, false)

    override fun onStartListening() = render(KeepAwake.refresh(this))

    override fun onClick() {
        when (KeepAwake.toggle(this)) {
            KeepAwake.Result.NEEDS_PERMISSION ->
                openApp(MainActivity.ACTION_GRANT_AND_ENABLE)
            KeepAwake.Result.NEEDS_FOREGROUND ->
                openApp(MainActivity.ACTION_ENABLE_SERVICE)
            else -> Unit
        }
        render(KeepAwake.state.value)
    }

    private fun render(s: KeepAwake.State) {
        val tile = qsTile ?: return
        tile.state = if (s.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (s.mode == KeepAwake.Mode.TIMEOUT) R.string.mode_timeout else R.string.mode_service
            )
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(action: String) {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launch = Runnable {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(launch) else launch.run()
    }
}
