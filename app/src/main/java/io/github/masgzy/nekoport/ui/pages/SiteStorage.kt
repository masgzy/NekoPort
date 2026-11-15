package io.github.masgzy.nekoport.ui.pages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File

/**
 * Storage access helper for custom site document roots outside the app
 * sandbox (e.g. `/sdcard/htdocs`).
 *
 * The app targets SDK 28 on purpose (same approach as Termux) and declares
 * `requestLegacyExternalStorage`, so classic `READ_EXTERNAL_STORAGE` runtime
 * permission grants full read/write access to shared storage on every
 * supported Android version - no MANAGE_EXTERNAL_SETTINGS detour needed.
 */
object SiteStorage {

    private fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /**
     * ACTUAL writability probe instead of a permission-bit check: on
     * Android 13+ READ_MEDIA_IMAGES grants media-file access only, which
     * says NOTHING about creating `/sdcard/<site>/` as a docroot. The old
     * chain - permission row shows "granted", mkdirs() then fails, site
     * serves nothing - was practically undiagnosable. Try the real thing:
     * create + delete a probe file in the shared-storage root.
     */
    fun canWriteSharedStorage(): Boolean = runCatching {
        val root = Environment.getExternalStorageDirectory()
        if (!root.isDirectory && !root.mkdirs()) return@runCatching false
        val probe = File(root, ".NekoPort-write-probe-${System.currentTimeMillis()}")
        val ok = probe.createNewFile()
        probe.delete()
        ok
    }.getOrDefault(false)

    fun hasStoragePermission(context: Context): Boolean = canWriteSharedStorage()

    /**
     * Requests the storage permission and returns whether shared storage is
     * REALLY writable afterwards (probe-verified). Must be called from the
     * main thread (it shows the system dialog).
     * Hard timeout: the launcher key survives process recreation poorly, and
     * a dialog the user never answers used to hang the calling coroutine
     * (and its UI progress state) forever.
     */
    suspend fun ensureStoragePermission(context: Context): Boolean {
        if (canWriteSharedStorage()) return true
        return try {
            val granted = kotlinx.coroutines.withTimeout(60_000) { requestPermission(context, permission()) }
            // The dialog may say "granted" while scoped storage still blocks
            // arbitrary paths; only the probe is authoritative.
            granted && canWriteSharedStorage()
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            false
        }
    }

    private suspend fun requestPermission(context: Context, permission: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val activity = ActivityHolder.activity
            if (activity == null) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
            val launcher = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            val registry = activity.activityResultRegistry
            val key = "nekoport-storage-permission-${System.nanoTime()}"
            val callback = object : androidx.activity.result.ActivityResultCallback<Boolean> {
                override fun onActivityResult(result: Boolean) {
                    if (cont.isActive) cont.resume(result)
                }
            }
            val disposable = registry.register(key, launcher, callback)
            cont.invokeOnCancellation { disposable.unregister() }
            disposable.launch(permission)
        }
}

/** Bridge so composables can reach the current Activity for permission requests. */
object ActivityHolder {
    @Volatile
    var activity: androidx.activity.ComponentActivity? = null
}
