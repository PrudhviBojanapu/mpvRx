/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.telemetry

import okhttp3.OkHttpClient

internal fun OkHttpClient.Builder.withMeasureTelemetry(): OkHttpClient.Builder = this
