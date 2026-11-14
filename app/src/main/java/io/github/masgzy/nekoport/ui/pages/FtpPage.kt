package io.github.masgzy.nekoport.ui.pages

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.FtpConfig
import io.github.masgzy.nekoport.core.NetUtils
import io.github.masgzy.nekoport.core.ServerService
import io.github.masgzy.nekoport.runtime.TermuxRuntime
import io.github.masgzy.nekoport.ui.OverlayBus
import io.github.masgzy.nekoport.ui.pages.EditorTarget
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.security.SecureRandom

@Composable
fun FtpPage(contentPadding: PaddingValues) {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val ftp = config.ftp
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current

    var port by remember(ftp.port) { mutableStateOf(TextFieldValue(ftp.port.toString())) }
    var username by remember(ftp.username) { mutableStateOf(TextFieldValue(ftp.username)) }
    var password by remember(ftp.password) { mutableStateOf(TextFieldValue(ftp.password)) }
    var pasvStart by remember(ftp.passivePortStart) { mutableStateOf(TextFieldValue(ftp.passivePortStart.toString())) }
    var addresses by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        while (true) {
            addresses = NetUtils.localAddresses()
            manager.refresh()
            delay(4000)
        }
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Status + master switch
        item {
            Card {
                SwitchPreference(
                    title = stringResource(R.string.ftp_enable),
                    summary = if (state.ftpRunning) {
                        stringResource(R.string.ftp_status_running, ftp.port)
                    } else {
                        stringResource(R.string.ftp_status_stopped)
                    },
                    checked = state.ftpRunning,
                    onCheckedChange = { on ->
                        if (on) {
                            manager.updateFtp(ftp.copy(enabled = true))
                            ServerService.start(context)
                        } else {
                            manager.updateFtp(ftp.copy(enabled = false))
                            manager.stopFtp()
                        }
                    },
                )
                state.ftpStatusText?.let {
                    Text(
                        text = it,
                        color = colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Connection info
        if (state.ftpRunning) {
            item {
                SmallTitle(text = stringResource(R.string.ftp_connection))
                Card {
                    ArrowPreference(
                        title = "ftp://${ftp.username}@${addresses.firstOrNull() ?: "127.0.0.1"}:${ftp.port}/",
                        summary = stringResource(R.string.ftp_connection_hint),
                        onClick = { },
                    )
                    addresses.forEach { ip ->
                        Text(
                            text = "ftp://$ip:${ftp.port}/",
                            color = colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.subtitle,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.ftp_root_hint),
                        color = colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Files (connection log)
        item {
            Card {
                ArrowPreference(
                    title = stringResource(R.string.ftp_open_log),
                    onClick = {
                        OverlayBus.openEditor(
                            EditorTarget(
                                title = "ftp.log",
                                file = TermuxRuntime.logsDir(context).resolve("ftp.log"),
                                readOnly = true,
                            ),
                        )
                    },
                )
            }
        }

        // Account
        item {
            SmallTitle(text = stringResource(R.string.ftp_account))
            Card {
                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = stringResource(R.string.ftp_username),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.ftp_password),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                TextButton(
                    text = stringResource(R.string.ftp_generate),
                    onClick = {
                        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
                        val random = SecureRandom()
                        password = TextFieldValue(
                            (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
                        )
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Ports
        item {
            SmallTitle(text = stringResource(R.string.ftp_ports))
            Card {
                TextField(
                    value = port,
                    onValueChange = { port = it },
                    label = stringResource(R.string.ftp_port),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                TextField(
                    value = pasvStart,
                    onValueChange = { pasvStart = it },
                    label = stringResource(R.string.ftp_pasv_start),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text(
                    text = stringResource(R.string.ftp_pasv_hint, (pasvStart.text.toIntOrNull() ?: 50000) + 32),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // Save + apply
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.ftp_save),
                    onClick = {
                        val portNum = port.text.toIntOrNull()?.takeIf { FtpConfig.isPortValid(it) } ?: ftp.port
                        val pasvNum = pasvStart.text.toIntOrNull()?.takeIf { FtpConfig.isPortValid(it) }
                            ?: ftp.passivePortStart
                        val newFtp = ftp.copy(
                            username = username.text.trim().ifEmpty { ftp.username },
                            password = password.text.ifEmpty { ftp.password },
                            port = portNum,
                            passivePortStart = pasvNum,
                        )
                        val wasRunning = state.ftpRunning
                        manager.updateFtp(newFtp)
                        if (wasRunning) manager.startFtp()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
