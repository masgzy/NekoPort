package io.github.masgzy.nekoport.ui.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.masgzy.nekoport.BuildConfig
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.TermuxMirror
import io.github.masgzy.nekoport.core.ThemeMode
import io.github.masgzy.nekoport.runtime.RuntimePackage
import io.github.masgzy.nekoport.runtime.TermuxRuntime
import io.github.masgzy.nekoport.runtime.UpdateChecker
import io.github.masgzy.nekoport.ui.OverlayBus
import io.github.masgzy.nekoport.ui.component.CopyableText
import io.github.masgzy.nekoport.ui.component.IconAction
import io.github.masgzy.nekoport.ui.component.NekoSmallTitle
import io.github.masgzy.nekoport.ui.component.NekoTextButton
import io.github.masgzy.nekoport.ui.component.animation.pressFeedback
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val UpdateYellow = Color(0xFFF5A623)
private val UninstallRed = Color(0xFFE5484D)

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

    LaunchedEffect(Unit) {
        manager.refreshRuntimeStatus()
        // Check for component updates every time the settings page opens
        // (throttled inside UpdateChecker); rows with an update turn their
        // update icon yellow, rows without stay grey.
        UpdateChecker.check(context, settings.repoMirror)
    }

    val pinnedLabels = TermuxMirror.PINNED.map { it.label }
    val selectedMirror = TermuxMirror.byUrl(settings.repoMirror)
    val selectedIndex = when {
        selectedMirror != null && selectedMirror.pinned -> TermuxMirror.PINNED.indexOf(selectedMirror)
        else -> TermuxMirror.PINNED.size // "more..." row
    }

    // Battery optimization: the entry hides itself once the user granted the
    // exemption (refreshed every time the app comes back to the foreground,
    // i.e. after returning from the system settings screen).
    var batteryIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryIgnored = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // ---- Appearance ----
        item {
            NekoSmallTitle(text = stringResource(R.string.settings_theme))
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
                if (!batteryIgnored) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_battery),
                        summary = stringResource(R.string.settings_battery_summary),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:io.github.masgzy.nekoport"),
                                    ),
                                )
                            }.onFailure {
                                runCatching {
                                    context.startActivity(
                                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        // ---- Server runtime: every component managed individually ----
        item {
            NekoSmallTitle(text = stringResource(R.string.runtime_title))
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
            NekoSmallTitle(text = stringResource(R.string.settings_about))
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
        CustomMirrorDialog(
            currentMirror = settings.repoMirror,
            onDismiss = { showCustomMirror = false },
            onSave = { url ->
                manager.updateSettings(settings.copy(repoMirror = url))
                showCustomMirror = false
            },
        )
    }
}

@Composable
private fun CustomMirrorDialog(
    currentMirror: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    // Pre-filled with the active mirror so the field is never blank; the
    // current URL is also printed below for readability.
    var url by remember { mutableStateOf(TextFieldValue(currentMirror)) }
    var showError by remember { mutableStateOf(false) }

    OverlayDialog(
        show = true,
        title = stringResource(R.string.mirror_custom),
        summary = stringResource(R.string.mirror_custom_summary),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = url,
                onValueChange = {
                    url = it
                    showError = false
                },
                label = stringResource(R.string.mirror_url),
                useLabelAsPlaceholder = false,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.mirror_current, currentMirror),
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showError) {
                Text(
                    text = stringResource(R.string.mirror_invalid),
                    color = colorScheme.primary,
                    style = MiuixTheme.textStyles.subtitle,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NekoTextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                NekoTextButton(
                    text = stringResource(R.string.dialog_ok),
                    onClick = {
                        val value = url.text.trim()
                        if (value.startsWith("http://") || value.startsWith("https://")) {
                            onSave(value)
                        } else {
                            showError = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

/**
 * One row per bundled component with install / update / uninstall actions.
 * Batch actions live at the card's top-right; per-component actions are
 * icon buttons on the right of each row (install = download, update = yellow
 * refresh, uninstall = red trash). Uninstalling keeps shared dependencies
 * (files claimed by another installed package) and refuses to remove a web
 * engine that still serves a site.
 */
@Composable
private fun RuntimeComponentsCard() {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val updateState by UpdateChecker.state.collectAsState()
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // progress text keyed by package name; busy = any operation running
    var progressFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var batchProgress by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf<RuntimePackage?>(null) }
    var confirmUninstallAll by remember { mutableStateOf(false) }
    var sizes by remember { mutableStateOf<Map<RuntimePackage, Long>>(emptyMap()) }

    LaunchedEffect(state.runtime.installed, state.runtime.versions) {
        val out = HashMap<RuntimePackage, Long>()
        for (pkg in RuntimePackage.ALL) {
            out[pkg] = TermuxRuntime.packageSize(context, pkg)
        }
        sizes = out
    }

    fun progressLabel(pkg: RuntimePackage) = progressFor?.takeIf { it.first == pkg.packageName }?.second

    suspend fun runInstall(roots: List<RuntimePackage>?, force: Boolean, progressKey: String) {
        progressFor = progressKey to context.getString(R.string.runtime_installing)
        if (progressKey == "__all__") batchProgress = context.getString(R.string.runtime_installing)
        errorText = null
        val result = TermuxRuntime.install(context, config.settings.repoMirror, roots, force) { progress ->
            val label = when (progress) {
                is TermuxRuntime.Progress.Package -> "${progress.name} (${progress.index}/${progress.total})"
                is TermuxRuntime.Progress.Download -> "${progress.name} ${progress.percent}%"
                is TermuxRuntime.Progress.Phase -> null
            }
            if (label != null) {
                progressFor = progressKey to label
                // Batch installs must show their process too, not just the
                // per-row label (the batch key matches no single row).
                if (progressKey == "__all__") batchProgress = label
            }
        }
        result.fold(
            onSuccess = {
                progressFor = null
                batchProgress = null
                // Re-check updates with fresh versions after install/update.
                UpdateChecker.check(context, config.settings.repoMirror, force = true)
            },
            onFailure = {
                progressFor = null
                batchProgress = null
                errorText = it.message ?: "unknown error"
            },
        )
        manager.refreshRuntimeStatus()
    }

    Card {
        // Batch actions as compact icon+label tools on the right - three or
        // four Chinese characters as plain buttons felt more awkward here
        // than centered buttons, so each action is a small icon with a tiny
        // caption underneath.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BatchAction(
                icon = { Icon(MiuixIcons.Download, contentDescription = null, tint = colorScheme.onSurface) },
                label = stringResource(R.string.runtime_batch_install),
                tint = colorScheme.onSurface,
                enabled = progressFor == null,
                onClick = { scope.launch { runInstall(null, force = false, progressKey = "__all__") } },
            )
            BatchAction(
                icon = { Icon(MiuixIcons.Search, contentDescription = null, tint = colorScheme.onSurface) },
                label = stringResource(R.string.runtime_batch_check),
                tint = colorScheme.onSurface,
                enabled = progressFor == null && !updateState.checking,
                onClick = {
                    scope.launch { UpdateChecker.check(context, config.settings.repoMirror, force = true) }
                },
            )
            BatchAction(
                icon = { Icon(MiuixIcons.Delete, contentDescription = null, tint = UninstallRed) },
                label = stringResource(R.string.runtime_batch_uninstall),
                tint = UninstallRed,
                enabled = progressFor == null && state.runtime.installed,
                onClick = { confirmUninstallAll = true },
            )
        }
        // Visible progress for batch installs and update checks.
        when {
            progressFor?.first == "__all__" -> batchProgress?.let {
                StatusLine(text = it, color = colorScheme.primary)
            }
            updateState.checking -> StatusLine(
                text = stringResource(R.string.runtime_batch_checking),
                color = colorScheme.onSurfaceVariantSummary,
            )
            else -> {}
        }
        updateState.error?.let {
            StatusLine(
                text = stringResource(R.string.runtime_batch_check_failed, it),
                color = colorScheme.primary,
            )
        }

        RuntimePackage.ALL.forEach { pkg ->
            val version = state.runtime.versions[pkg]
            val sizeMb = sizes[pkg] ?: 0L
            val busy = progressFor != null
            val latest = updateState.available[pkg]
            val updatable = version != null && latest != null
            val summary = buildString {
                if (version != null) {
                    append("v$version")
                    if (sizeMb > 0) append(" · ${"%.1f".format(sizeMb / 1048576.0)} MB")
                    if (latest != null) append(" · ")
                        .append(stringResource(R.string.runtime_update_available, latest))
                } else {
                    append(stringResource(R.string.runtime_not_installed))
                }
                progressLabel(pkg)?.let { append(" · $it") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pkg.label,
                        color = colorScheme.onSurface,
                        style = MiuixTheme.textStyles.main,
                    )
                    Text(
                        text = summary,
                        color = colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconAction(
                    icon = { Icon(MiuixIcons.Download, contentDescription = stringResource(R.string.runtime_component_install)) },
                    tint = colorScheme.onSurface,
                    enabled = !busy && version == null,
                    contentDescription = stringResource(R.string.runtime_component_install),
                    onClick = { scope.launch { runInstall(listOf(pkg), force = false, progressKey = pkg.packageName) } },
                )
                if (version != null) {
                    Spacer(Modifier.height(8.dp))
                    // Update icon: grey (disabled) when the mirror has nothing
                    // newer, yellow and active when an update is available.
                    IconAction(
                        icon = { Icon(MiuixIcons.Refresh, contentDescription = stringResource(R.string.runtime_component_update)) },
                        tint = if (updatable) UpdateYellow else colorScheme.onSurfaceVariantSummary,
                        enabled = !busy && updatable,
                        contentDescription = stringResource(R.string.runtime_component_update),
                        onClick = { scope.launch { runInstall(listOf(pkg), force = true, progressKey = pkg.packageName) } },
                    )
                    Spacer(Modifier.height(8.dp))
                    IconAction(
                        icon = { Icon(MiuixIcons.Delete, contentDescription = stringResource(R.string.runtime_component_uninstall)) },
                        tint = UninstallRed,
                        enabled = !busy,
                        contentDescription = stringResource(R.string.runtime_component_uninstall),
                        onClick = { confirmUninstall = pkg },
                    )
                }
            }
        }
        if (updateState.lastCheckedAt == 0L && updateState.error == null && !updateState.checking) {
            Text(
                text = stringResource(R.string.runtime_update_unchecked),
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        errorText?.let {
            CopyableText(
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

    if (confirmUninstallAll) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.runtime_batch_uninstall),
            summary = stringResource(R.string.runtime_batch_uninstall_confirm),
            onDismissRequest = { confirmUninstallAll = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NekoTextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = { confirmUninstallAll = false },
                    modifier = Modifier.weight(1f),
                )
                NekoTextButton(
                    text = stringResource(R.string.dialog_ok),
                    onClick = {
                        scope.launch {
                            progressFor = "__all__" to context.getString(R.string.runtime_installing)
                            val outcome = runCatching {
                                manager.stopAll()
                                TermuxRuntime.uninstall(context)
                            }
                            outcome.fold(
                                onSuccess = { errorText = null },
                                onFailure = { errorText = it.message },
                            )
                            progressFor = null
                            manager.refreshRuntimeStatus()
                            confirmUninstallAll = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Batch action tool: small icon with a one-word caption underneath. Replaces
 * the former plain-text buttons ("三个四个字的按钮放在右上角比居中还别扭").
 */
@Composable
private fun BatchAction(
    icon: @Composable () -> Unit,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.35f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .pressFeedback(cornerRadius = 10.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(modifier = Modifier.alpha(alpha)) {
            icon()
        }
        Text(
            text = label,
            color = tint.copy(alpha = alpha * 0.9f),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** Single-line status (batch progress / check result). */
@Composable
private fun StatusLine(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MiuixTheme.textStyles.subtitle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
