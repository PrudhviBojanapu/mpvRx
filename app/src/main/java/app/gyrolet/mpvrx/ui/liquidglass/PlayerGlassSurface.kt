/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.liquidglass

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

internal val LocalPlayerGlassSurfaceActive = staticCompositionLocalOf { false }

@Composable
internal fun Modifier.playerGlassSurface(
  backdrop: Any? = null,
  shape: Shape,
  containerColor: Color,
  enabled: Boolean = false,
): Modifier = if (!enabled) this else background(containerColor, shape)
