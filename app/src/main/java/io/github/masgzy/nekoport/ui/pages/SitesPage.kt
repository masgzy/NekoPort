package io.github.masgzy.nekoport.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.Engine
import io.github.masgzy.nekoport.core.SiteConfig
import io.github.masgzy.nekoport.core.SiteRuntime
import io.github.masgzy.nekoport.runtime.ConfigWriter
import io.github.masgzy.nekoport.ui.AppRoot
import io.github.masgzy.nekoport.ui.NekoNav
import io.github.masgzy.nekoport.ui.OverlayBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import io.github.masgzy.nekoport.ui.component.NekoTextButton
import io.github.masgzy.nekoport.ui.component.animation.pressFeedback
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * Sites tab: the list of HTTP sites plus the new-site flow. The component
 * status card, the PHP card and the run-all switch live on the dashboard /
 * manage tabs; config & log entries of a site are hidden behind "more".
 */
@Composable
fun SitesPage(contentPadding: PaddingValues) {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val colorScheme = MiuixTheme.colorScheme
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingSite by remember { mutableStateOf<SiteConfig?>(null) }

    // Dashboard "edit" buttons land here and open the editor dialog directly.
    val pendingEdit by NekoNav.editSiteRequest.collectAsState()
    LaunchedEffect(pendingEdit) {
        val id = pendingEdit ?: return@LaunchedEffect
        val site = manager.config.value.sites.firstOrNull { it.id == id }
        if (site != null) editingSite = site
        NekoNav.consumeEditSiteRequest()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.sites.isEmpty()) {
                item {
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.sites_empty),
                                color = colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.main,
                            )
                            NekoTextButton(
                                text = stringResource(R.string.sites_add),
                                onClick = { showEditor = true },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            } else {
                items(state.sites, key = { it.site.id }) { runtime ->
                    SiteCard(
                        runtime = runtime,
                        onEdit = { editingSite = runtime.site },
                    )
                }
            }
        }

        // Floating "add site" button, always above the glass bottom bar.
        FloatingActionButton(
            onClick = { showEditor = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .pressFeedback(cornerRadius = 28.dp)
                .padding(
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    end = 16.dp,
                )
                .size(56.dp),
        ) {
            Icon(
                imageVector = MiuixIcons.Add,
                contentDescription = stringResource(R.string.sites_add),
                tint = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.size(26.dp),
            )
        }
    }

    if (showEditor) {
        SiteEditorDialog(
            site = null,
            onDismiss = { showEditor = false },
        )
    }
    editingSite?.let { site ->
        SiteEditorDialog(
            site = site,
            onDismiss = { editingSite = null },
        )
    }
}

