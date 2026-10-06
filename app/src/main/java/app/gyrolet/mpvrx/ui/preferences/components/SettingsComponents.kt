/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight

/**
 * Semantic settings row shared by settings surfaces that do not use compose-preference.
 *
 * Rows deliberately stay visually calm. A tonal container is opt-in through [containerColor] so
 * routine settings are not converted into a wall of cards.
 */
@Composable
fun SettingsClickableItem(
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  icon: AppIcon? = null,
  enabled: Boolean = true,
  onClick: () -> Unit = {},
  isFirstItem: Boolean = false,
  isLastItem: Boolean = false,
  trailing: @Composable (() -> Unit)? = null,
  containerColor: Color = Color.Transparent,
) {
  val interactionSource = remember { MutableInteractionSource() }

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(MaterialTheme.shapes.medium, enabled = enabled, focusedScale = 1.01f)
        .clickable(
          enabled = enabled,
          interactionSource = interactionSource,
          onClick = onClick,
        ),
    color = containerColor,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Box(
          modifier = Modifier.size(40.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint =
              if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color =
            if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (!description.isNullOrBlank()) {
          Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color =
              if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      if (trailing != null) {
        Spacer(modifier = Modifier.width(12.dp))
        trailing()
      }
    }
  }
}

/** Containment is reserved for a meaningful unit such as a server/account/preview group. */
@Composable
fun SettingsGroup(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(
      modifier = Modifier.padding(vertical = 4.dp),
      content = content,
    )
  }
}

@Composable
fun SettingsSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = title,
    modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    color = MaterialTheme.colorScheme.onSurface,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold,
  )
}

@Composable
fun SettingsSwitchItem(
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  icon: AppIcon? = null,
  isChecked: Boolean,
  enabled: Boolean = true,
  onClick: () -> Unit,
  isFirstItem: Boolean = false,
  isLastItem: Boolean = false,
) {
  SettingsClickableItem(
    title = title,
    description = description,
    icon = icon,
    enabled = enabled,
    onClick = onClick,
    isFirstItem = isFirstItem,
    isLastItem = isLastItem,
    modifier = modifier,
    trailing = {
      IconSwitch(
        checked = isChecked,
        onCheckedChange = null,
        enabled = enabled,
      )
    },
  )
}

@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
  HorizontalDivider(
    modifier = modifier.padding(start = 16.dp, end = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
  )
}
