/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.presentation.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

typealias LiquidGlassBackdrop = HazeState

private val LocalLiquidGlassBackdrop = staticCompositionLocalOf<LiquidGlassBackdrop?> { null }

@Composable
fun rememberLiquidGlassBackdrop(): LiquidGlassBackdrop = rememberHazeState()

fun Modifier.captureLiquidGlassBackdrop(
  backdrop: LiquidGlassBackdrop?,
  enabled: Boolean = true,
): Modifier = if (enabled && backdrop != null) hazeSource(backdrop) else this

@Composable
fun ProvideLiquidGlassBackdrop(
  backdrop: LiquidGlassBackdrop,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalLiquidGlassBackdrop provides backdrop.takeIf { enabled },
    content = content,
  )
}

enum class LiquidGlassStyle {
  MiniPlayer,
  Navigation,
  Control,
}

/**
 * Haze-backed liquid glass surface.
 *
 * The caller-provided source state keeps the effect portable on Android versions where advanced
 * refraction is unavailable; Haze automatically simplifies unsupported optical features.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun LiquidGlassSurface(
  shape: Shape,
  modifier: Modifier = Modifier,
  style: LiquidGlassStyle = LiquidGlassStyle.Navigation,
  glassColor: Color,
  fallbackColor: Color,
  contentColor: Color = MaterialTheme.colorScheme.onSurface,
  backdrop: LiquidGlassBackdrop? = LocalLiquidGlassBackdrop.current,
  glowStrength: Float = 1f,
  content: @Composable BoxScope.() -> Unit,
) {
  val reducedMotion = AppMotion.shouldReduceMotion()
  val liquidGlassSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
  val blurRadius = if (style == LiquidGlassStyle.MiniPlayer) 12.dp else 8.dp
  val refractionHeightFraction =
    when (style) {
      LiquidGlassStyle.MiniPlayer -> 0.30f
      LiquidGlassStyle.Navigation -> 0.28f
      LiquidGlassStyle.Control -> 0.24f
    }
  val refractionAmount =
    when (style) {
      LiquidGlassStyle.MiniPlayer -> 26.dp
      LiquidGlassStyle.Navigation -> 22.dp
      LiquidGlassStyle.Control -> 18.dp
    }
  val shadowElevation: Dp =
    when (style) {
      LiquidGlassStyle.MiniPlayer -> 10.dp
      LiquidGlassStyle.Navigation -> 8.dp
      LiquidGlassStyle.Control -> 4.dp
    }
  val roundedShape = shape as? RoundedCornerShape

  val glassStyle =
    remember(roundedShape, style, glassColor, fallbackColor, reducedMotion, glowStrength) {
      roundedShape?.let { resolvedShape ->
        GlassStyle {
          shape(resolvedShape)
          tint(glassColor)
          // A very light backing tint keeps the simplified renderer readable without making the
          // normal source-backed path opaque.
          backgroundColor(fallbackColor.copy(alpha = 0.08f))
          optics(
            refractionStrength = if (reducedMotion) 0f else 0.72f,
            refractionHeightFraction = if (reducedMotion) 0f else refractionHeightFraction,
            refractionDisplacement = if (reducedMotion) 0.dp else refractionAmount,
            depth = if (reducedMotion) 0f else 0.6f,
            blurRadius = blurRadius,
            refractionDetailIntensity = if (reducedMotion) 0f else 0.5f,
          )
          // Replaces Backdrop's vibrancy/highlight/rim treatment.
          chromaMultiplier(1.08f)
          contrast(0.04f)
          specularIntensity((if (reducedMotion) 0.28f else 0.52f) * glowStrength)
          ambientResponse(0.36f * glowStrength)
          edgeSoftness(1.dp)
          edgeShadow(Color.Black.copy(alpha = 0.16f * glowStrength))
          chromaticAberrationStrength(if (reducedMotion) 0f else 0.12f * glowStrength)
        }
      }
    }

  val surfaceModifier =
    if (liquidGlassSupported && backdrop != null && roundedShape != null && glassStyle != null) {
      modifier
        .shadow(shadowElevation, shape)
        .clip(shape)
        .hazeGlass(
          input = HazeInput.Sources(backdrop),
          style = glassStyle,
        )
    } else {
      modifier
        .shadow(shadowElevation, shape)
        .clip(shape)
        .background(fallbackColor)
    }

  CompositionLocalProvider(LocalContentColor provides contentColor) {
    Box(modifier = surfaceModifier, content = content)
  }
}
