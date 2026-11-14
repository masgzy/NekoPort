package io.github.masgzy.nekoport.runtime

import android.content.Context
import io.github.masgzy.nekoport.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * Debian-ish version comparison. String inequality used to flag ANY
     * difference as an update, so a mirror rollback (or an epoch mismatch
     * like `1:2.4.71` vs `2.4.71`) offered "downgrades" as updates.
     */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        val (candidateEpoch, candidateRest) = splitEpoch(candidate.trim())
        val (installedEpoch, installedRest) = splitEpoch(installed.trim())
        if (candidateEpoch != installedEpoch) return candidateEpoch > installedEpoch
        val a = numericTokens(candidateRest)
        val b = numericTokens(installedRest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }

    /** Splits `1:2.4.71` into (1, "2.4.71"); a missing/invalid epoch means 0. */
    private fun splitEpoch(v: String): Pair<Long, String> {
        val idx = v.indexOf(':')
        if (idx > 0) {
            val epoch = v.substring(0, idx).toLongOrNull()
            if (epoch != null) return epoch to v.substring(idx + 1)
        }
        return 0L to v
    }

    /** Leading numeric value of every dot/dash/plus/tilde segment; missing segments count as 0. */
    private fun numericTokens(v: String): List<Long> =
        v.split('.', '-', '+', '~').map { part -> part.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L }

    /** Serializes concurrent checks (settings page entry + manual button). */
    private val checkMutex = Mutex()

    /**
     * Called when the settings page appears. Fetches unless a fresh result is
     * already cached; [force] bypasses the throttle (manual "检查更新").
     */
    suspend fun check(context: Context, mirror: String, force: Boolean = false) = checkMutex.withLock {
        val now = System.currentTimeMillis()
        val current = _state.value
        // Re-read under the lock: a parallel call may already have flipped the
        // flags between the caller's decision and the mutex grant.
        if (current.checking) return@withLock
        if (!force && current.error == null && now - current.lastCheckedAt < THROTTLE_MS) return@withLock
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
                latest[pkg.packageName]?.takeIf { isNewer(it, recorded) }?.let { pkg to it.substringAfter(':', it) }
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
