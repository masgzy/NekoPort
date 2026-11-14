package io.github.masgzy.nekoport.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.BuildConfig
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.TermuxMirror
import io.github.masgzy.nekoport.core.ThemeMode
import io.github.masgzy.nekoport.runtime.RuntimePackage
import io.github.masgzy.nekoport.runtime.TermuxRuntime
import io.github.masgzy.nekoport.ui.OverlayBus
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import io.github.masgzy.nekoport.ui.component.NekoTextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsPage(contentPadding: PaddingValues) {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val settings = config.settings
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var themeIndex by remember { mutableIntStateOf(ThemeMode.entries.indexOf(settings.themeMode)) }
    var showMoreMirrors by remember { mutableStateOf(false) }
    var showCustomMirror by remember { mutableStateOf(false) }
    var customMirror by remember { mutableStateOf(TextFieldValue(settings.repoMirror)) }

    LaunchedEffect(Unit) {
        manager.refreshRuntimeStatus()
    }

    val pinnedLabels = TermuxMirror.PINNED.map { it.label }
    val selectedMirror = TermuxMirror.byUrl(settings.repoMirror)
    val selectedIndex = when {
        selectedMirror != null && selectedMirror.pinned -> TermuxMirror.PINNED.indexOf(selectedMirror)
        else -> TermuxMirror.PINNED.size // "more..." row
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // ---- Appearance ----
        item {
            SmallTitle(text = stringResource(R.string.settings_theme))
            Card {
                OverlaySpinnerPreference(
                    items = listOf(
                        stringResource(R.string.settings_theme_system),
                        stringResource(R.string.settings_theme_light),
                        stringResource(R.string.settings_theme_dark),
                    ).map { DropdownItem(text = it, onClick = {}) },
                    selectedIndex = themeIndex,
                    title = stringResource(R.string.settings_theme),
                    onSelectedIndexChange = { idx ->
                        themeIndex = idx
                        manager.updateSettings(
                            settings.copy(themeMode = ThemeMode.entries[idx])
                        )
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_boot_start),
                    summary = stringResource(R.string.settings_boot_start_summary),
                    checked = settings.startOnBoot,
                    onCheckedChange = { manager.updateSettings(settings.copy(startOnBoot = it)) },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_battery),
                    summary = stringResource(R.string.settings_battery_summary),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:io.github.masgzy.nekoport"),
                                ),
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                )
                            }
                        }
                    },
                )
            }
        }

        // ---- Server runtime: every component managed individually ----
        item {
            SmallTitle(text = stringResource(R.string.runtime_title))
        }
        item {
            RuntimeComponentsCard()
        }
        item {
            Card {
                OverlaySpinnerPreference(
                    items = (pinnedLabels + listOf(stringResource(R.string.mirror_more)))
                        .map { DropdownItem(text = it, onClick = {}) },
                    selectedIndex = selectedIndex,
                    title = stringResource(R.string.mirror_title),
                    summary = settings.repoMirror,
                    onSelectedIndexChange = { idx ->
                        if (idx < TermuxMirror.PINNED.size) {
                            manager.updateSettings(settings.copy(repoMirror = TermuxMirror.PINNED[idx].url))
                        } else {
                            showMoreMirrors = true
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.runtime_note),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // ---- About ----
        item {
            SmallTitle(text = stringResource(R.string.settings_about))
            Card {
                Text(
                    text = stringResource(R.string.settings_about_summary),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.main,
                    modifier = Modifier.padding(16.dp),
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_github),
                    summary = "https://github.com/masgzy/NekoPort",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/masgzy/NekoPort"))
                            )
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_editor_credit),
                    summary = "https://github.com/Acode-Foundation/Acode",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Acode-Foundation/Acode"))
                            )
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_app_logs),
                    onClick = { OverlayBus.showAppLogs() },
                )
                Text(
                    text = stringResource(R.string.settings_license, BuildConfig.VERSION_NAME),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showMoreMirrors) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.mirror_more),
            summary = stringResource(R.string.mirror_more_summary),
            onDismissRequest = { showMoreMirrors = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TermuxMirror.MORE.forEach { mirror ->
                    ArrowPreference(
                        title = mirror.label,
                        summary = mirror.url,
                        onClick = {
                            manager.updateSettings(settings.copy(repoMirror = mirror.url))
                            showMoreMirrors = false
                        },
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.mirror_custom),
                    summary = stringResource(R.string.mirror_custom_summary),
                    onClick = {
                        showMoreMirrors = false
                        showCustomMirror = true
                    },
                )
            }
        }
    }

    if (showCustomMirror) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.mirror_custom),
            summary = stringResource(R.string.mirror_custom_summary),
            onDismissRequest = { showCustomMirror = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = customMirror,
                    onValueChange = { customMirror = it },
                    label = stringResource(R.string.mirror_url),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NekoTextButton(
                        text = stringResource(R.string.dialog_cancel),
                        onClick = { showCustomMirror = false },
                        modifier = Modifier.weight(1f),
                    )
                    NekoTextButton(
                        text = stringResource(R.string.dialog_ok),
                        onClick = {
                            val url = customMirror.text.trim()
                            if (url.startsWith("http")) {
                                manager.updateSettings(settings.copy(repoMirror = url))
                            }
                            showCustomMirror = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * One row per bundled component with install / update / uninstall actions.
 * Uninstalling keeps shared dependencies (files claimed by another installed
 * package) and refuses to remove a web engine that still serves a site.
 */
@Composable
private fun RuntimeComponentsCard() {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // progress text keyed by package name; busy = any operation running
    var progressFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf<RuntimePackage?>(null) }
    var sizes by remember { mutableStateOf<Map<RuntimePackage, Long>>(emptyMap()) }

    LaunchedEffect(state.runtime.installed, state.runtime.versions) {
        val out = HashMap<RuntimePackage, Long>()
        for (pkg in RuntimePackage.ALL) {
            out[pkg] = TermuxRuntime.packageSize(context, pkg)
        }
        sizes = out
    }

    fun progressLabel(pkg: RuntimePackage) = progressFor?.takeIf { it.first == pkg.packageName }?.second

    suspend fun runInstall(roots: List<RuntimePackage>?, progressKey: String) {
        progressFor = progressKey to context.getString(R.string.runtime_installing)
        errorText = null
        val result = TermuxRuntime.install(context, config.settings.repoMirror, roots) { progress ->
            val label = when (progress) {
                is TermuxRuntime.Progress.Package -> "${progress.name} (${progress.index}/${progress.total})"
                is TermuxRuntime.Progress.Download -> "${progress.name} ${progress.percent}%"
                is TermuxRuntime.Progress.Phase -> null
            }
            if (label != null) progressFor = progressKey to label
        }
        result.fold(
            onSuccess = { progressFor = null },
            onFailure = {
                progressFor = null
                errorText = it.message ?: "unknown error"
            },
        )
        manager.refreshRuntimeStatus()
    }

    Card {
        RuntimePackage.ALL.forEach { pkg ->
            val version = state.runtime.versions[pkg]
            val sizeMb = sizes[pkg] ?: 0L
            val busy = progressFor != null
            val summary = buildString {
                if (version != null) {
                    append("v$version")
                    if (sizeMb > 0) append(" · ${"%.1f".format(sizeMb / 1048576.0)} MB")
                } else {
                    append(stringResource(R.string.runtime_not_installed))
                }
                progressLabel(pkg)?.let { append(" · $it") }
            }
            Text(
                text = pkg.label,
                color = colorScheme.onSurface,
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = summary,
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NekoTextButton(
                    text = stringResource(
                        if (version != null) R.string.runtime_update_one else R.string.runtime_install_one,
                    ),
                    enabled = !busy,
                    onClick = {
                        scope.launch { runInstall(listOf(pkg), pkg.packageName) }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (version != null) {
                    NekoTextButton(
                        text = stringResource(R.string.runtime_uninstall_one),
                        enabled = !busy,
                        onClick = { confirmUninstall = pkg },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        errorText?.let {
            Text(
                text = stringResource(R.string.runtime_install_failed, it),
                color = colorScheme.primary,
                style = MiuixTheme.textStyles.subtitle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    confirmUninstall?.let { pkg ->
        OverlayDialog(
            show = true,
            title = stringResource(R.string.runtime_uninstall_one, pkg.label),
            summary = stringResource(R.string.runtime_uninstall_one_confirm, pkg.label),
            onDismissRequest = { confirmUninstall = null },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NekoTextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = { confirmUninstall = null },
                    modifier = Modifier.weight(1f),
                )
                NekoTextButton(
                    text = stringResource(R.string.dialog_ok),
                    onClick = {
                        scope.launch {
                            progressFor = pkg.packageName to context.getString(R.string.runtime_installing)
                            val result = TermuxRuntime.uninstallPackage(context, pkg)
                            result.fold(
                                onSuccess = { errorText = null },
                                onFailure = { errorText = it.message },
                            )
                            progressFor = null
                            manager.refreshRuntimeStatus()
                            confirmUninstall = null
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
