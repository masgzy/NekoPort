package io.github.masgzy.nekoport.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.masgzy.nekoport.core.FtpAccount
import io.github.masgzy.nekoport.core.FtpConfig
import io.github.masgzy.nekoport.core.NetUtils
import io.github.masgzy.nekoport.core.ServerService
import io.github.masgzy.nekoport.runtime.TermuxRuntime
import io.github.masgzy.nekoport.ui.OverlayBus
import io.github.masgzy.nekoport.ui.component.CopyableText
import io.github.masgzy.nekoport.ui.component.NekoTextButton
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.security.SecureRandom

/**
 * FTP management panel (Pure-FTPd), embedded in the Manage tab. The
 * surrounding ManagePage owns scrolling; the address list refreshes on a
 * lightweight local-only timer.
 *
 * Supports multiple accounts: the legacy primary account (username/password
 * fields) plus extra accounts, each with an optional chroot directory.
 * Text fields keep their label visible above the value (the previous
 * label-as-placeholder style made it impossible to tell which field was
 * which once a value was entered).
 */
@Composable
fun FtpPanel() {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val ftp = config.ftp
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current

    var port by remember(ftp.port) { mutableStateOf(TextFieldValue(ftp.port.toString())) }
    var pasvStart by remember(ftp.passivePortStart) { mutableStateOf(TextFieldValue(ftp.passivePortStart.toString())) }
    // Extra accounts (editable copies); the primary account keeps the legacy fields.
    // Each draft carries a stable composition key: a plain positional list let
    // a deleted middle account's editor state slide onto the next account, so
    // editing then wrote account A's username/password into account B on save.
    var accountDrafts by remember(ftp.accounts) {
        mutableStateOf(ftp.accounts.map { AccountDraft(java.util.UUID.randomUUID().toString(), it) })
    }
    var username by remember(ftp.username) { mutableStateOf(TextFieldValue(ftp.username)) }
    var password by remember(ftp.password) { mutableStateOf(TextFieldValue(ftp.password)) }
    var addresses by remember { mutableStateOf(listOf<String>()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            addresses = NetUtils.localAddresses()
            delay(4000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Status + master switch
        Card {
            SwitchPreference(
                title = stringResource(R.string.ftp_enable),
                summary = if (state.ftpRunning) {
                    stringResource(R.string.ftp_status_running, ftp.port)
                } else {
                    state.ftpStatusText ?: stringResource(R.string.ftp_status_stopped)
                },
                checked = state.ftpRunning,
                onCheckedChange = { on ->
                    if (on) {
                        manager.updateFtp(ftp.copy(enabled = true))
                        ServerService.start(context)
                        manager.startFtpAsync()
                    } else {
                        manager.updateFtp(ftp.copy(enabled = false))
                        manager.stopFtpAsync()
                    }
                },
            )
            // Setup/startup failure details (pure-pw output, launch-log tail);
            // long-press copies the message so it can be reported verbatim.
            if (!state.ftpRunning && state.ftpStatusText != null) {
                CopyableText(
                    text = state.ftpStatusText!!,
                    color = colorScheme.primary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // Connection info
        if (state.ftpRunning) {
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
                Text(
                    // Android untrusted apps cannot chroot(): FTP users can
                    // browse the whole app sandbox below their root. Say so
                    // instead of pretending there is an isolation that does
                    // not exist (the -A flag used to die with 421 and broke
                    // every login).
                    text = stringResource(R.string.ftp_sandbox_warning),
                    color = colorScheme.primary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // Files (connection log)
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

        // Accounts
        Card {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.ftp_accounts_list),
                    color = colorScheme.onSurface,
                    style = MiuixTheme.textStyles.title3,
                )
                Text(
                    text = stringResource(R.string.ftp_accounts_summary),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Primary (legacy) account.
            LabeledField(
                label = stringResource(R.string.ftp_username),
                value = username,
                onValueChange = { username = it },
                keyboardType = null,
            )
            LabeledField(
                label = stringResource(R.string.ftp_password),
                value = password,
                onValueChange = { password = it },
                keyboardType = null,
                trailing = {
                    NekoTextButton(
                        text = stringResource(R.string.ftp_generate),
                        onClick = {
                            password = TextFieldValue(randomPassword())
                        },
                    )
                },
            )
            // Extra accounts.
            accountDrafts.forEachIndexed { index, draft ->
                key(draft.key) {
                    AccountEditor(
                        account = draft.account,
                        onChange = { updated ->
                            accountDrafts = accountDrafts
                                .mapIndexed { i, d -> if (i == index) d.copy(account = updated) else d }
                        },
                        onRemove = {
                            accountDrafts = accountDrafts.toMutableList().also { it.removeAt(index) }
                        },
                    )
                }
            }
            NekoTextButton(
                text = stringResource(R.string.ftp_add_account),
                onClick = {
                    accountDrafts = accountDrafts +
                        AccountDraft(java.util.UUID.randomUUID().toString(), FtpAccount(username = "", password = randomPassword()))
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // Ports
        Card {
            LabeledField(
                label = stringResource(R.string.ftp_port),
                value = port,
                onValueChange = { port = it },
                keyboardType = KeyboardType.Number,
            )
            LabeledField(
                label = stringResource(R.string.ftp_pasv_start),
                value = pasvStart,
                onValueChange = { pasvStart = it },
                keyboardType = KeyboardType.Number,
            )
            Text(
                text = stringResource(R.string.ftp_pasv_hint, (pasvStart.text.toIntOrNull() ?: 50000) + 32),
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        errorText?.let {
            CopyableText(
                text = it,
                color = colorScheme.primary,
                style = MiuixTheme.textStyles.subtitle,
            )
        }

        // Save + apply
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NekoTextButton(
                text = stringResource(R.string.ftp_save),
                onClick = {
                    val portNum = port.text.toIntOrNull()?.takeIf { FtpConfig.isPortValid(it) } ?: ftp.port
                    val pasvNum = pasvStart.text.toIntOrNull()?.takeIf { FtpConfig.isPortValid(it) }
                        ?: ftp.passivePortStart

                    fun trimmedAccount(a: FtpAccount) = a.copy(
                        username = a.username.trim(),
                        password = a.password,
                        root = a.root.trim(),
                    )
                    val extras = accountDrafts.map { trimmedAccount(it.account) }
                    val primaryName = username.text.trim().ifEmpty { ftp.username }
                    val allNames = listOf(primaryName) + extras.map { it.username }
                    val validNames = allNames.all { FtpAccount.nameValid(it) }
                    val unique = allNames.size == allNames.distinct().size
                    if (!validNames || !unique) {
                        errorText = context.getString(R.string.ftp_account_invalid)
                        return@NekoTextButton
                    }
                    // The passive range must fit into the port space; pure-ftpd
                    // refuses to start when pasvStart+32 overflows 65535.
                    if (pasvNum + 32 > 65535) {
                        errorText = context.getString(R.string.ftp_pasv_invalid)
                        return@NekoTextButton
                    }
                    // The FTP control port must not sit inside its own passive
                    // range (previously configurable; data connections could
                    // steal the listener's port and the server misbehaved).
                    if (portNum in pasvNum..(pasvNum + 32)) {
                        errorText = context.getString(R.string.ftp_port_in_pasv, portNum)
                        return@NekoTextButton
                    }
                    // Global port dedup: FTP must not collide with the site
                    // ports or the MariaDB port.
                    if (config.sites.any { it.port == portNum } || config.maria.port == portNum) {
                        errorText = context.getString(R.string.ftp_port_conflict, portNum)
                        return@NekoTextButton
                    }
                    if (config.sites.any { it.port in pasvNum..(pasvNum + 32) } || config.maria.port in pasvNum..(pasvNum + 32)) {
                        errorText = context.getString(R.string.ftp_pasv_conflict)
                        return@NekoTextButton
                    }
                    // Without chroot (Android forbids chroot(2) for app uids)
                    // an FTP login can read everything below its root: refuse
                    // roots that would expose config.json / the MariaDB data /
                    // the runtime binaries. ServerManager re-checks this on
                    // every start as a second line of defense.
                    val badRoot = extras.map { it.root }.firstOrNull { manager.isSensitiveFtpRoot(it) }
                    if (badRoot != null) {
                        errorText = context.getString(R.string.ftp_account_root_sensitive, badRoot)
                        return@NekoTextButton
                    }
                    errorText = null
                    val newFtp = ftp.copy(
                        username = primaryName,
                        password = password.text.ifEmpty { ftp.password },
                        port = portNum,
                        passivePortStart = pasvNum,
                        accounts = extras,
                    )
                    val wasRunning = state.ftpRunning
                    manager.updateFtp(newFtp)
                    if (wasRunning) manager.startFtpAsync()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Editable copy of an extra FTP account plus its stable composition key. */
private data class AccountDraft(val key: String, val account: FtpAccount)

@Composable
private fun AccountEditor(
    account: FtpAccount,
    onChange: (FtpAccount) -> Unit,
    onRemove: () -> Unit,
) {
    // Local editing state initialized once; the parent list only receives the
    // final values (keying remember on the text itself would reset the cursor
    // on every keystroke).
    var username by remember { mutableStateOf(TextFieldValue(account.username)) }
    var password by remember { mutableStateOf(TextFieldValue(account.password)) }
    var root by remember { mutableStateOf(TextFieldValue(account.root)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NekoTextButton(
                text = stringResource(R.string.ftp_delete_account),
                onClick = onRemove,
            )
        }
        LabeledField(
            label = stringResource(R.string.ftp_username),
            value = username,
            onValueChange = {
                username = it
                onChange(account.copy(username = it.text))
            },
            keyboardType = null,
        )
        LabeledField(
            label = stringResource(R.string.ftp_password),
            value = password,
            onValueChange = {
                password = it
                onChange(account.copy(password = it.text))
            },
            keyboardType = null,
            trailing = {
                NekoTextButton(
                    text = stringResource(R.string.ftp_generate),
                    onClick = {
                        val generated = randomPassword()
                        password = TextFieldValue(generated)
                        onChange(account.copy(password = generated))
                    },
                )
            },
        )
        LabeledField(
            label = stringResource(R.string.ftp_account_root),
            value = root,
            onValueChange = {
                root = it
                onChange(account.copy(root = it.text))
            },
            keyboardType = null,
        )
    }
}

/**
 * A text field whose label stays visible above the value. The old
 * `useLabelAsPlaceholder` style hid the field's meaning once text was
 * entered ("如果不把数值全删掉，压根不知道键是什么").
 */
@Composable
private fun LabeledField(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    keyboardType: KeyboardType?,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (trailing == null) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            useLabelAsPlaceholder = false,
            singleLine = true,
            keyboardOptions = keyboardType?.let { KeyboardOptions(keyboardType = it) } ?: KeyboardOptions.Default,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                label = label,
                useLabelAsPlaceholder = false,
                singleLine = true,
                keyboardOptions = keyboardType?.let { KeyboardOptions(keyboardType = it) } ?: KeyboardOptions.Default,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
    }
}

private fun randomPassword(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    val random = SecureRandom()
    return (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}
