package io.github.masgzy.nekoport.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.LogEntry
import io.github.masgzy.nekoport.core.LogLevel
import top.yukonga.miuix.kmp.basic.Card
import io.github.masgzy.nekoport.ui.component.NekoTextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LogColors = mapOf(
    LogLevel.INFO to "#3b82f6",
    LogLevel.ACCESS to "#6b7280",
    LogLevel.WARN to "#d97706",
    LogLevel.ERROR to "#dc2626",
)

@Composable
fun LogsPage(contentPadding: PaddingValues) {
    val entries by AppLog.flow.collectAsState(initial = emptyList())
    val colorScheme = MiuixTheme.colorScheme
    var filterIndex by remember { mutableIntStateOf(0) }
    var showClearDialog by remember { mutableStateOf(false) }

    val filters = listOf(
        stringResource(R.string.logs_filter_all),
        stringResource(R.string.logs_filter_access),
        stringResource(R.string.logs_filter_error),
        stringResource(R.string.logs_filter_info),
    )

    val filtered = remember(entries, filterIndex) {
        when (filterIndex) {
            1 -> entries.filter { it.level == LogLevel.ACCESS }
            2 -> entries.filter { it.level == LogLevel.ERROR || it.level == LogLevel.WARN }
            3 -> entries.filter { it.level == LogLevel.INFO }
            else -> entries
        }.takeLast(500).reversed()
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Card {
                OverlaySpinnerPreference(
                    items = filters.map { f -> DropdownItem(text = f, onClick = {}) },
                    selectedIndex = filterIndex,
                    title = stringResource(R.string.logs_title),
                    onSelectedIndexChange = { filterIndex = it },
                )
                NekoTextButton(
                    text = stringResource(R.string.logs_clear),
                    onClick = { showClearDialog = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                Card {
                    Text(
                        text = stringResource(R.string.logs_empty),
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(filtered, key = { it.time.toString() + it.tag + it.message }) { entry: LogEntry ->
                Card {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        androidx.compose.foundation.layout.Row {
                            Text(
                                text = entry.level.name,
                                color = colorScheme.primary,
                                style = MiuixTheme.textStyles.subtitle,
                            )
                            Text(
                                text = "  ${entry.tag}",
                                color = colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.subtitle,
                            )
                        }
                        Text(
                            text = entry.message,
                            color = colorScheme.onSurface,
                            style = MiuixTheme.textStyles.main,
                        )
                    }
                }
            }
        }
    }

    OverlayDialog(
        show = showClearDialog,
        title = stringResource(R.string.logs_clear),
        onDismissRequest = { showClearDialog = false },
    ) {
        NekoTextButton(
            text = stringResource(R.string.dialog_ok),
            onClick = {
                AppLog.clear()
                showClearDialog = false
            },
        )
    }
}
