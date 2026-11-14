package io.github.masgzy.nekoport.ui.pages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

    fun hasStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        // Android 10+: legacy external storage is only available when the
        // user granted READ_EXTERNAL_STORAGE at install/first-run time.
        return ContextCompat.checkSelfPermission(context, permission()) ==
            PackageManager.PERMISSION_GRANTED ||
            Environment.isExternalStorageLegacy()
    }

    /**
     * Requests the storage permission and returns whether it was granted.
     * Must be called from the main thread (it shows the system dialog).
     * Hard timeout: the launcher key survives process recreation poorly, and
     * a dialog the user never answers used to hang the calling coroutine
     * (and its UI progress state) forever.
     */
    suspend fun ensureStoragePermission(context: Context): Boolean {
        if (hasStoragePermission(context)) return true
        return try {
            kotlinx.coroutines.withTimeout(60_000) { requestPermission(context, permission()) }
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
