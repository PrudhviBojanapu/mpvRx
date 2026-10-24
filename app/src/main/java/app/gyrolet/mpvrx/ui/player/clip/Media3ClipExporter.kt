/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context

internal object Media3ClipExporter {
  @Suppress("UNUSED_PARAMETER")
  suspend fun export(
    context: Context,
    source: String,
    output: String,
    startSeconds: Double,
    endSeconds: Double,
    crop: ClipCrop?,
    cropFrameWidth: Int,
    cropFrameHeight: Int,
    headers: Map<String, String>,
    onProgress: (Double) -> Unit,
  ): String? = "Clip export disabled"
}