@Composable
private fun SiteCard(runtime: SiteRuntime, onEdit: () -> Unit) {
    val manager = NekoApp.get().serverManager
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val colorScheme = MiuixTheme.colorScheme
    var showMore by rememberSaveable(runtime.site.id) { mutableStateOf(false) }
    Card {
        SwitchPreference(
            title = "${runtime.site.name} :${runtime.site.port}",
            summary = buildString {
                append(runtime.site.engine.displayName)
                if (runtime.site.enablePhp) append(" + PHP")
                append(" · ")
                append(
                    if (runtime.running) {
                        stringResource(R.string.site_status_running)
                    } else {
                        runtime.statusText ?: stringResource(R.string.site_status_stopped)
                    },
                )
            },
            checked = runtime.running,
            onCheckedChange = { on ->
                if (on) manager.startSiteAsync(runtime.site) else manager.stopSiteAsync(runtime.site.id)
            },
        )
        runtime.statusText?.let {
            Text(
                text = it,
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        ArrowPreference(
            title = stringResource(R.string.site_edit),
            onClick = onEdit,
        )
        // Config/log entries stay hidden behind "more" by default.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            NekoTextButton(
                text = if (showMore) stringResource(R.string.dashboard_less) else stringResource(R.string.dashboard_more),
                onClick = { showMore = !showMore },
            )
        }
        if (showMore) {
            ArrowPreference(
                title = stringResource(R.string.site_edit_conf),
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val auto = ConfigWriter.writeEngineConf(context, runtime.site, manager.siteDocRoot(runtime.site))
                        val file = ConfigWriter.ensureUserCopy(auto)
                        OverlayBus.openEditor(
                            EditorTarget(
                                title = "${runtime.site.name} · ${runtime.site.engine.displayName}",
                                file = file,
                                resetAction = { ConfigWriter.resetToAuto(auto) },
                            ),
                        )
                    }
                },
            )
            ArrowPreference(
                title = stringResource(R.string.site_open_log),
                onClick = {
                    OverlayBus.openEditor(
                        EditorTarget(
                            title = "site-${runtime.site.id}-error.log",
                            file = ConfigWriter.siteErrorLog(context, runtime.site.id, runtime.site.engine),
                            readOnly = true,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun SiteEditorDialog(site: SiteConfig?, onDismiss: () -> Unit) {
    val manager = NekoApp.get().serverManager
    val config by manager.config.collectAsState()
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val colorScheme = MiuixTheme.colorScheme
    val isNew = site == null

    var name by remember { mutableStateOf(TextFieldValue(site?.name ?: "")) }
    var port by remember { mutableStateOf(TextFieldValue(site?.port?.toString() ?: "8080")) }
    var engineIndex by remember {
        mutableStateOf(Engine.entries.indexOfFirst { it == (site?.engine ?: Engine.LIGHTTPD) }.coerceAtLeast(0))
    }
    var enabled by remember { mutableStateOf(site?.enabled ?: true) }
    var enablePhp by remember { mutableStateOf(site?.enablePhp ?: false) }
    var dirListing by remember { mutableStateOf(site?.directoryListing ?: true) }
    var indexFiles by remember {
        mutableStateOf(TextFieldValue(site?.indexFiles?.joinToString(",") ?: "index.html,index.htm,index.php"))
    }
    var phpDenyDirs by remember {
        mutableStateOf(TextFieldValue(site?.phpDenyDirs?.joinToString(",") ?: "uploads,upload,static"))
    }
    var phpExtensions by remember {
        mutableStateOf(TextFieldValue(site?.phpExtensions?.joinToString(",") ?: "php"))
    }
    var docRoot by remember { mutableStateOf(TextFieldValue(site?.docRoot ?: "")) }
    var error by remember { mutableStateOf<String?>(null) }

    fun showError(res: Int) {
        error = context.getString(res)
    }

    OverlayDialog(
        show = true,
        title = if (isNew) stringResource(R.string.sites_add) else stringResource(R.string.site_edit),
        summary = if (!isNew) {
            stringResource(R.string.site_docroot) + ": " +
                (site!!.docRoot.ifBlank { "sites/${site.name}" })
        } else null,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isNew) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.site_name),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextField(
                value = port,
                onValueChange = { port = it },
                label = stringResource(R.string.site_port),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OverlaySpinnerPreference(
                items = Engine.entries.map { DropdownItem(text = it.displayName, onClick = {}) },
                selectedIndex = engineIndex,
                title = stringResource(R.string.site_engine),
                onSelectedIndexChange = { engineIndex = it },
            )
            TextField(
                value = docRoot,
                onValueChange = { docRoot = it },
                label = stringResource(R.string.site_docroot),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NekoTextButton(
                    text = stringResource(R.string.site_docroot_default),
                    onClick = { docRoot = TextFieldValue("") },
                    modifier = Modifier.weight(1f),
                )
                NekoTextButton(
                    text = stringResource(R.string.site_docroot_sdcard),
                    onClick = {
                        scope.launch {
                            val granted = SiteStorage.ensureStoragePermission(context)
                            if (granted) docRoot = TextFieldValue("/sdcard/" + (name.text.trim().ifEmpty { "htdocs" }))
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.site_docroot_hint),
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
            SwitchPreference(
                title = stringResource(R.string.site_enabled),
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
            SwitchPreference(
                title = stringResource(R.string.site_enable_php),
                summary = stringResource(R.string.site_enable_php_summary),
                checked = enablePhp,
                onCheckedChange = { enablePhp = it },
            )
            SwitchPreference(
                title = stringResource(R.string.site_dir_listing),
                summary = stringResource(R.string.site_dir_listing_summary),
                checked = dirListing,
                onCheckedChange = { dirListing = it },
            )
            TextField(
                value = indexFiles,
                onValueChange = { indexFiles = it },
                label = stringResource(R.string.site_index_files),
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (enablePhp) {
                Text(
                    text = stringResource(R.string.site_php_deny_dirs_hint),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                )
                TextField(
                    value = phpDenyDirs,
                    onValueChange = { phpDenyDirs = it },
                    label = stringResource(R.string.site_php_deny_dirs),
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.site_php_extensions_hint),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                )
                TextField(
                    value = phpExtensions,
                    onValueChange = { phpExtensions = it },
                    label = stringResource(R.string.site_php_extensions),
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.runtime.versions.containsKey(
                        io.github.masgzy.nekoport.runtime.RuntimePackage.PHP_FPM,
                    )
                ) {
                    Text(
                        text = stringResource(R.string.site_php_not_installed),
                        color = colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
            }
            if (error != null) {
                Text(text = error!!, color = colorScheme.primary, style = MiuixTheme.textStyles.main)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!isNew) {
                    NekoTextButton(
                        text = stringResource(R.string.dialog_delete),
                        onClick = {
                            val updated = config.sites.filterNot { it.id == site!!.id }
                            manager.stopSiteAsync(site!!.id)
                            manager.updateSites(updated)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                NekoTextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                NekoTextButton(
                    text = stringResource(R.string.site_confirm),
                    onClick = {
                        val portNum = port.text.toIntOrNull()
                        if (portNum == null || !SiteConfig.isPortValid(portNum)) {
                            showError(R.string.site_invalid_port)
                            return@NekoTextButton
                        }
                        if (config.sites.any { it.id != site?.id && it.port == portNum }) {
                            showError(R.string.site_port_conflict)
                            return@NekoTextButton
                        }
                        val engine = Engine.entries[engineIndex.coerceIn(0, Engine.entries.lastIndex)]
                        val finalName: String
                        if (isNew) {
                            val candidate = name.text.trim()
                            if (!SiteConfig.NAME_REGEX.matches(candidate)) {
                                showError(R.string.site_invalid_name)
                                return@NekoTextButton
                            }
                            finalName = candidate
                            // All disk work and the (blocking) service launch run on IO.
                            scope.launch(Dispatchers.IO) {
                                val docRootValue = docRoot.text.trim()
                                if (docRootValue.isEmpty()) {
                                    val dir = manager.siteDir(finalName)
                                    dir.mkdirs()
                                    val index = File(dir, "index.html")
                                    if (!index.exists()) {
                                        index.writeText(
                                            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                                                "<title>$finalName</title></head><body>" +
                                                "<h1>$finalName</h1><p>Powered by NekoPort</p></body></html>"
                                        )
                                    }
                                } else {
                                    File(docRootValue).mkdirs()
                                }
                                val newSite = SiteConfig(
                                    id = manager.nextSiteId(),
                                    name = finalName,
                                    port = portNum,
                                    enabled = enabled,
                                    engine = engine,
                                    enablePhp = enablePhp,
                                    directoryListing = dirListing,
                                    indexFiles = indexFiles.text.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                    phpDenyDirs = phpDenyDirs.text.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                    phpExtensions = phpExtensions.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                        .ifEmpty { listOf("php") },
                                    docRoot = docRootValue,
                                )
                                manager.updateSites(config.sites + newSite)
                                if (enabled) manager.startSite(newSite)
                            }
                        } else {
                            val edited = site!!.copy(
                                port = portNum,
                                enabled = enabled,
                                engine = engine,
                                enablePhp = enablePhp,
                                directoryListing = dirListing,
                                indexFiles = indexFiles.text.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                phpDenyDirs = phpDenyDirs.text.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                phpExtensions = phpExtensions.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                    .ifEmpty { listOf("php") },
                                docRoot = docRoot.text.trim(),
                            )
                            val wasRunning = manager.state.value.sites
                                .firstOrNull { it.site.id == edited.id }?.running ?: false
                            scope.launch(Dispatchers.IO) {
                                if (wasRunning) manager.stopSite(edited.id)
                                manager.updateSites(config.sites.map { if (it.id == edited.id) edited else it })
                                if (wasRunning && edited.enabled) manager.startSite(edited)
                            }
                        }
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
