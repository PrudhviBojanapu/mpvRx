/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.liquidglass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.ui.player.controls.PlayerButtonAlpha
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlin.math.abs

val LocalKyantPlayerBackdrop = staticCompositionLocalOf<Any?> { null }
internal val LocalPlayerHdrOutputEnabled = staticCompositionLocalOf { false }
internal val LocalPlayerLiquidGlassActive = staticCompositionLocalOf<Boolean?> { null }

@Composable
fun liquidGlassActive(): Boolean = false

object LiquidControlColors {
  val accent: Color
    @Composable get() = MaterialTheme.colorScheme.primary
  val content: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
  val disabledContent: Color
    @Composable get() = content.copy(alpha = PlayerButtonAlpha.DISABLED_CONTENT)
  val surface: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = PlayerButtonAlpha.GLASS_CONTAINER)
  val selectedSurface: Color
    @Composable get() = accent.copy(alpha = PlayerButtonAlpha.SELECTED_GLASS_CONTAINER)
  val inactiveTrack: Color
    @Composable get() = content.copy(alpha = 0.2f)
}

data class LiquidGlassSettings(
  val opacity: Float = 1f,
  val blur: Float = 1f,
  val refractionHeight: Float = 1f,
  val refractionAmount: Float = 1f,
  val depthEffect: Boolean = false,
  val chromaticAberration: Boolean = false,
  val vibrancy: Boolean = false,
  val saturation: Float = 1f,
  val brightness: Float = 0f,
  val contrast: Float = 1f,
  val highlightStyle: String = "Component",
  val highlightStrength: Float = 1f,
  val highlightWidth: Float = 1f,
  val highlightBlur: Float = 1f,
) {
  fun surfaceColor(base: Color): Color = base
}

@Composable
fun rememberLiquidGlassSettings(): LiquidGlassSettings = remember { LiquidGlassSettings() }

fun DrawScope.drawShapeTint(shape: Shape, color: Color) {
  drawOutline(shape.createOutline(size, layoutDirection, this), color = color)
}

fun DrawScope.drawShapeTint(shape: Shape, brush: Brush) {
  drawOutline(shape.createOutline(size, layoutDirection, this), brush = brush)
}

fun DrawScope.drawCapsuleTint(color: Color) {
  drawRoundRect(color = color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f))
}


/** Simple clean drop shadow outside the clipped shape bounds. */
fun Modifier.outsideShadow(
  shape: Shape,
  radius: Dp,
  color: Color = Color.Black.copy(alpha = 0.18f),
): Modifier = this

class DampedDragAnimation(
  val animationScope: CoroutineScope,
  initialValue: Float,
  val valueRange: ClosedFloatingPointRange<Float>,
  val visibilityThreshold: Float,
  val initialScale: Float = 1f,
  val pressedScale: Float = 1.08f,
  val onDragStarted: () -> Unit = {},
  val onDragStopped: () -> Unit = {},
  val onDrag: (offset: Offset, value: Float) -> Unit = { _, _ -> },
) {
  private val valueAnimationSpec = spring(1f, 1500f, visibilityThreshold)
  private val scaleAnimationSpec = spring(0.5f, 400f, 0.001f)
  private val velocityAnimationSpec = spring(0.5f, 400f, 0.001f)

  val valueAnimation = Animatable(initialValue, visibilityThreshold)
  val scaleAnimation = Animatable(initialScale, 0.001f)
  val pressProgressAnimation = Animatable(0f, 0.001f)
  val velocityAnimation = Animatable(0f, 0.001f)

  val value: Float get() = valueAnimation.value
  val scaleX: Float get() = scaleAnimation.value
  val scaleY: Float get() = scaleAnimation.value
  val pressProgress: Float get() = pressProgressAnimation.value
  val velocity: Float get() = velocityAnimation.value

  val progress: Float
    get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)

  private val velocityTracker = VelocityTracker()
  private var isPressed = false
  private var isDragging = false
  private var velocityJob: Job? = null

  fun press() {
    isPressed = true
    animationScope.launch {
      launch { scaleAnimation.animateTo(pressedScale, scaleAnimationSpec) }
      launch { pressProgressAnimation.animateTo(1f, scaleAnimationSpec) }
    }
  }

  fun release() {
    isPressed = false
    animationScope.launch {
      launch { scaleAnimation.animateTo(initialScale, scaleAnimationSpec) }
      launch { pressProgressAnimation.animateTo(0f, scaleAnimationSpec) }
    }
  }

  fun updateValue(targetValue: Float) {
    if (!isDragging) {
      animationScope.launch {
        valueAnimation.snapTo(targetValue.coerceIn(valueRange))
      }
    }
  }
}
