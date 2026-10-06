/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics

@Composable
fun SwitchPreference(
  value: Boolean,
  onValueChange: (Boolean) -> Unit,
  title: @Composable () -> Unit,
  summary: @Composable (() -> Unit)? = null,
  icon: @Composable (() -> Unit)? = null,
  enabled: Boolean = true,
  titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
  summaryStyle: TextStyle = MaterialTheme.typography.bodyMedium,
  switchModifier: Modifier = Modifier,
  modifier: Modifier = Modifier,
) {
  val haptics = rememberAppHaptics()
  val titleColor =
    if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
  val summaryColor =
    if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(MaterialTheme.shapes.medium, enabled = enabled, focusedScale = 1.01f)
        .toggleable(value = value, enabled = enabled, role = Role.Switch) { checked ->
          if (checked != value) {
            onValueChange(checked)
            haptics.selection(checked)
          }
        }
        .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Box(
        modifier = Modifier.padding(end = 12.dp),
        contentAlignment = Alignment.Center,
      ) {
        CompositionLocalProvider(LocalContentColor provides titleColor) { icon() }
      }
    }

    Column(
      modifier =
        Modifier
          .weight(1f)
          .padding(end = 16.dp),
    ) {
      CompositionLocalProvider(LocalContentColor provides titleColor) {
        ProvideTextStyle(value = titleStyle.copy(color = titleColor)) {
          title()
        }
      }
      if (summary != null) {
        CompositionLocalProvider(LocalContentColor provides summaryColor) {
          ProvideTextStyle(value = summaryStyle.copy(color = summaryColor)) {
            summary()
          }
        }
      }
    }

    IconSwitch(
      checked = value,
      onCheckedChange = null,
      enabled = enabled,
      modifier = switchModifier,
    )
  }
}
