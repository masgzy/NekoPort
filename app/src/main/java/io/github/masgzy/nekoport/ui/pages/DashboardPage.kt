package io.github.masgzy.nekoport.ui.pages

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.Engine
import io.github.masgzy.nekoport.core.NetUtils
import io.github.masgzy.nekoport.core.ServerService
import io.github.masgzy.nekoport.core.SiteRuntime
import io.github.masgzy.nekoport.runtime.ConfigWriter
import io.github.masgzy.nekoport.runtime.RuntimePackage
import io.github.masgzy.nekoport.ui.AppRoot
import io.github.masgzy.nekoport.ui.NekoTab
import io.github.masgzy.nekoport.ui.NekoNav
import io.github.masgzy.nekoport.ui.OverlayBus
import io.github.masgzy.nekoport.ui.component.NekoSmallTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import io.github.masgzy.nekoport.ui.component.NekoTextButton
import io.github.masgzy.nekoport.ui.component.animation.pressFeedback
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * Dashboard (bottom bar, leftmost): a read-only status page in the spirit of
 * KSWEB's status tab - one glance shows every component's state, and every
 * site's address / folder with one-tap shortcuts. All heavy work lives in
 * the manage/sites tabs; the dashboard only navigates.
 */
@Composable
fun DashboardPage(contentPadding: PaddingValues) {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Run-all master switch: starts every enabled site + FTP + MySQL and
        // keeps them alive via the foreground-service notification.
        item {
            Card {
                SwitchPreference(
                    title = stringResource(R.string.sites_master_switch),
                    summary = stringResource(R.string.sites_master_switch_summary),
                    checked = state.running,
                    onCheckedChange = { on ->
                        if (on) {
                            ServerService.start(context)
                            manager.startAllAsync()
                        } else {
                            ServerService.stop(context)
                        }
                    },
                )
            }
        }

        // Component status: one row per bundled server.
        item {
            NekoSmallTitle(text = stringResource(R.string.dashboard_components))
            Card {
                ComponentRow(
                    pkg = RuntimePackage.LIGHTTPD,
                    version = state.runtime.versions[RuntimePackage.LIGHTTPD],
                    running = state.sites.any { it.site.engine == Engine.LIGHTTPD && it.running },
                    detail = portsOf(state, Engine.LIGHTTPD),
                    onClick = { NekoNav.scrollToManagePanel(ManagePanelIndex.LIGHTTPD) },
                )
                ComponentRow(
                    pkg = RuntimePackage.NGINX,
                    version = state.runtime.versions[RuntimePackage.NGINX],
                    running = state.sites.any { it.site.engine == Engine.NGINX && it.running },
                    detail = portsOf(state, Engine.NGINX),
                    onClick = { NekoNav.scrollToManagePanel(ManagePanelIndex.NGINX) },
                )
                ComponentRow(
                    pkg = RuntimePackage.APACHE,
                    version = state.runtime.versions[RuntimePackage.APACHE],
                    running = state.sites.any { it.site.engine == Engine.APACHE && it.running },
                    detail = portsOf(state, Engine.APACHE),
                    onClick = { NekoNav.scrollToManagePanel(ManagePanelIndex.APACHE) },
                )
                ComponentRow(
                    pkg = RuntimePackage.PHP_FPM,
                    version = state.runtime.versions[RuntimePackage.PHP_FPM],
                    running = state.phpFpmRunning,
                    detail = stringResource(R.string.dashboard_component_fastcgi),
                    onClick = { NekoNav.scrollToManagePanel(ManagePanelIndex.PHP) },
                )
                ComponentRow(
                    pkg = RuntimePackage.MARIADB,
                    version = state.runtime.versions[RuntimePackage.MARIADB],
                    running = state.mariaRunning,
                    detail = if (state.mariaRunning) "127.0.0.1:${config.maria.port}" else "",
                    onClick = { NekoNav.scrollToManagePanel(ManagePanelIndex.MYSQL) },
                )
                ComponentRow(
                    pkg = RuntimePackage.PURE_FTPD,
                    version = state.runtime.versions[RuntimePackage.PURE_FTPD],
                    running = state.ftpRunning,
                    detail = if (state.ftpRunning) "ftp://:${config.ftp.port}" else "",
                    onClick = { NekoNav.scrollToManagePanel(ManagePanelIndex.FTP) },
                )
            }
        }

        // Per-site cards: address + folder + shortcuts.
        item {
            NekoSmallTitle(text = stringResource(R.string.dashboard_sites))
        }
        if (state.sites.isEmpty()) {
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.sites_empty),
                            color = colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.main,
                        )
                        NekoTextButton(
                            text = stringResource(R.string.dashboard_go_sites),
                            onClick = { NekoNav.goToTab(AppRoot.tabIndexOf(NekoTab.SITES)) },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        } else {
            items(state.sites, key = { it.site.id }) { runtime ->
                DashboardSiteCard(runtime = runtime)
            }
        }
    }
}

