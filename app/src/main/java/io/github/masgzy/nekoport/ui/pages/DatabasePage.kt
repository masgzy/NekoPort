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
import io.github.masgzy.nekoport.runtime.MariaDbController
import io.github.masgzy.nekoport.runtime.RuntimePackage
import io.github.masgzy.nekoport.runtime.TermuxRuntime
import io.github.masgzy.nekoport.ui.OverlayBus
import io.github.masgzy.nekoport.ui.component.CopyableText
import io.github.masgzy.nekoport.ui.component.NekoSmallTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // V3 hardening: turning on LAN access requires an explicit warning dialog
    // (root starts with an empty password - anyone on the Wi-Fi would get the
    // full database), and root's password can be changed in place.
    var showRemoteWarn by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var rootPasswordCustom by remember { mutableStateOf(MariaDbController.isRootPasswordCustom(context)) }
    var showRemoteNeedPassword by remember { mutableStateOf(false) }

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
                // Bootstrap/startup failure details; long-press copies.
                if (!state.mariaRunning && state.mariaStatusText != null) {
                    CopyableText(
                        text = state.mariaStatusText!!,
                        color = MiuixTheme.colorScheme.primary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
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
                            // falls back to the recorded index version when
                            // the probe fails (e.g. broken component), so the
                            // row never feels unresponsive.
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                versionDetails = TermuxRuntime.rawVersionOutput(context, RuntimePackage.MARIADB)
                                    ?: TermuxRuntime.recordedVersion(context, RuntimePackage.MARIADB)
                                        ?.let {
                                            "mariadbd $it\n\n(mariadbd --version 执行失败；" +
                                                "组件可能不完整，请在设置中更新或重装服务器组件)"
                                        }
                                    ?: "mariadbd 不存在，请在设置中安装服务器组件"
                            }
                        },
                    )
                }
                SwitchPreference(
                    title = stringResource(R.string.maria_allow_remote),
                    summary = stringResource(R.string.maria_allow_remote_summary),
                    checked = maria.allowRemote,
                    onCheckedChange = { on ->
                        if (on) {
                            if (rootPasswordCustom) {
                                // LAN access with a user-set root password:
                                // still worth a warning, but the credential
                                // exists. With the default EMPTY root password
                                // this dialog used to be a formality - the
                                // whole database was reachable with zero
                                // credentials from the network.
                                showRemoteWarn = true
                            } else {
                                showRemoteNeedPassword = true
                            }
                        } else {
                            manager.updateMaria(maria.copy(allowRemote = false))
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.maria_set_root_pw),
                    summary = stringResource(
                        if (rootPasswordCustom) R.string.maria_set_root_pw_custom
                        else R.string.maria_set_root_pw_empty,
                    ),
                    onClick = { showPasswordDialog = true },
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
                            ConfigWriter.writeMariaCnf(
                                context, config.maria, ConfigWriter.mariaDataDir(context),
                                config.settings.localOnly,
                            )
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

    // Version details dialog (tapping the version row); long-press copies.
    versionDetails?.let { details ->
        OverlayDialog(
            show = true,
            title = stringResource(R.string.maria_version_title),
            onDismissRequest = { versionDetails = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                CopyableText(
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

    // LAN-access confirmation: with the default empty root password the whole
    // database would be readable/writable by anyone on the network.
    if (showRemoteNeedPassword) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.maria_remote_warn_title),
            onDismissRequest = { showRemoteNeedPassword = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.maria_remote_need_password),
                    color = colorScheme.primary,
                    style = MiuixTheme.textStyles.main,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NekoTextButton(
                        text = stringResource(R.string.dialog_cancel),
                        onClick = { showRemoteNeedPassword = false },
                        modifier = Modifier.weight(1f),
                    )
                    NekoTextButton(
                        text = stringResource(R.string.maria_remote_set_password),
                        onClick = {
                            showRemoteNeedPassword = false
                            showPasswordDialog = true
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // LAN-access confirmation: with the default empty root password the whole
    // database would be readable/writable by anyone on the network.
    if (showRemoteWarn) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.maria_remote_warn_title),
            onDismissRequest = { showRemoteWarn = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.maria_remote_warn_text),
                    color = colorScheme.primary,
                    style = MiuixTheme.textStyles.main,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NekoTextButton(
                        text = stringResource(R.string.dialog_cancel),
                        onClick = { showRemoteWarn = false },
                        modifier = Modifier.weight(1f),
                    )
                    NekoTextButton(
                        text = stringResource(R.string.maria_remote_warn_confirm),
                        onClick = {
                            manager.updateMaria(maria.copy(allowRemote = true))
                            showRemoteWarn = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // Root password management: set / change / clear in place. Without this
    // the only way was raw SQL, and the startup probe used to clear whatever
    // the user set (fixed: user passwords are detected and respected).
    if (showPasswordDialog) {
        var currentPw by remember { mutableStateOf(TextFieldValue("")) }
        var newPw by remember { mutableStateOf(TextFieldValue("")) }
        var confirmPw by remember { mutableStateOf(TextFieldValue("")) }
        var pwError by remember { mutableStateOf<String?>(null) }
        var pwBusy by remember { mutableStateOf(false) }
        OverlayDialog(
            show = true,
            title = stringResource(R.string.maria_set_root_pw),
            summary = stringResource(R.string.maria_set_root_pw_summary),
            onDismissRequest = { if (!pwBusy) showPasswordDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = currentPw,
                    onValueChange = { currentPw = it },
                    label = stringResource(R.string.maria_pw_current),
                    useLabelAsPlaceholder = false,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = newPw,
                    onValueChange = { newPw = it },
                    label = stringResource(R.string.maria_pw_new),
                    useLabelAsPlaceholder = false,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = confirmPw,
                    onValueChange = { confirmPw = it },
                    label = stringResource(R.string.maria_pw_confirm),
                    useLabelAsPlaceholder = false,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                pwError?.let {
                    CopyableText(
                        text = it,
                        color = colorScheme.primary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NekoTextButton(
                        text = stringResource(R.string.dialog_cancel),
                        enabled = !pwBusy,
                        onClick = { showPasswordDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    NekoTextButton(
                        text = stringResource(R.string.maria_pw_apply),
                        enabled = !pwBusy,
                        onClick = {
                            when {
                                !state.mariaRunning ->
                                    pwError = context.getString(R.string.maria_pw_need_running)
                                newPw.text != confirmPw.text ->
                                    pwError = context.getString(R.string.maria_pw_mismatch)
                                else -> {
                                    pwBusy = true
                                    scope.launch(Dispatchers.IO) {
                                        val result = MariaDbController.setRootPassword(
                                            context, maria, currentPw.text, newPw.text,
                                        )
                                        withContext(Dispatchers.Main) {
                                            pwBusy = false
                                            result.fold(
                                                onSuccess = {
                                                    rootPasswordCustom = newPw.text.isNotEmpty()
                                                    showPasswordDialog = false
                                                },
                                                onFailure = {
                                                    pwError = context.getString(
                                                        R.string.maria_pw_failed,
                                                        it.message ?: "unknown",
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
