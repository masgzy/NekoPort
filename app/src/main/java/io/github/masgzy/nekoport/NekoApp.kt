package io.github.masgzy.nekoport

import android.app.Application
import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.ServerManager

class NekoApp : Application() {

    lateinit var serverManager: ServerManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        serverManager = ServerManager.get(this)
        AppLog.i("NekoApp", "NekoPort started")
    }

    companion object {
        @Volatile
        private var instance: NekoApp? = null

        fun get(): NekoApp = instance!!
    }
}
