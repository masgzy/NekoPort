package io.github.masgzy.nekoport.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.CronAction
import io.github.masgzy.nekoport.core.CronParser
import io.github.masgzy.nekoport.core.CronTask
import io.github.masgzy.nekoport.runtime.ConfigWriter
import io.github.masgzy.nekoport.ui.OverlayBus
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scheduled-task panel, embedded in the Manage tab. The add-task FAB of the
 * old standalone tab becomes a plain action row here.
 */
@Composable
fun CronPanel() {
    val manager = NekoApp.get().serverManager
    val config by manager.config.collectAsState()
    val cron = config.cron
    val colorScheme = MiuixTheme.colorScheme
    var showEditor by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<CronTask?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Master switch
        Card {
            SwitchPreference(
                title = stringResource(R.string.cron_enable),
                summary = stringResource(R.string.cron_enable_summary),
                checked = cron.enabled,
                onCheckedChange = { manager.updateCron(cron.copy(enabled = it)) },
            )
        }

        // Logging
        SmallTitle(text = stringResource(R.string.cron_logs))
        Card {
            SwitchPreference(
                title = stringResource(R.string.cron_log_enabled),
                checked = cron.logEnabled,
                onCheckedChange = { manager.updateCron(cron.copy(logEnabled = it)) },
            )
            ArrowPreference(
                title = stringResource(R.string.cron_open_log),
                onClick = {
                    val log = ConfigWriter.cronLog(NekoApp.get())
                    if (!log.exists()) {
                        runCatching {
                            log.parentFile?.mkdirs()
                            log.writeText("")
                        }
                    }
                    OverlayBus.openEditor(
                        EditorTarget(title = "cron.log", file = log, readOnly = true),
                    )
                },
            )
        }

        // Task list
        SmallTitle(
            text = stringResource(R.string.cron_tasks) +
                " (${cron.tasks.count { it.enabled }}/${cron.tasks.size})",
        )
        if (cron.tasks.isEmpty()) {
            Card {
                Text(
                    text = stringResource(R.string.cron_empty),
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.main,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            cron.tasks.forEach { task ->
                Card {
                    SwitchPreference(
                        title = task.name,
                        summary = "${task.expr} · ${cronActionLabel(task.action)}" +
                            if (task.enabled) "" else " · " + stringResource(R.string.cron_task_disabled),
                        checked = task.enabled,
                        onCheckedChange = { on ->
                            manager.updateCron(
                                cron.copy(
                                    tasks = cron.tasks.map {
                                        if (it.id == task.id) it.copy(enabled = on) else it
                                    },
                                ),
                            )
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.cron_edit_task),
                        onClick = { editingTask = task },
                    )
                }
            }
        }

        // Add task (was the FAB of the standalone tab)
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.cron_add),
                onClick = { showEditor = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showEditor) {
        TaskEditorDialog(task = null, onDismiss = { showEditor = false })
    }
    editingTask?.let { task ->
        TaskEditorDialog(task = task, onDismiss = { editingTask = null })
    }
}

@Composable
private fun cronActionLabel(action: CronAction): String = stringResource(
    when (action) {
        CronAction.COMMAND -> R.string.cron_action_command
        CronAction.RESTART_SERVICES -> R.string.cron_action_restart
        CronAction.KEEP_ALIVE -> R.string.cron_action_keepalive
    },
)

@Composable
private fun TaskEditorDialog(task: CronTask?, onDismiss: () -> Unit) {
    val manager = NekoApp.get().serverManager
    val config by manager.config.collectAsState()
    val cron = config.cron
    val context = LocalContext.current
    val isNew = task == null

    var name by remember { mutableStateOf(TextFieldValue(task?.name ?: "")) }
    var expr by remember { mutableStateOf(TextFieldValue(task?.expr ?: "*/5 * * * *")) }
    var command by remember { mutableStateOf(TextFieldValue(task?.command ?: "")) }
    var actionIndex by remember {
        mutableStateOf(CronAction.entries.indexOfFirst { it == (task?.action ?: CronAction.COMMAND) }.coerceAtLeast(0))
    }
    var logOutput by remember { mutableStateOf(task?.logOutput ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nextRun by remember { mutableStateOf<String?>(null) }

    fun checkExpr() {
        val e = expr.text.trim()
        if (CronParser.isValid(e)) {
            val next = CronParser.nextFire(e, System.currentTimeMillis())
            nextRun = next?.let {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
            }
            error = null
        } else {
            nextRun = null
            error = ""
        }
    }

    OverlayDialog(
        show = true,
        title = if (isNew) stringResource(R.string.cron_add) else stringResource(R.string.cron_edit_task),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.cron_task_name),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = expr,
                onValueChange = { expr = it },
                label = stringResource(R.string.cron_expr),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.cron_expr_hint),
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.cron_check_expr),
                    onClick = { checkExpr() },
                    modifier = Modifier.weight(1f),
                )
            }
            nextRun?.let {
                Text(
                    text = stringResource(R.string.cron_next_run, it),
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            OverlaySpinnerPreference(
                items = listOf(
                    stringResource(R.string.cron_action_command),
                    stringResource(R.string.cron_action_restart),
                    stringResource(R.string.cron_action_keepalive),
                ).map { DropdownItem(text = it, onClick = {}) },
                selectedIndex = actionIndex,
                title = stringResource(R.string.cron_action),
                onSelectedIndexChange = { actionIndex = it },
            )
            if (CronAction.entries[actionIndex.coerceIn(0, CronAction.entries.lastIndex)] == CronAction.COMMAND) {
                TextField(
                    value = command,
                    onValueChange = { command = it },
                    label = stringResource(R.string.cron_command),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SwitchPreference(
                title = stringResource(R.string.cron_log_output),
                summary = stringResource(R.string.cron_log_output_summary),
                checked = logOutput,
                onCheckedChange = { logOutput = it },
            )
            if (error != null && error != "") {
                Text(text = error!!, color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.main)
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
                            manager.updateCron(cron.copy(tasks = cron.tasks.filterNot { it.id == task!!.id }))
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
                        val trimmedName = name.text.trim()
                        if (!CronTask.isNameValid(trimmedName)) {
                            error = context.getString(R.string.cron_name_invalid)
                            return@TextButton
                        }
                        if (!CronParser.isValid(expr.text.trim())) {
                            error = context.getString(R.string.cron_expr_invalid)
                            return@TextButton
                        }
                        if (CronAction.entries[actionIndex.coerceIn(0, CronAction.entries.lastIndex)] == CronAction.COMMAND &&
                            command.text.isBlank()
                        ) {
                            error = context.getString(R.string.cron_command_empty)
                            return@TextButton
                        }
                        val newTask = CronTask(
                            id = task?.id ?: (cron.tasks.maxOfOrNull { it.id } ?: 0L) + 1L,
                            name = trimmedName,
                            expr = expr.text.trim(),
                            action = CronAction.entries[actionIndex.coerceIn(0, CronAction.entries.lastIndex)],
                            command = command.text.trim(),
                            logTask = true,
                            logOutput = logOutput,
                            enabled = task?.enabled ?: true,
                        )
                        manager.updateCron(
                            cron.copy(
                                tasks = if (isNew) cron.tasks + newTask
                                else cron.tasks.map { if (it.id == newTask.id) newTask else it },
                            ),
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
