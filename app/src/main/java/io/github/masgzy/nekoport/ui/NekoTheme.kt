package io.github.masgzy.nekoport.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * App theme built on miuix `ThemeController` (System / Light / Dark scheme modes).
 */
@Composable
fun NekoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val controller = remember(darkTheme) {
        ThemeController(if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light)
    }
    MiuixTheme(controller = controller, content = content)
}
