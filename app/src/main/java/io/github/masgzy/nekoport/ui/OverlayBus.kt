package io.github.masgzy.nekoport.ui

import io.github.masgzy.nekoport.ui.pages.EditorTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny navigation bus for full-screen overlays (config editor / log viewer /
 * app log). Pages request an overlay from anywhere; [AppRoot] renders them
 * on top of the pager.
 *
 * The editor is a multi-tab session (interaction model follows Acode):
 * [openEditor] appends the file as a tab (or focuses it when already open).
 * The editor close path goes through [requestCloseEditor] so unsaved changes
 * can interrupt the close (system back, close button) with a confirmation.
 */
object OverlayBus {

    data class EditorSession(
        val targets: List<EditorTarget>,
        val activeIndex: Int,
    )

    private val _editor = MutableStateFlow<EditorSession?>(null)
    val editor: StateFlow<EditorSession?> = _editor

    private val _appLogs = MutableStateFlow(false)
    val appLogs: StateFlow<Boolean> = _appLogs

    /** One-shot flag: the user asked to close while unsaved edits may exist. */
    private val _pendingClose = MutableStateFlow(false)
    val pendingClose: StateFlow<Boolean> = _pendingClose

    fun openEditor(target: EditorTarget) {
        _pendingClose.value = false
        val session = _editor.value
        _editor.value = if (session == null) {
            EditorSession(listOf(target), 0)
        } else {
            val existing = session.targets.indexOfFirst { it.id == target.id }
            if (existing >= 0) {
                session.copy(activeIndex = existing)
            } else {
                EditorSession(session.targets + target, session.targets.size)
            }
        }
    }

    fun setActiveTab(index: Int) {
        val session = _editor.value ?: return
        if (index in session.targets.indices) {
            _editor.value = session.copy(activeIndex = index)
        }
    }

    /** Removes a tab; the editor overlay closes when the last tab goes away. */
    fun closeTab(index: Int) {
        val session = _editor.value ?: return
        val remaining = session.targets.toMutableList().apply { removeAt(index) }
        if (remaining.isEmpty()) {
            closeEditor()
        } else {
            val active = (if (index < session.activeIndex) session.activeIndex - 1 else session.activeIndex)
                .coerceIn(0, remaining.size - 1)
            _editor.value = EditorSession(remaining, active)
        }
    }

    fun closeEditor() {
        _pendingClose.value = false
        _editor.value = null
    }

    /** Close request (back button / close icon): the editor decides what to do. */
    fun requestCloseEditor() {
        _pendingClose.value = true
    }

    fun clearPendingClose() {
        _pendingClose.value = false
    }

    fun showAppLogs() {
        _appLogs.value = true
    }

    fun dismissAppLogs() {
        _appLogs.value = false
    }
}
