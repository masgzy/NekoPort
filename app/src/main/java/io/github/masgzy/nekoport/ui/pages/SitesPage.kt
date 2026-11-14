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
import io.github.masgzy.nekoport.core.ServerService
import io.github.masgzy.nekoport.core.SiteConfig
import io.github.masgzy.nekoport.core.SiteRuntime
import io.github.masgzy.nekoport.runtime.TermuxRuntime
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

@Composable
fun SitesPage(contentPadding: PaddingValues) {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingSite by remember { mutableStateOf<SiteConfig?>(null) }

    // Keep runtime status fresh (install state, version detection).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            manager.refreshRuntimeStatus()
            manager.refresh()
            kotlinx.coroutines.delay(4000)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Master switch - starts/stops every enabled service in a
            // foreground service so servers survive leaving the app.
            item {
                Card {
                    SwitchPreference(
                        title = stringResource(R.string.sites_master_switch),
                        summary = stringResource(R.string.sites_master_switch_summary),
                        checked = state.running,
                        onCheckedChange = { on ->
                            if (on) ServerService.start(context) else ServerService.stop(context)
                        },
                    )
                }
            }

            // Bundled real-server runtime status.
            item {
                RuntimeCard()
            }

            if (state.sites.isEmpty()) {
                item {
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.sites_empty),
                                color = colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.main,
                            )
                            TextButton(
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
private fun RuntimeCard() {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val colorScheme = MiuixTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val runtime = state.runtime
    var progressText by remember { mutableStateOf<String?>(null) }

    Card {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.runtime_title),
                color = colorScheme.onSurface,
                style = MiuixTheme.textStyles.title2,
            )
            Text(
                text = if (runtime.installed) {
                    val versions = runtime.versions.entries.joinToString(", ") {
                        "${it.key.label} ${it.value}"
                    }
                    versions.ifEmpty { stringResource(R.string.runtime_ready) }
                } else {
                    stringResource(R.string.runtime_missing)
                },
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                modifier = Modifier.padding(top = 4.dp),
            )
            progressText?.let {
                Text(
                    text = it,
                    color = colorScheme.primary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (!runtime.installed) {
            TextButton(
                text = stringResource(R.string.runtime_install),
                enabled = progressText == null,
                onClick = {
                    progressText = context.getString(R.string.runtime_installing)
                    scope.launch {
                        val mirror = config.settings.phpMirror
                        val result = TermuxRuntime.install(context, mirror) { progress ->
                            progressText = when (progress) {
                                is TermuxRuntime.Progress.Phase -> "..."
                                is TermuxRuntime.Progress.Package ->
                                    "${progress.name} (${progress.index}/${progress.total})"
                                is TermuxRuntime.Progress.Download ->
                                    "${progress.name} ${progress.percent}%"
                            }
                        }
                        result.fold(
                            onSuccess = { progressText = null },
                            onFailure = {
                                progressText = it.message
                            },
                        )
                        manager.refreshRuntimeStatus()
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SiteCard(runtime: SiteRuntime, onEdit: () -> Unit) {
    val manager = NekoApp.get().serverManager
    val colorScheme = MiuixTheme.colorScheme
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
                        stringResource(R.string.site_status_stopped)
                    },
                )
            },
            checked = runtime.running,
            onCheckedChange = { on ->
                if (on) manager.startSite(runtime.site) else manager.stopSite(runtime.site.id)
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
    }
}

@Composable
private fun SiteEditorDialog(site: SiteConfig?, onDismiss: () -> Unit) {
    val manager = NekoApp.get().serverManager
    val config by manager.config.collectAsState()
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val isNew = site == null

    var name by remember { mutableStateOf(TextFieldValue(site?.name ?: "")) }
    var port by remember { mutableStateOf(TextFieldValue(site?.port?.toString() ?: "8080")) }
    var engineIndex by remember {
        mutableStateOf(Engine.entries.indexOfFirst { it == (site?.engine ?: Engine.NGINX) }.coerceAtLeast(0))
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
    var error by remember { mutableStateOf<String?>(null) }

    fun showError(res: Int) {
        error = context.getString(res)
    }

    OverlayDialog(
        show = true,
        title = if (isNew) stringResource(R.string.sites_add) else stringResource(R.string.site_edit),
        summary = if (!isNew) stringResource(R.string.site_docroot) + ": sites/" + site!!.name else null,
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
                    TextButton(
                        text = stringResource(R.string.dialog_delete),
                        onClick = {
                            val updated = config.sites.filterNot { it.id == site!!.id }
                            manager.stopSite(site!!.id)
                            manager.updateSites(updated)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.site_confirm),
                    onClick = {
                        val portNum = port.text.toIntOrNull()
                        if (portNum == null || !SiteConfig.isPortValid(portNum)) {
                            showError(R.string.site_invalid_port)
                            return@TextButton
                        }
                        if (config.sites.any { it.id != site?.id && it.port == portNum }) {
                            showError(R.string.site_port_conflict)
                            return@TextButton
                        }
                        val engine = Engine.entries[engineIndex.coerceIn(0, Engine.entries.lastIndex)]
                        val finalName: String
                        if (isNew) {
                            val candidate = name.text.trim()
                            if (!SiteConfig.NAME_REGEX.matches(candidate)) {
                                showError(R.string.site_invalid_name)
                                return@TextButton
                            }
                            finalName = candidate
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
                            )
                            manager.updateSites(config.sites + newSite)
                            if (enabled) manager.startSite(newSite)
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
                            )
                            val wasRunning = manager.state.value.sites
                                .firstOrNull { it.site.id == edited.id }?.running ?: false
                            manager.stopSite(edited.id)
                            manager.updateSites(config.sites.map { if (it.id == edited.id) edited else it })
                            if (wasRunning && edited.enabled) manager.startSite(edited)
                        }
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
