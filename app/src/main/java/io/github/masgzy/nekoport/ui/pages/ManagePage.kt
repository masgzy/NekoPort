package io.github.masgzy.nekoport.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.Engine
import io.github.masgzy.nekoport.runtime.ConfigWriter
import io.github.masgzy.nekoport.runtime.RuntimePackage
import io.github.masgzy.nekoport.ui.AppRoot
import io.github.masgzy.nekoport.ui.NekoTab
import io.github.masgzy.nekoport.ui.NekoNav
import io.github.masgzy.nekoport.ui.OverlayBus
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Manage tab: merges the previous MySQL / FTP / scheduled-task tabs into one
 * surface that also manages the three HTTP engines and PHP, following the
 * classic component-page layout (enable switch, component settings, config
 * file, log file) in a single scrolling list.
 */
@Composable
fun ManagePage(contentPadding: PaddingValues) {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Dashboard component rows jump straight to their panel.
    val scrollTarget by NekoNav.manageScrollTarget.collectAsState()
    LaunchedEffect(scrollTarget) {
        val target = scrollTarget ?: return@LaunchedEffect
        listState.scrollToItem(target)
        NekoNav.consumeManageScrollTarget()
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "engines-header") {
            SmallTitle(text = stringResource(R.string.manage_engines))
        }
        item(key = "engine-lighttpd") {
            EnginePanel(Engine.LIGHTTPD)
        }
        item(key = "engine-nginx") {
            EnginePanel(Engine.NGINX)
        }
        item(key = "engine-apache") {
            EnginePanel(Engine.APACHE)
        }
        item(key = "php") {
            PhpPanel()
        }
        item(key = "mysql") {
            SmallTitle(text = stringResource(R.string.maria_component_title))
            MariaPanel()
        }
        item(key = "ftp") {
            SmallTitle(text = stringResource(R.string.manage_ftp))
            FtpPanel()
        }
        item(key = "cron") {
            SmallTitle(text = stringResource(R.string.manage_cron))
            CronPanel()
        }
    }
}

/**
 * Per-engine management panel: every site served by this engine with its
 * start/stop switch, plus the per-site config and log entries.
 */
@Composable
private fun EnginePanel(engine: Engine) {
    val manager = NekoApp.get().serverManager
    val state by manager.state.collectAsState()
    val config by manager.config.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val colorScheme = MiuixTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val sites = config.sites.filter { it.engine == engine }
    val version = state.runtime.versions[
        when (engine) {
            Engine.LIGHTTPD -> RuntimePackage.LIGHTTPD
            Engine.NGINX -> RuntimePackage.NGINX
            Engine.APACHE -> RuntimePackage.APACHE
        }
    ]

    Card {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = engine.displayName,
                color = colorScheme.onSurface,
                style = MiuixTheme.textStyles.title2,
            )
            Text(
                text = version?.let { "v$it" } ?: stringResource(R.string.runtime_not_installed),
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (sites.isEmpty()) {
            Text(
                text = stringResource(R.string.manage_engine_no_sites, engine.displayName),
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TextButton(
                text = stringResource(R.string.manage_add_site),
                onClick = { NekoNav.goToTab(AppRoot.tabIndexOf(NekoTab.SITES)) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else {
            sites.forEach { site ->
                val runtime = state.sites.firstOrNull { it.site.id == site.id }
                val running = runtime?.running ?: false
                SwitchPreference(
                    title = "${site.name} :${site.port}",
                    summary = buildString {
                        if (site.enablePhp) append("PHP · ")
                        append(
                            if (running) stringResource(R.string.site_status_running)
                            else (runtime?.statusText ?: stringResource(R.string.site_status_stopped))
                        )
                    },
                    checked = running,
                    onCheckedChange = { on ->
                        if (on) manager.startSiteAsync(site) else manager.stopSiteAsync(site.id)
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.site_edit_conf),
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
}

/** PHP panel (moved out of the sites tab): php.ini / php-fpm.conf / error log. */
@Composable
private fun PhpPanel() {
    val manager = NekoApp.get().serverManager
    val config by manager.config.collectAsState()
    val state by manager.state.collectAsState()
    val colorScheme = MiuixTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val version = state.runtime.versions[RuntimePackage.PHP_FPM]

    Card {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.php_title),
                color = colorScheme.onSurface,
                style = MiuixTheme.textStyles.title2,
            )
            Text(
                text = if (state.phpFpmRunning) {
                    "v${version ?: "?"} · " + stringResource(R.string.site_status_running)
                } else {
                    version?.let { "v$it" } ?: stringResource(R.string.php_not_installed)
                },
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        ArrowPreference(
            title = "php.ini",
            summary = stringResource(R.string.php_edit_ini),
            onClick = {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    ConfigWriter.writePhpIni(context)
                    val file = ConfigWriter.ensureUserCopy(ConfigWriter.phpIniAuto(context))
                    OverlayBus.openEditor(
                        EditorTarget(
                            title = "php.ini",
                            file = file,
                            resetAction = { ConfigWriter.resetToAuto(ConfigWriter.phpIniAuto(context)) },
                        ),
                    )
                }
            },
        )
        ArrowPreference(
            title = "php-fpm.conf",
            summary = stringResource(R.string.php_edit_fpm_conf),
            onClick = {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    ConfigWriter.writePhpFpmConf(context, config.sites)
                    val file = ConfigWriter.ensureUserCopy(ConfigWriter.phpFpmConfAuto(context))
                    OverlayBus.openEditor(
                        EditorTarget(
                            title = "php-fpm.conf",
                            file = file,
                            resetAction = { ConfigWriter.resetToAuto(ConfigWriter.phpFpmConfAuto(context)) },
                        ),
                    )
                }
            },
        )
        ArrowPreference(
            title = stringResource(R.string.php_open_log),
            onClick = {
                OverlayBus.openEditor(
                    EditorTarget(
                        title = "php-fpm-error.log",
                        file = ConfigWriter.logsDir(context).resolve("php-fpm-error.log"),
                        readOnly = true,
                    ),
                )
            },
        )
    }
}
