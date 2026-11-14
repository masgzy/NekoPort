package io.github.masgzy.nekoport.core

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists [GlobalConfig] as JSON inside the app private directory.
 *
 * Corruption handling: a parse failure used to silently fall back to defaults
 * and the next save overwrote the file - one bad write (power loss, full disk)
 * could wipe every site/FTP/cron setting with no trace. Now the broken file
 * is preserved as `config.json.corrupt-<ts>`, the rolling `.bak` from the
 * last successful save is tried first, and every save is tmp + fsync +
 * atomic-rename with a backup of the previous version.
 */
class ConfigStore(context: Context) {

    private val file = File(context.filesDir, "config.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun load(): GlobalConfig {
        if (!file.exists()) return GlobalConfig()
        try {
            var config = json.decodeFromString<GlobalConfig>(file.readText())
            // One-shot mirror migration: installs before 0.1.0-alpha.3 defaulted
            // to the official Termux mirror; switch them to Tsinghua once, unless
            // the user already picked a non-default mirror themselves.
            if (!config.settings.mirrorMigrated &&
                config.settings.repoMirror == TermuxMirror.OFFICIAL.url
            ) {
                config = config.copy(
                    settings = config.settings.copy(
                        repoMirror = TermuxMirror.TUNA.url,
                        mirrorMigrated = true,
                    ),
                )
                save(config)
                AppLog.i("ConfigStore", "repo mirror migrated to Tsinghua")
            }
            return config
        } catch (e: Exception) {
            AppLog.e("ConfigStore", "config.json corrupted: ${e.message}")
            // Keep the broken file around for manual recovery instead of
            // letting the next save overwrite it silently.
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val kept = File(file.parentFile, "config.json.corrupt-$stamp")
                if (file.renameTo(kept)) {
                    AppLog.w("ConfigStore", "corrupt config preserved as ${kept.name}")
                }
            }
            // Roll back to the last known-good snapshot when available.
            val bak = File(file.parentFile, "config.json.bak")
            if (bak.exists()) {
                try {
                    val config = json.decodeFromString<GlobalConfig>(bak.readText())
                    AppLog.w("ConfigStore", "recovered config from config.json.bak")
                    save(config)
                    return config
                } catch (e2: Exception) {
                    AppLog.e("ConfigStore", "backup also unreadable: ${e2.message}")
                }
            }
            return GlobalConfig()
        }
    }

    @Synchronized
    fun save(config: GlobalConfig) {
        runCatching {
            // Rolling backup of the previous version (also written atomically
            // so a crash mid-copy cannot leave a half-written .bak).
            if (file.exists()) {
                val bak = File(file.parentFile, "config.json.bak")
                val bakTmp = File(file.parentFile, "config.json.bak.tmp")
                bakTmp.writeText(file.readText())
                if (!bakTmp.renameTo(bak)) bak.writeText(bakTmp.readText())
                bakTmp.delete()
            }
            val tmp = File(file.parentFile, "config.json.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(GlobalConfig.serializer(), config).toByteArray())
                out.fd.sync() // survive power loss: rename without fsync can land an empty file
            }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure {
            AppLog.e("ConfigStore", "save config failed: ${it.message}")
        }
    }
}
