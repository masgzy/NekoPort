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
import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.FtpConfig
import io.github.masgzy.nekoport.core.PhpMirror
import io.github.masgzy.nekoport.core.ThemeMode
import io.github.masgzy.nekoport.php.PhpEnvironment
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
import java.security.SecureRandom
import java.util.Locale

@Composable
fun SettingsPage(contentPadding: PaddingValues) {
    val manager = NekoApp.get().serverManager
    val config by manager.config.collectAsState()
    val settings = config.settings
    val ftp = config.ftp
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var themeIndex by remember { mutableStateOf(ThemeMode.entries.indexOf(settings.themeMode)) }
    var phpBusy by remember { mutableStateOf(false) }
    var phpProgress by remember { mutableStateOf<String?>(null) }
    var phpError by remember { mutableStateOf<String?>(null) }

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
                SwitchPreference(
                    title = stringResource(R.string.settings_access_log),
                    checked = settings.accessLogEnabled,
                    onCheckedChange = {
                        manager.updateSettings(settings.copy(accessLogEnabled = it))
                        AppLog.accessLogEnabled = it
                    },
                )
            }
        }

        // ---- FTP ----
        item {
            SmallTitle(text = stringResource(R.string.settings_ftp))
            Card {
                FtpEditor(ftp = ftp, onSave = { manager.updateFtp(it) })
            }
        }

        // ---- PHP runtime ----
        item {
            SmallTitle(text = stringResource(R.string.settings_php))
            Card {
                val installed = settings.installedPhpVersion
                Text(
                    text = installed?.let { stringResource(R.string.settings_php_status_version, it) }
                        ?: stringResource(R.string.settings_php_status_none),
                    color = colorScheme.onSurface,
                    style = MiuixTheme.textStyles.main,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (installed != null) {
                    Text(
                        text = if (PhpMirror.isSafe(installed)) {
                            stringResource(R.string.settings_php_ok, installed)
                        } else {
                            stringResource(R.string.settings_php_outdated, installed, PhpMirror.RECOMMENDED)
                        },
                        color = colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                phpProgress?.let {
                    Text(
                        text = it,
                        color = colorScheme.primary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                phpError?.let {
                    Text(
                        text = stringResource(R.string.settings_php_status_failed, it),
                        color = colorScheme.primary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_php_note),
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
                        text = if (installed == null) {
                            stringResource(R.string.settings_php_install)
                        } else {
                            stringResource(R.string.settings_php_reinstall)
                        },
                        enabled = !phpBusy,
                        onClick = {
                            phpBusy = true
                            phpError = null
                            scope.launch {
                                PhpEnvironment.install(context, settings.phpMirror) { progress ->
                                    phpProgress = when (progress) {
                                        is PhpEnvironment.Progress.Phase ->
                                            when (progress.label.substringBefore(':')) {
                                                "index" -> context.getString(R.string.settings_php_status_downloading, "index")
                                                "resolve" -> context.getString(R.string.settings_php_status_downloading, "deps")
                                                "extract" -> context.getString(R.string.settings_php_status_extracting, progress.label.substringAfter(':'))
                                                else -> progress.label
                                            }
                                        is PhpEnvironment.Progress.Package ->
                                            context.getString(
                                                R.string.settings_php_status_downloading,
                                                "${progress.name} (${progress.index}/${progress.total})",
                                            )
                                        is PhpEnvironment.Progress.Download ->
                                            context.getString(
                                                R.string.settings_php_status_downloading,
                                                "${progress.name} ${progress.percent}%",
                                            )
                                    }
                                }.onSuccess { version ->
                                    manager.setPhpVersion(version)
                                    phpProgress = null
                                }.onFailure {
                                    phpError = it.message ?: "unknown error"
                                    phpProgress = null
                                }
                                phpBusy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.settings_php_uninstall),
                        enabled = installed != null && !phpBusy,
                        onClick = {
                            manager.uninstallPhp()
                        },
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
                    text = stringResource(R.string.settings_license),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FtpEditor(ftp: FtpConfig, onSave: (FtpConfig) -> Unit) {
    var enabled by remember { mutableStateOf(ftp.enabled) }
    var port by remember { mutableStateOf(TextFieldValue(ftp.port.toString())) }
    var username by remember { mutableStateOf(TextFieldValue(ftp.username)) }
    var password by remember { mutableStateOf(TextFieldValue(ftp.password)) }
    var pasvStart by remember { mutableStateOf(TextFieldValue(ftp.passivePortStart.toString())) }
    var showConfirm by remember { mutableStateOf(false) }

    SwitchPreference(
        title = stringResource(R.string.settings_ftp_enable),
        checked = enabled,
        onCheckedChange = {
            enabled = it
            showConfirm = true
        },
    )
    TextField(
        value = port,
        onValueChange = { port = it },
        label = stringResource(R.string.settings_ftp_port),
        useLabelAsPlaceholder = true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
    TextField(
        value = username,
        onValueChange = { username = it },
        label = stringResource(R.string.settings_ftp_user),
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
    TextField(
        value = password,
        onValueChange = { password = it },
        label = stringResource(R.string.settings_ftp_password),
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
    TextButton(
        text = stringResource(R.string.settings_ftp_generate),
        onClick = {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            password = TextFieldValue(
                (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            )
            showConfirm = true
        },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
    TextField(
        value = pasvStart,
        onValueChange = { pasvStart = it },
        label = stringResource(R.string.settings_ftp_pasv_start),
        useLabelAsPlaceholder = true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )

    if (showConfirm) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.settings_ftp),
            onDismissRequest = { showConfirm = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = stringResource(R.string.dialog_cancel),
                        onClick = {
                            showConfirm = false
                            // revert
                            enabled = ftp.enabled
                            port = TextFieldValue(ftp.port.toString())
                            username = TextFieldValue(ftp.username)
                            password = TextFieldValue(ftp.password)
                            pasvStart = TextFieldValue(ftp.passivePortStart.toString())
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.dialog_ok),
                        onClick = {
                            val newFtp = ftp.copy(
                                enabled = enabled,
                                port = port.text.toIntOrNull()?.takeIf { FtpConfig.isPortValid(it) } ?: ftp.port,
                                username = username.text.trim().ifEmpty { ftp.username },
                                password = password.text.ifEmpty { ftp.password },
                                passivePortStart = pasvStart.text.toIntOrNull()
                                    ?.takeIf { FtpConfig.isPortValid(it) } ?: ftp.passivePortStart,
                            )
                            onSave(newFtp)
                            showConfirm = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
