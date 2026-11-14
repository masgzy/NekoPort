package io.github.masgzy.nekoport.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.ServerService
import io.github.masgzy.nekoport.runtime.ConfigWriter
import io.github.masgzy.nekoport.runtime.RuntimePackage
import io.github.masgzy.nekoport.runtime.TermuxRuntime
import io.github.masgzy.nekoport.ui.OverlayBus
import io.github.masgzy.nekoport.ui.component.NekoSmallTitle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import io.github.masgzy.nekoport.ui.component.NekoTextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MySQL (MariaDB) management panel, embedded in the Manage tab. Embedded in
 * a non-scrolling Column - the surrounding ManagePage owns the scroll.
 */
@Composable
fun MariaPanel() {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val maria = config.maria
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var port by remember(maria.port) { mutableStateOf(TextFieldValue(maria.port.toString())) }
    var installProgress by remember { mutableStateOf<String?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var versionDetails by remember { mutableStateOf<String?>(null) }

    val mariaVersion = state.runtime.versions[RuntimePackage.MARIADB]
    val mariaInstalled = remember(state.runtime.versions) {
        TermuxRuntime.isMariaDbInstalled(context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!mariaInstalled) {
            // Component missing: offer the install/update flow.
            Card {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.maria_component_title),
                        color = colorScheme.onSurface,
                        style = MiuixTheme.textStyles.title2,
                    )
                    Text(
                        text = stringResource(R.string.maria_not_installed),
                        color = colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.main,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    installProgress?.let {
                        Text(
                            text = it,
                            color = colorScheme.primary,
                            style = MiuixTheme.textStyles.subtitle,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    installError?.let {
                        Text(
                            text = stringResource(R.string.runtime_install_failed, it),
                            color = colorScheme.primary,
                            style = MiuixTheme.textStyles.subtitle,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                NekoTextButton(
                    text = stringResource(R.string.runtime_install),
                    enabled = installProgress == null,
                    onClick = {
                        installProgress = context.getString(R.string.runtime_installing)
                        installError = null
                        scope.launch {
                            val result = TermuxRuntime.install(context, config.settings.repoMirror) { progress ->
                                installProgress = when (progress) {
                                    is TermuxRuntime.Progress.Package ->
                                        "${progress.name} (${progress.index}/${progress.total})"
                                    is TermuxRuntime.Progress.Download ->
                                        "${progress.name} ${progress.percent}%"
                                    is TermuxRuntime.Progress.Phase -> "..."
                                }
                            }
                            result.fold(
                                onSuccess = { installProgress = null },
                                onFailure = { installProgress = null; installError = it.message },
                            )
                            manager.refreshRuntimeStatus()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        } else {
            // Status + master switch
            Card {
                SwitchPreference(
                    title = stringResource(R.string.maria_enable),
                    summary = when {
                        state.mariaRunning -> stringResource(R.string.maria_status_running, maria.port)
                        state.mariaStatusText != null -> state.mariaStatusText!!
                        else -> stringResource(R.string.maria_status_stopped)
                    },
                    checked = state.mariaRunning,
                    onCheckedChange = { on ->
                        if (on) {
                            manager.updateMaria(maria.copy(enabled = true))
                            // Single command path: the foreground service keeps
                            // the notification alive, the manager starts MariaDB.
                            ServerService.start(context)
                            manager.startMariaAsync()
                        } else {
                            manager.updateMaria(maria.copy(enabled = false))
                            manager.stopMariaAsync()
                        }
                    },
                )
            }

            // Connection info
            if (state.mariaRunning) {
                NekoSmallTitle(text = stringResource(R.string.maria_connection))
                Card {
                    ArrowPreference(
                        title = "127.0.0.1:${maria.port}",
                        summary = stringResource(R.string.maria_connection_hint),
                        onClick = { },
                    )
                    if (maria.allowRemote) {
                        Text(
                            text = stringResource(R.string.maria_connection_remote),
                            color = colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.subtitle,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Settings
            NekoSmallTitle(text = stringResource(R.string.maria_settings))
            Card {
                if (mariaVersion != null) {
                    ArrowPreference(
                        title = stringResource(R.string.maria_version),
                        summary = mariaVersion,
                        onClick = {
                            // Full `mariadbd --version` output in a dialog;
                            // the row previously had a no-op click handler.
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                versionDetails = TermuxRuntime.rawVersionOutput(context, RuntimePackage.MARIADB)
                                    ?: "mariadbd --version failed"
                            }
                        },
                    )
                }
                SwitchPreference(
                    title = stringResource(R.string.maria_allow_remote),
                    summary = stringResource(R.string.maria_allow_remote_summary),
                    checked = maria.allowRemote,
                    onCheckedChange = { manager.updateMaria(maria.copy(allowRemote = it)) },
                )
                TextField(
                    value = port,
                    onValueChange = { port = it },
                    label = stringResource(R.string.maria_port),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text(
                    text = stringResource(R.string.maria_root_hint),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Save + apply
            Row(modifier = Modifier.fillMaxWidth()) {
                NekoTextButton(
                    text = stringResource(R.string.maria_save),
                    onClick = {
                        val portNum = port.text.toIntOrNull()
                        val newMaria = if (portNum != null && io.github.masgzy.nekoport.core.MariaConfig.isPortValid(portNum)) {
                            maria.copy(port = portNum)
                        } else {
                            maria
                        }
                        val wasRunning = state.mariaRunning
                        manager.updateMaria(newMaria)
                        if (wasRunning) {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    manager.stopMaria()
                                    manager.startMaria()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Files
            NekoSmallTitle(text = stringResource(R.string.maria_files))
            Card {
                ArrowPreference(
                    title = stringResource(R.string.maria_edit_conf),
                    summary = ConfigWriter.mariaCnf(context).canonicalPath,
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            ConfigWriter.writeMariaCnf(context, config.maria, ConfigWriter.mariaDataDir(context))
                            val file = ConfigWriter.ensureUserCopy(ConfigWriter.mariaCnfAuto(context))
                            OverlayBus.openEditor(
                                EditorTarget(
                                    title = "my.cnf",
                                    file = file,
                                    resetAction = { ConfigWriter.resetToAuto(ConfigWriter.mariaCnfAuto(context)) },
                                ),
                            )
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.maria_open_log),
                    onClick = {
                        OverlayBus.openEditor(
                            EditorTarget(
                                title = "mariadb.log",
                                file = ConfigWriter.logsDir(context).resolve("mariadb.log"),
                                readOnly = true,
                            ),
                        )
                    },
                )
            }
        }
    }

    // Version details dialog (tapping the version row).
    versionDetails?.let { details ->
        OverlayDialog(
            show = true,
            title = stringResource(R.string.maria_version_title),
            onDismissRequest = { versionDetails = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = details,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
                NekoTextButton(
                    text = stringResource(R.string.dialog_ok),
                    onClick = { versionDetails = null },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
