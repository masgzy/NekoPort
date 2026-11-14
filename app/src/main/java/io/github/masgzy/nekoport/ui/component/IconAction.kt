package io.github.masgzy.nekoport.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.masgzy.nekoport.ui.component.animation.pressFeedback

/**
 * Compact icon button with the global press feedback. Used for the per-row
 * component actions (install / update / uninstall) and the card header
 * shortcuts (edit-site pen, folder...). The icon lambda supplies its own
 * tint; [tint] is only a convenience for callers that reuse it for labels.
 */
@androidx.compose.runtime.Composable
fun IconAction(
    icon: @androidx.compose.runtime.Composable () -> Unit,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.35f
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .pressFeedback(cornerRadius = 10.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.alpha(alpha)) {
            icon()
        }
    }
}
