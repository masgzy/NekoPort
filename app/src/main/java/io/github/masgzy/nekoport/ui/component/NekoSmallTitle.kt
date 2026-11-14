package io.github.masgzy.nekoport.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Section header used across pages.
 *
 * miuix's [SmallTitle] defaults to 28dp horizontal padding (which stacks with
 * the page padding and makes headers look oddly indented) and a pale variant
 * color (users found "服务器组件 / 关于" too faint). This wrapper pins the
 * header to the same left edge as card content and uses the strong
 * onSurface color.
 */
@Composable
fun NekoSmallTitle(text: String, modifier: Modifier = Modifier) {
    SmallTitle(
        text = text,
        modifier = modifier,
        textColor = MiuixTheme.colorScheme.onSurface,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    )
}
