package io.github.masgzy.nekoport.ui

import io.github.masgzy.nekoport.ui.pages.EditorTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny navigation bus for full-screen overlays (config editor / log viewer /
 * app log). Pages request an overlay from anywhere; [AppRoot] renders them
 * on top of the pager.
 *
 * The editor close path goes through [requestCloseEditor] so unsaved changes
 * can interrupt the close (system back, close button) with a confirmation.
 */
object OverlayBus {

    private val _editor = MutableStateFlow<EditorTarget?>(null)
    val editor: StateFlow<EditorTarget?> = _editor

    private val _appLogs = MutableStateFlow(false)
    val appLogs: StateFlow<Boolean> = _appLogs

    /** One-shot flag: the user asked to close while unsaved edits may exist. */
    private val _pendingClose = MutableStateFlow(false)
    val pendingClose: StateFlow<Boolean> = _pendingClose

    fun openEditor(target: EditorTarget) {
        _pendingClose.value = false
        _editor.value = target
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
