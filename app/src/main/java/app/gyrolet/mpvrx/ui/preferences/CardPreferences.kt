/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.LocalEmphasizedTypography

/**
 * Calm settings grouping for Material 3 Expressive.
 *
 * The old implementation wrapped every preference section in a large rounded card. Settings are
 * dense, routine content, so spacing and typography now carry most of the hierarchy. Individual
 * screens can still use a real Card/Surface for previews, accounts, warnings, or other content
 * that genuinely benefits from containment.
 */
@Composable
fun PreferenceCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 2.dp),
    content = content,
  )
}

/** A quiet separator for adjacent preference rows. */
@Composable
fun PreferenceDivider(modifier: Modifier = Modifier) {
  HorizontalDivider(
    modifier = modifier.padding(start = 16.dp, end = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
  )
}

/**
 * Section heading for routine settings content.
 *
 * Expressive hierarchy comes from type, whitespace and the page-level hero/title—not a decorative
 * underline repeated before every group.
 */
@Composable
fun PreferenceSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  topPadding: Dp = 28.dp,
) {
  val emphasizedTypography = LocalEmphasizedTypography.current
  Text(
    text = title,
    modifier =
      modifier
        .fillMaxWidth()
        .padding(start = 24.dp, end = 24.dp, top = topPadding, bottom = 8.dp),
    style = emphasizedTypography.titleMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )
}
