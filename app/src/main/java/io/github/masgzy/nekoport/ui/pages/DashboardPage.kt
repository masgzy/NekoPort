package io.github.masgzy.nekoport.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.NetUtils
import io.github.masgzy.nekoport.core.PhpMirror
import io.github.masgzy.nekoport.core.ServerService
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.stringResource

@Composable
fun DashboardPage(contentPadding: PaddingValues) {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val context = LocalContext.current
    val colorScheme = MiuixTheme.colorScheme

    var addresses by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        while (true) {
            addresses = NetUtils.localAddresses()
            manager.refresh()
            delay(3000)
        }
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Master switch
        item {
            Card {
                SwitchPreference(
                    title = stringResource(R.string.home_master_switch),
                    summary = stringResource(R.string.home_master_switch_summary),
                    checked = state.running,
                    onCheckedChange = { on ->
                        if (on) ServerService.start(context) else ServerService.stop(context)
                    },
                )
            }
        }

        // Network addresses
        item {
            Card {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Icon(
                        imageVector = MiuixIcons.Link,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "  " + stringResource(R.string.home_network),
                        color = colorScheme.onSurface,
                        style = MiuixTheme.textStyles.title2,
                    )
                }
                if (addresses.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_network_empty),
                        color = colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.main,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    val runningSites = state.sites.filter { it.running }
                    addresses.forEach { ip ->
                        val firstPort = runningSites.firstOrNull()?.site?.port
                        if (firstPort != null) {
                            ArrowPreference(
                                title = "http://$ip:$firstPort/",
                                summary = stringResource(R.string.home_open_browser),
                                onClick = {
                                    runCatching {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("http://$ip:$firstPort/"),
                                        )
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    }
                                },
                            )
                        } else {
                            Text(
                                text = ip,
                                color = colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.main,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        // Web status
        item {
            Card {
                StatusRow(
                    title = stringResource(R.string.home_http_card),
                    value = if (state.sites.any { it.running }) {
                        stringResource(R.string.home_http_status_running, state.sites.count { it.running })
                    } else {
                        stringResource(R.string.home_http_status_stopped)
                    },
                    running = state.sites.any { it.running },
                )
            }
        }

        // FTP status
        item {
            Card {
                StatusRow(
                    title = stringResource(R.string.home_ftp_card),
                    value = if (state.ftpRunning) {
                        stringResource(R.string.home_ftp_status_running, config.ftp.port)
                    } else {
                        stringResource(R.string.home_ftp_status_disabled)
                    },
                    running = state.ftpRunning,
                )
            }
        }

        // PHP status
        item {
            Card {
                val installed = config.settings.installedPhpVersion
                StatusRow(
                    title = stringResource(R.string.home_php_card),
                    value = installed?.let { stringResource(R.string.home_php_version, it) }
                        ?: stringResource(R.string.home_php_not_installed),
                    running = installed != null,
                )
                if (installed != null && !PhpMirror.isSafe(installed)) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Icon(
                            imageVector = MiuixIcons.Info,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "  " + stringResource(R.string.home_php_outdated_warning, PhpMirror.RECOMMENDED),
                            color = colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.main,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Sites overview
        item {
            Card {
                Text(
                    text = stringResource(R.string.home_site_card),
                    color = colorScheme.onSurface,
                    style = MiuixTheme.textStyles.title2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (state.sites.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_no_sites),
                        color = colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.main,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    state.sites.forEach { runtime ->
                        SwitchPreference(
                            title = "${runtime.site.name} :${runtime.site.port}",
                            summary = if (runtime.running) {
                                stringResource(R.string.site_status_running)
                            } else {
                                stringResource(R.string.site_status_stopped)
                            },
                            checked = runtime.running,
                            onCheckedChange = { on ->
                                if (on) manager.startSite(runtime.site) else manager.stopSite(runtime.site.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(title: String, value: String, running: Boolean) {
    val colorScheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            color = colorScheme.onSurface,
            style = MiuixTheme.textStyles.title2,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = if (running) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.main,
        )
    }
}
