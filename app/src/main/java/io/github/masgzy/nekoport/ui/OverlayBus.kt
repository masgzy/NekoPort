package io.github.masgzy.nekoport.ui

import io.github.masgzy.nekoport.ui.pages.EditorTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny navigation bus for full-screen overlays (config editor / log viewer /
 * app log). Pages request an overlay from anywhere; [AppRoot] renders them
 * on top of the pager.
 */
object OverlayBus {

    private val _editor = MutableStateFlow<EditorTarget?>(null)
    val editor: StateFlow<EditorTarget?> = _editor

    private val _appLogs = MutableStateFlow(false)
    val appLogs: StateFlow<Boolean> = _appLogs

    fun openEditor(target: EditorTarget) {
        _editor.value = target
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun showAppLogs() {
        _appLogs.value = true
    }

    fun dismissAppLogs() {
        _appLogs.value = false
    }
}
