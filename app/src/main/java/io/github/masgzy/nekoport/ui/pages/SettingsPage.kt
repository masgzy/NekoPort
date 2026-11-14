package io.github.masgzy.nekoport.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import io.github.masgzy.nekoport.core.PhpMirror
import io.github.masgzy.nekoport.core.ThemeMode
import io.github.masgzy.nekoport.runtime.TermuxRuntime
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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

    var themeIndex by remember { mutableStateOf(ThemeMode.entries.indexOf(settings.themeMode)) }
    var installProgress by remember { mutableStateOf<String?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var showUninstallConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        manager.refreshRuntimeStatus()
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
            }
        }

        // ---- Server runtime (real binaries) ----
        item {
            SmallTitle(text = stringResource(R.string.runtime_title))
            Card {
                val versions = state.runtime.versions
                if (versions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.runtime_missing),
                        color = colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.main,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                } else {
                    versions.forEach { (pkg, version) ->
                        ArrowPreference(
                            title = pkg.label,
                            summary = "v$version",
                            onClick = { },
                        )
                    }
                }
                installProgress?.let {
                    Text(
                        text = it,
                        color = colorScheme.primary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                installError?.let {
                    Text(
                        text = stringResource(R.string.runtime_install_failed, it),
                        color = colorScheme.primary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.runtime_note),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = if (state.runtime.installed) {
                            stringResource(R.string.runtime_reinstall)
                        } else {
                            stringResource(R.string.runtime_install)
                        },
                        enabled = installProgress == null,
                        onClick = {
                            installProgress = context.getString(R.string.runtime_installing)
                            installError = null
                            scope.launch {
                                val result = TermuxRuntime.install(context, settings.phpMirror) { progress ->
                                    installProgress = when (progress) {
                                        is TermuxRuntime.Progress.Package ->
                                            "${progress.name} (${progress.index}/${progress.total})"
                                        is TermuxRuntime.Progress.Download ->
                                            "${progress.name} ${progress.percent}%"
                                        is TermuxRuntime.Progress.Phase -> "..."
                                    }
                                }
                                result.fold(
                                    onSuccess = {
                                        installProgress = null
                                        manager.refreshRuntimeStatus()
                                    },
                                    onFailure = {
                                        installProgress = null
                                        installError = it.message ?: "unknown error"
                                    },
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.runtime_uninstall),
                        enabled = state.runtime.installed && installProgress == null,
                        onClick = { showUninstallConfirm = true },
                        modifier = Modifier.weight(1f),
                    )
                }
                TextField(
                    value = remember(settings.phpMirror) { TextFieldValue(settings.phpMirror) },
                    onValueChange = { manager.updateSettings(settings.copy(phpMirror = it.text)) },
                    label = stringResource(R.string.settings_php_mirror),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // ---- Security note ----
        item {
            SmallTitle(text = stringResource(R.string.settings_security))
            Card {
                Text(
                    text = stringResource(R.string.settings_security_summary),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.main,
                    modifier = Modifier.padding(16.dp),
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
                Text(
                    text = stringResource(R.string.settings_license, BuildConfig.VERSION_NAME),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showUninstallConfirm) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.runtime_uninstall),
            summary = stringResource(R.string.runtime_uninstall_confirm),
            onDismissRequest = { showUninstallConfirm = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = { showUninstallConfirm = false },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.dialog_ok),
                    onClick = {
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                manager.stopAll()
                                TermuxRuntime.uninstall(context)
                            }
                            manager.refreshRuntimeStatus()
                            showUninstallConfirm = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
