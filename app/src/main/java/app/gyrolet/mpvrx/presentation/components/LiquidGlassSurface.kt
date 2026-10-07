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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.liquidglass.rememberLiquidGlassOptics
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
  content: @Composable BoxScope.() -> Unit,
) {
  val optics = rememberLiquidGlassOptics()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val refractionHeight = if (style == LiquidGlassStyle.MiniPlayer) 18.dp else 14.dp
  val refractionAmount = if (style == LiquidGlassStyle.MiniPlayer) 26.dp else 22.dp
  val shadowElevation: Dp = if (style == LiquidGlassStyle.MiniPlayer) 10.dp else 8.dp

  val surfaceModifier =
    if (backdrop != null) {
      modifier
        .shadow(shadowElevation, shape)
        .clip(shape)
        .drawBackdrop(
          backdrop = backdrop,
          shape = { Capsule() },
          effects = {
            vibrancy()
            blur(optics.blurRadius.toPx())
            if (!reducedMotion && optics.refractionStrength > 0f) {
              lens(
                refractionHeight.toPx() * optics.refractionStrength,
                refractionAmount.toPx() * optics.refractionStrength,
                chromaticAberration = optics.chromaticAberration,
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
            drawRect(
              fallbackColor.copy(
                alpha = (0.08f * optics.tintStrength).coerceIn(0f, 1f),
              ),
            )
            drawRect(
              glassColor.copy(
                alpha = (glassColor.alpha * optics.tintStrength).coerceIn(0f, 1f),
              ),
            )
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
