package io.github.masgzy.nekoport.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.ui.component.animation.pressFeedback
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * App-wide text button wrapper: identical to miuix TextButton but with the
 * global press feedback (scale 0.96 compression + deep inner shadow on
 * press, spring overshoot on release).
 */
@Composable
fun NekoTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier.pressFeedback(cornerRadius = 100.dp),
        enabled = enabled,
    )
}
