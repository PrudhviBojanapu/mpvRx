/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.liquidglass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

val LocalLiquidControlGroup = staticCompositionLocalOf { false }

object PlayerLiquidTokens {
  val ButtonSize: Dp = 40.dp
  val CenterButtonSize: Dp = 72.dp
  val IconSize: Dp = 22.dp
  val CenterIconSize: Dp = 34.dp
  val PillHeight: Dp = 40.dp

  val contentColor: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface

  val disabledContentColor: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

  val selectedContentColor: Color
    @Composable get() = MaterialTheme.colorScheme.primary

  val surfaceColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.32f)

  val selectedSurfaceColor: Color
    @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
}

@Composable
fun LiquidIconButton(
  icon: AppIcon,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: () -> Unit = {},
  title: String? = null,
  tint: Color = PlayerLiquidTokens.contentColor,
  surfaceColor: Color = PlayerLiquidTokens.surfaceColor,
  size: Dp = PlayerLiquidTokens.ButtonSize,
  iconSize: Dp = PlayerLiquidTokens.IconSize,
  spacing: Dp = 8.dp,
  useGlass: Boolean = true,
  backdrop: Backdrop? = LocalKyantPlayerBackdrop.current,
) {
  CompositionLocalProvider(LocalContentColor provides tint) {
    LiquidPillButton(
      onClick = onClick,
      onLongClick = onLongClick,
      modifier = modifier.requiredSize(size),
      tint = tint,
      surfaceColor = surfaceColor,
      height = size,
      spacing = spacing,
      horizontalPadding = 0.dp,
      useGlass = useGlass,
      backdrop = backdrop,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = title,
        tint = tint,
        modifier = Modifier.size(iconSize),
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiquidPillButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: () -> Unit = {},
  isInteractive: Boolean = true,
  tint: Color = PlayerLiquidTokens.contentColor,
  surfaceColor: Color = PlayerLiquidTokens.surfaceColor,
  height: Dp = PlayerLiquidTokens.PillHeight,
  spacing: Dp = 8.dp,
  horizontalPadding: Dp = 16.dp,
  useGlass: Boolean = true,
  backdrop: Backdrop? = LocalKyantPlayerBackdrop.current,
  content: @Composable RowScope.() -> Unit,
) {
  val resolvedTint = if (tint.isSpecified) tint else PlayerLiquidTokens.contentColor
  val resolvedSurface = if (surfaceColor.isSpecified) surfaceColor else PlayerLiquidTokens.surfaceColor
  val renderOwnGlass = useGlass && !LocalLiquidControlGroup.current
  CompositionLocalProvider(LocalContentColor provides resolvedTint) {
    if (renderOwnGlass && backdrop != null) {
      LiquidButton(
        onClick = onClick,
        onLongClick = onLongClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = isInteractive,
        surfaceColor = resolvedSurface,
        height = height,
        horizontalPadding = horizontalPadding,
        spacing = spacing,
        content = content,
      )
    } else {
      val interactionSource = remember { MutableInteractionSource() }
      Row(
        modifier =
          modifier
            .height(height)
            .clip(CircleShape)
            .combinedClickable(
              enabled = isInteractive,
              interactionSource = interactionSource,
              indication = ripple(),
              role = Role.Button,
              onClick = onClick,
              onLongClick = onLongClick,
            ).padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
      )
    }
  }
}

/**
 * One continuous Kyant surface for a row of player actions. Child buttons retain independent hit
 * targets but do not stack separate glass layers over the shared video-reflecting capsule.
 */
@Composable
fun LiquidControlGroup(
  modifier: Modifier = Modifier,
  backdrop: Backdrop? = LocalKyantPlayerBackdrop.current,
  horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(2.dp),
  content: @Composable RowScope.() -> Unit,
) {
  val optics = rememberLiquidGlassOptics()
  val surfaceColor = PlayerLiquidTokens.surfaceColor
  val groupedModifier =
    if (backdrop != null) {
      modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { Capsule() },
        effects = {
          vibrancy()
          blur(optics.blurRadius.toPx())
          if (optics.refractionStrength > 0f) {
            lens(
              14.dp.toPx() * optics.refractionStrength,
              28.dp.toPx() * optics.refractionStrength,
              chromaticAberration = optics.chromaticAberration,
            )
          }
        },
        highlight = {
          Highlight.Ambient.copy(alpha = 0.62f)
        },
        shadow = {
          Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.20f))
        },
        innerShadow = {
          InnerShadow(radius = 2.dp, color = Color.White.copy(alpha = 0.16f))
        },
        onDrawSurface = {
          drawRect(
            surfaceColor.copy(
              alpha = (surfaceColor.alpha * optics.tintStrength).coerceIn(0f, 1f),
            ),
          )
        },
      )
    } else {
      modifier.clip(CircleShape).background(
        surfaceColor.copy(
          alpha = (surfaceColor.alpha * optics.tintStrength).coerceIn(0f, 1f),
        ),
      )
    }

  CompositionLocalProvider(LocalLiquidControlGroup provides true) {
    Row(
      modifier = groupedModifier.heightIn(min = 53.dp).padding(horizontal = 4.dp, vertical = 4.dp),
      horizontalArrangement = horizontalArrangement,
      verticalAlignment = Alignment.CenterVertically,
      content = content,
    )
  }
}

@Composable
fun LiquidActionRow(
  modifier: Modifier = Modifier,
  contentColor: Color = PlayerLiquidTokens.contentColor,
  content: @Composable RowScope.() -> Unit,
) {
  CompositionLocalProvider(LocalContentColor provides contentColor) {
    Row(modifier = modifier, content = content)
  }
}
