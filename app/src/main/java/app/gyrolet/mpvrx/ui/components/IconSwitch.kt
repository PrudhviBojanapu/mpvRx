/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.gyrolet.mpvrx.ui.icons.AnimatedIcon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics

@Composable
fun IconSwitch(
  checked: Boolean,
  onCheckedChange: ((Boolean) -> Unit)?,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val haptics = rememberAppHaptics()

  Switch(
    checked = checked,
    onCheckedChange =
      onCheckedChange?.let { callback ->
        { value ->
          if (value != checked) {
            callback(value)
            haptics.selection(value)
          }
        }
      },
    modifier = modifier,
    enabled = enabled,
    thumbContent = {
      AnimatedIcon(
        imageVector = if (checked) Icons.RoundedFilled.Check else Icons.RoundedFilled.Close,
        contentDescription = null,
        modifier = Modifier.size(SwitchDefaults.IconSize),
      )
    },
  )
}
