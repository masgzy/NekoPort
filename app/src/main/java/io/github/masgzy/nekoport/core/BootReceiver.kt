package io.github.masgzy.nekoport.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores services after boot when enabled in settings. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val manager = ServerManager.get(context)
        if (!manager.config.value.settings.startOnBoot) return
        ServerService.start(context)
    }
}
