/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.liquidglass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import org.koin.compose.koinInject

@Immutable
data class LiquidGlassOptics(
  val blurRadius: Dp,
  val refractionStrength: Float,
  val tintStrength: Float,
  val chromaticAberration: Boolean,
)

/** Single source of truth for the user-adjustable Kyant optical parameters. */
@Composable
fun rememberLiquidGlassOptics(): LiquidGlassOptics {
  val preferences = koinInject<AppearancePreferences>()
  val blurRadius by preferences.liquidGlassBlurRadius.collectAsState()
  val refractionStrength by preferences.liquidGlassRefractionStrength.collectAsState()
  val tintStrength by preferences.liquidGlassTintStrength.collectAsState()
  val chromaticAberration by preferences.liquidGlassChromaticAberration.collectAsState()

  return remember(blurRadius, refractionStrength, tintStrength, chromaticAberration) {
    LiquidGlassOptics(
      blurRadius = blurRadius.coerceIn(0f, 24f).dp,
      refractionStrength = refractionStrength.coerceIn(0f, 2f),
      tintStrength = tintStrength.coerceIn(0f, 2f),
      chromaticAberration = chromaticAberration,
    )
  }
}
