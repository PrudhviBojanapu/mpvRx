/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.ContinuousRoundedRectangle

typealias LiquidGlassBackdrop = LayerBackdrop

private val LocalLiquidGlassBackdrop = staticCompositionLocalOf<LiquidGlassBackdrop?> { null }

@Composable
fun rememberLiquidGlassBackdrop(): LiquidGlassBackdrop = rememberLayerBackdrop()

fun Modifier.captureLiquidGlassBackdrop(
  backdrop: LiquidGlassBackdrop?,
  enabled: Boolean = true,
): Modifier = if (enabled && backdrop != null) layerBackdrop(backdrop) else this

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
}

/** Kyant-backed liquid glass surface shared by mini players and floating action bars. */
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
  cornerRadius: Dp? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  val reducedMotion = AppMotion.shouldReduceMotion()
  val blurRadius = if (style == LiquidGlassStyle.MiniPlayer) 12.dp else 8.dp
  val refractionHeight = if (style == LiquidGlassStyle.MiniPlayer) 18.dp else 14.dp
  val refractionAmount = if (style == LiquidGlassStyle.MiniPlayer) 26.dp else 22.dp
  val shadowElevation: Dp = if (style == LiquidGlassStyle.MiniPlayer) 10.dp else 8.dp
  val glassCornerRadiusPx = with(LocalDensity.current) { cornerRadius?.toPx() }

  val surfaceModifier =
    if (backdrop != null) {
      modifier
        .shadow(shadowElevation, shape)
        .clip(shape)
        .drawBackdrop(
          backdrop = backdrop,
          shape = {
            if (glassCornerRadiusPx == null) {
              Capsule()
            } else {
              ContinuousRoundedRectangle(glassCornerRadiusPx)
            }
          },
          effects = {
            vibrancy()
            blur(blurRadius.toPx())
            if (!reducedMotion) {
              lens(
                refractionHeight.toPx(),
                refractionAmount.toPx(),
                chromaticAberration = true,
              )
            }
          },
          highlight = {
            Highlight.Ambient.copy(alpha = (if (reducedMotion) 0.28f else 0.52f) * glowStrength)
          },
          shadow = {
            Shadow(
              radius = shadowElevation,
              color = Color.Black.copy(alpha = 0.16f * glowStrength),
            )
          },
          innerShadow = {
            InnerShadow(
              radius = 2.dp,
              color = Color.White.copy(alpha = 0.14f * glowStrength),
            )
          },
          onDrawSurface = {
            drawRect(fallbackColor.copy(alpha = 0.08f))
            drawRect(glassColor)
          },
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
