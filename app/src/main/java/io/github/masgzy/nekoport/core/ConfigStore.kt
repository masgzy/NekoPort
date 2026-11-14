package io.github.masgzy.nekoport.core

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists [GlobalConfig] as JSON inside the app private directory.
 */
class ConfigStore(context: Context) {

    private val file = File(context.filesDir, "config.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun load(): GlobalConfig = runCatching {
        if (!file.exists()) return GlobalConfig()
        json.decodeFromString<GlobalConfig>(file.readText())
    }.getOrElse {
        AppLog.e("ConfigStore", "config.json corrupted: ${it.message}, falling back to defaults")
        GlobalConfig()
    }

    @Synchronized
    fun save(config: GlobalConfig) {
        runCatching {
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(json.encodeToString(GlobalConfig.serializer(), config))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure {
            AppLog.e("ConfigStore", "save config failed: ${it.message}")
        }
    }
}