private fun portsOf(state: io.github.masgzy.nekoport.core.ManagerState, engine: Engine): String =
    state.sites
        .filter { it.site.engine == engine }
        .joinToString(", ") { ":${it.site.port}" }

/** Fixed item indices of the manage page panels (for scroll targeting). */
object ManagePanelIndex {
    const val LIGHTTPD = 1
    const val NGINX = 2
    const val APACHE = 3
    const val PHP = 4
    const val MYSQL = 5
    const val FTP = 6
    const val CRON = 7
}

@Composable
private fun ComponentRow(
    pkg: RuntimePackage,
    version: String?,
    running: Boolean,
    detail: String,
    onClick: () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressFeedback(cornerRadius = 16.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        running -> Color(0xFF34C759)
                        version != null -> colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
                        else -> Color.Transparent
                    },
                ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = pkg.label,
                color = colorScheme.onSurface,
                style = MiuixTheme.textStyles.main,
            )
            Text(
                text = when {
                    version != null -> "v$version" + if (detail.isNotBlank()) " · $detail" else ""
                    else -> stringResource(R.string.runtime_not_installed)
                },
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (version == null) {
            NekoTextButton(
                text = stringResource(R.string.runtime_install),
                onClick = {
                    Toast.makeText(context, context.getString(R.string.dashboard_install_hint), Toast.LENGTH_SHORT).show()
                    NekoNav.goToTab(AppRoot.tabIndexOf(NekoTab.SETTINGS))
                },
            )
        } else {
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun DashboardSiteCard(runtime: SiteRuntime) {
    val manager = NekoApp.get().serverManager
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMore by rememberSaveable(runtime.site.id) { mutableStateOf(false) }
    val site = runtime.site
    val url = "http://localhost:${site.port}"
    val lanIp = remember(runtime.site.id) { NetUtils.localAddresses().firstOrNull() }

    Card {
        // Header: name + engine tag + status.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${site.name} :${site.port}",
                    color = colorScheme.onSurface,
                    style = MiuixTheme.textStyles.title3,
                )
                Text(
                    text = buildString {
                        append(site.engine.displayName)
                        if (site.enablePhp) append(" + PHP")
                        append(" · ")
                        append(
                            if (runtime.running) {
                                stringResource(R.string.site_status_running)
                            } else {
                                runtime.statusText ?: stringResource(R.string.site_status_stopped)
                            },
                        )
                    },
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                )
            }
            Icon(
                imageVector = MiuixIcons.Folder,
                contentDescription = stringResource(R.string.dashboard_open_files_manager),
                tint = colorScheme.primary,
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { NekoNav.openFiles(manager.siteDocRoot(site)) }
                    .padding(2.dp),
            )
        }

        // Address row (tap = open in browser; the folder icon above enters
        // the built-in file manager at the site root).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }.onFailure {
                        Toast.makeText(
                            context,
                            context.getString(R.string.dashboard_no_browser),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                text = url + if (lanIp != null) "   ·   http://$lanIp:${site.port}" else "",
                color = colorScheme.primary,
                style = MiuixTheme.textStyles.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Folder row with the three actions.
        val docRoot = manager.siteDocRoot(site)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = docRoot.canonicalPath,
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NekoTextButton(
                text = stringResource(R.string.dashboard_open_folder),
                onClick = { NekoNav.openFiles(docRoot) },
                modifier = Modifier.weight(1f),
            )
            NekoTextButton(
                text = stringResource(R.string.dashboard_edit_files),
                onClick = { NekoNav.openFiles(docRoot) },
                modifier = Modifier.weight(1f),
            )
            NekoTextButton(
                text = stringResource(R.string.site_edit),
                onClick = {
                    NekoNav.editSite(site.id)
                    NekoNav.goToTab(AppRoot.tabIndexOf(NekoTab.SITES))
                },
                modifier = Modifier.weight(1f),
            )
        }

        // "More" reveals the config/log entries (hidden by default).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            NekoTextButton(
                text = if (showMore) stringResource(R.string.dashboard_less) else stringResource(R.string.dashboard_more),
                onClick = { showMore = !showMore },
            )
        }
        if (showMore) {
            ArrowPreference(
                title = stringResource(R.string.site_edit_conf),
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val auto = ConfigWriter.writeEngineConf(context, site, manager.siteDocRoot(site))
                        val file = ConfigWriter.ensureUserCopy(auto)
                        OverlayBus.openEditor(
                            EditorTarget(
                                title = "${site.name} · ${site.engine.displayName}",
                                file = file,
                                resetAction = { ConfigWriter.resetToAuto(auto) },
                            ),
                        )
                    }
                },
            )
            ArrowPreference(
                title = stringResource(R.string.site_open_log),
                onClick = {
                    OverlayBus.openEditor(
                        EditorTarget(
                            title = "site-${site.id}-error.log",
                            file = ConfigWriter.siteErrorLog(context, site.id, site.engine),
                            readOnly = true,
                        ),
                    )
                },
            )
        }
    }
}
