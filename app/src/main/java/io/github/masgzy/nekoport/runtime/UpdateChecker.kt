package io.github.masgzy.nekoport.runtime

import android.content.Context
import io.github.masgzy.nekoport.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Tracks which components have a newer build on the Termux mirror.
 *
 * The check runs automatically every time the settings page is entered
 * (throttled to one fetch per minute - the Packages index is a few MB) and
 * manually through the "检查更新" batch action. A component counts as
 * "updatable" when the index version differs from the recorded install
 * version; the settings UI then turns its update icon from grey to yellow.
 */
object UpdateChecker {

    data class State(
        val checking: Boolean = false,
        /** Latest published version per component; absent = no update (or unknown). */
        val available: Map<RuntimePackage, String> = emptyMap(),
        val lastCheckedAt: Long = 0L,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Re-checks are skipped within this window after a successful check. */
    private const val THROTTLE_MS = 60_000L

    /**
     * Called when the settings page appears. Fetches unless a fresh result is
     * already cached; [force] bypasses the throttle (manual "检查更新").
     */
    suspend fun check(context: Context, mirror: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val current = _state.value
        if (current.checking) return
        if (!force && current.error == null && now - current.lastCheckedAt < THROTTLE_MS) return
        _state.value = current.copy(checking = true, error = null)
        _state.value = try {
            val latest = withContext(Dispatchers.IO) {
                TermuxRuntime.fetchLatestVersions(mirror)
            }
            val installed = withContext(Dispatchers.IO) {
                RuntimePackage.ALL.mapNotNull { pkg ->
                    TermuxRuntime.readInstalledVersion(context, pkg)?.let { pkg to it }
                }.toMap()
            }
            val available = installed.mapNotNull { (pkg, recorded) ->
                latest[pkg.packageName]?.takeIf { it != recorded }?.let { pkg to it.substringAfter(':', it) }
            }.toMap()
            AppLog.i(
                "Update",
                "checked: ${available.size} of ${installed.size} components updatable" +
                    if (available.isEmpty()) "" else " (${available.keys.joinToString { it.label }})",
            )
            State(checking = false, available = available, lastCheckedAt = System.currentTimeMillis())
        } catch (e: Exception) {
            AppLog.e("Update", "update check failed: ${e.message}")
            State(
                checking = false,
                available = _state.value.available,
                lastCheckedAt = _state.value.lastCheckedAt,
                error = e.message ?: "network error",
            )
        }
    }
}
