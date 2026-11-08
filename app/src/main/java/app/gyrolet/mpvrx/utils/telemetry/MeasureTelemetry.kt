/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.telemetry

import android.app.Application
import android.content.Context

internal object MeasureTelemetry {
  fun initialize(application: Application, enabled: Boolean) {}

  fun setEnabled(enabled: Boolean): Boolean = false

  fun isEnabled(context: Context): Boolean = false
}
