package io.github.masgzy.nekoport.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.ui.component.FloatingBottomBarMode
import io.github.masgzy.nekoport.ui.component.FloatingGlassBottomBar
import io.github.masgzy.nekoport.ui.component.FloatingGlassBottomBarIcon
import io.github.masgzy.nekoport.ui.component.FloatingGlassBottomBarLabel
import io.github.masgzy.nekoport.ui.pages.FilesPage
import io.github.masgzy.nekoport.ui.pages.FtpPage
import io.github.masgzy.nekoport.ui.pages.LogsPage
import io.github.masgzy.nekoport.ui.pages.SettingsPage
import io.github.masgzy.nekoport.ui.pages.SitesPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Bottom bar tab: miuix icons where available, official Material Symbols otherwise. */
private sealed class NekoTabIcon {
    data class Vector(val vector: androidx.compose.ui.graphics.vector.ImageVector) : NekoTabIcon()
    data class Resource(val res: Int) : NekoTabIcon()
}

private enum class NekoTab(
    @StringRes val title: Int,
    val icon: NekoTabIcon,
) {
    SITES(R.string.tab_sites, NekoTabIcon.Resource(R.drawable.ic_tab_web)),
    FTP(R.string.tab_ftp, NekoTabIcon.Resource(R.drawable.ic_tab_ftp)),
    FILES(R.string.tab_files, NekoTabIcon.Vector(MiuixIcons.Folder)),
    LOGS(R.string.tab_logs, NekoTabIcon.Vector(MiuixIcons.Notes)),
    SETTINGS(R.string.tab_settings, NekoTabIcon.Vector(MiuixIcons.Settings)),
}

/**
 * Root of the app: pages live in a horizontal pager and the floating liquid
 * glass bottom bar samples the content behind it through a `layerBackdrop`.
 * The pager intentionally does NOT apply the scaffold bottom padding, so the
 * glass bar really sits on top of scrolling content; pages receive
 * [pagePadding] so their lists and floating buttons clear the bar.
 */
@Composable
fun AppRoot(dark: Boolean) {
    val tabs = NekoTab.entries
    val pagerState = rememberPagerState(initialPage = 0) { tabs.size }
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomPadding = navInset + 96.dp

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(tabs[pagerState.currentPage].title),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Only the top bar inset is applied; the bottom is left open for
                // the floating glass bar to sample the content behind it.
                .padding(
                    top = scaffoldPadding.calculateTopPadding(),
                    start = scaffoldPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = scaffoldPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                ),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) { index ->
                val pagePadding = PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = contentBottomPadding,
                )
                when (NekoTab.entries[index]) {
                    NekoTab.SITES -> SitesPage(pagePadding)
                    NekoTab.FTP -> FtpPage(pagePadding)
                    NekoTab.FILES -> FilesPage(pagePadding)
                    NekoTab.LOGS -> LogsPage(pagePadding)
                    NekoTab.SETTINGS -> SettingsPage(pagePadding)
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp + navInset),
                contentAlignment = Alignment.BottomCenter,
            ) {
                FloatingGlassBottomBar(
                    items = tabs,
                    selectedIndex = { pagerState.currentPage },
                    onSelected = { index ->
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    backdrop = backdrop,
                    mode = FloatingBottomBarMode.LiquidGlass,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    iconContent = { tab, index, previewSelected ->
                        val isSelected = previewSelected || pagerState.currentPage == index
                        when (val icon = tab.icon) {
                            is NekoTabIcon.Vector -> FloatingGlassBottomBarIcon(
                                selected = isSelected,
                                selectedIcon = icon.vector,
                                unselectedIcon = icon.vector,
                            )
                            is NekoTabIcon.Resource -> TabPainterIcon(
                                res = icon.res,
                                selected = isSelected,
                            )
                        }
                    },
                    labelContent = { tab, index ->
                        FloatingGlassBottomBarLabel(
                            label = stringResource(tab.title),
                            selected = pagerState.currentPage == index,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun TabPainterIcon(res: Int, selected: Boolean) {
    val localColor = io.github.masgzy.nekoport.ui.component.LocalFloatingBottomBarContentColor.current
    val tint = when {
        localColor != androidx.compose.ui.graphics.Color.Unspecified -> localColor
        selected -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Icon(
        painter = painterResource(res),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(24.dp),
    )
}
