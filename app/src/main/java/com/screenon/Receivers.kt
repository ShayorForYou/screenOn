package com.screenon

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The app's single alarm (timer end, timeout mode's battery look, "still on" reminder) and the
 * notifications' Turn off button.
 */
class AutoOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TURN_OFF) KeepAwake.disable(context)
        else KeepAwake.onAlarm(context)
    }

    companion object {
        const val ACTION_TURN_OFF = "com.screenon.TURN_OFF"
    }
}

/** System events that can leave the state stale: a restart, an app update, exact-alarm access. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // A real restart turns it off and gives your own timeout back right away.
            Intent.ACTION_BOOT_COMPLETED -> KeepAwake.onBoot(context)
            // Updates clear alarms (and stop the service); exact access changes how alarms are set.
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                KeepAwake.refresh(context)
                KeepAwake.schedule(context)
            }
        }
    }
}
