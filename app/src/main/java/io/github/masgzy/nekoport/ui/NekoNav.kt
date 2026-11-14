package io.github.masgzy.nekoport.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Lightweight cross-tab navigation bus: pages can request a tab switch, ask
 * the Sites tab to open a site editor, point the Files tab at a directory,
 * or ask the Manage tab to scroll to a component panel.
 */
object NekoNav {

    private val _tabRequest = MutableStateFlow<Int?>(null)
    val tabRequest: StateFlow<Int?> = _tabRequest

    private val _editSiteRequest = MutableStateFlow<Long?>(null)
    val editSiteRequest: StateFlow<Long?> = _editSiteRequest

    private val _filesTargetDir = MutableStateFlow<File?>(null)
    val filesTargetDir: StateFlow<File?> = _filesTargetDir

    private val _manageScrollTarget = MutableStateFlow<Int?>(null)
    val manageScrollTarget: StateFlow<Int?> = _manageScrollTarget

    fun goToTab(index: Int) {
        _tabRequest.value = index
    }

    fun consumeTabRequest() {
        _tabRequest.value = null
    }

    fun editSite(siteId: Long) {
        _editSiteRequest.value = siteId
    }

    fun consumeEditSiteRequest() {
        _editSiteRequest.value = null
    }

    fun openFiles(dir: File?) {
        _filesTargetDir.value = dir
        goToTab(AppRoot.tabIndexOf(NekoTab.FILES))
    }

    fun consumeFilesTargetDir() {
        _filesTargetDir.value = null
    }

    fun scrollToManagePanel(itemIndex: Int) {
        _manageScrollTarget.value = itemIndex
        goToTab(AppRoot.tabIndexOf(NekoTab.MANAGE))
    }

    fun consumeManageScrollTarget() {
        _manageScrollTarget.value = null
    }
}
