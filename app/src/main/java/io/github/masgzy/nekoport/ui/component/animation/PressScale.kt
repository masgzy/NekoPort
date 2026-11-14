package io.github.masgzy.nekoport.ui.component.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Global press feedback for interactive controls:
 *
 *  - pressed:  the control compresses to [pressedScale] (0.96 by default)
 *              while a deep inner shadow grows with the press progress;
 *  - released: the scale springs back to 1.0 with a slight overshoot
 *              (dampingRatio 0.55), the shadow fades away.
 *
 * The gesture observer listens on [PointerEventPass.Initial] and consumes
 * nothing, so it composes safely with `clickable` / `onClick` handlers and
 * does not break scrolling.
 */
fun Modifier.pressFeedback(
    pressedScale: Float = 0.96f,
    shadowAlpha: Float = 0.28f,
    cornerRadius: Dp = 16.dp,
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val currentPressed by rememberUpdatedState(pressed)
    val scale = remember { Animatable(1f) }
    val shadow = remember { Animatable(0f) }
    val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }

    // Damping < 1 => the release springs past 1.0 and settles back - the
    // slight overshoot ("Spring Overshoot") the press interaction calls for.
    val scaleSpec = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
    val shadowSpec = spring<Float>(dampingRatio = 1f, stiffness = 1200f)

    LaunchedEffect(currentPressed) {
        if (currentPressed) {
            launch { scale.animateTo(pressedScale, scaleSpec) }
            launch { shadow.animateTo(1f, shadowSpec) }
        } else {
            launch { scale.animateTo(1f, scaleSpec) }
            launch { shadow.animateTo(0f, shadowSpec) }
        }
    }

    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(pass = PointerEventPass.Initial)
                pressed = true
                waitForUpOrCancellation(pass = PointerEventPass.Initial)
                pressed = false
            }
        }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .drawWithContent {
            drawContent()
            val s = shadow.value
            if (s > 0.01f) {
                // Deep inner shadow: an inset dark frame growing with the press.
                val inset = size.minDimension * 0.05f * s + radiusPx * 0.06f
                drawRoundRect(
                    color = Color.Black.copy(alpha = shadowAlpha * s),
                    topLeft = Offset(inset * 0.5f, inset * 0.5f),
                    size = Size(size.width - inset, size.height - inset),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = inset),
                )
            }
        }
}
