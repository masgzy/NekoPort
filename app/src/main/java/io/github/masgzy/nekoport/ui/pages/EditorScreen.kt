package io.github.masgzy.nekoport.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.ui.OverlayBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * A file opened in the full-screen overlay editor. Config files are editable
 * and can be reset to the generated defaults; log files open read-only.
 */
data class EditorTarget(
    val title: String,
    val file: File,
    val readOnly: Boolean = false,
    /** Invoked after user confirmation to drop the user-edited file. */
    val resetAction: (() -> Unit)? = null,
    val maxBytes: Int = 512 * 1024,
)

/**
 * Renders the active overlay on top of everything: the config/log editor and
 * the application log page (reached from Settings).
 */
@Composable
fun OverlayHost() {
    val editor by OverlayBus.editor.collectAsState()
    val appLogs by OverlayBus.appLogs.collectAsState()
    when {
        editor != null -> EditorScreen(target = editor!!)
        appLogs -> AppLogScreen()
    }
}

@Composable
private fun OverlayScaffold(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(10.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = stringResource(R.string.dialog_cancel),
                        tint = colorScheme.onSurface,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                ) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.title2,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MiuixTheme.textStyles.subtitle,
                            color = colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
                actions()
            }
            content()
        }
    }
}

@Composable
private fun EditorScreen(target: EditorTarget) {
    val colorScheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    var text by remember(target.file.path) { mutableStateOf(TextFieldValue("")) }
    var loaded by remember(target.file.path) { mutableStateOf(false) }
    var dirty by remember(target.file.path) { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(target.file.path, target.readOnly) {
        val content = withContext(Dispatchers.IO) {
            runCatching {
                val file = target.file
                if (!file.exists()) {
                    ""
                } else if (file.length() > target.maxBytes) {
                    val skip = file.length() - target.maxBytes
                    file.inputStream().use { input ->
                        input.skip(skip)
                        input.bufferedReader().readText()
                    }
                } else {
                    file.readText()
                }
            }.getOrElse { "// ${it.message}" }
        }
        text = TextFieldValue(content)
        loaded = true
    }

    OverlayScaffold(
        title = target.title,
        subtitle = target.file.canonicalPath,
        onDismiss = { OverlayBus.closeEditor() },
        actions = {
            if (target.resetAction != null) {
                TextButton(
                    text = stringResource(R.string.editor_reset),
                    onClick = { confirmReset = true },
                )
            }
            if (!target.readOnly) {
                TextButton(
                    text = stringResource(R.string.editor_save),
                    enabled = loaded && dirty,
                    onClick = {
                        scope.launch {
                            val value = text.text
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    target.file.parentFile?.mkdirs()
                                    target.file.writeText(value)
                                }
                            }
                            dirty = false
                        }
                    },
                )
            }
        },
    ) {
        if (target.readOnly) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                BasicText(
                    text = text.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = colorScheme.onSurface,
                    ),
                )
            }
        } else {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    dirty = true
                },
                enabled = loaded,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colorScheme.onSurface,
                ),
            )
        }
    }

    if (confirmReset) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.editor_reset),
            summary = stringResource(R.string.editor_reset_confirm),
            onDismissRequest = { confirmReset = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = { confirmReset = false },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.dialog_ok),
                    onClick = {
                        target.resetAction?.invoke()
                        confirmReset = false
                        OverlayBus.closeEditor()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AppLogScreen() {
    OverlayScaffold(
        title = stringResource(R.string.logs_title),
        onDismiss = { OverlayBus.dismissAppLogs() },
    ) {
        LogsPage(contentPadding = PaddingValues(bottom = 16.dp))
    }
}
