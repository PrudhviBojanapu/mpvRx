/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Native hardware SurfaceView rendering for optimal performance and battery life.
 */
internal fun shouldUseTextureRendering(
  liquidGlass: Boolean,
  hdrOutput: Boolean,
  liquidGlassInHdr: Boolean,
): Boolean = false

/** Keeps one MPV surface owner while choosing a native or Compose-recordable renderer. */
abstract class MpvSurfaceHost(context: Context, attributes: AttributeSet) : FrameLayout(context, attributes) {
  internal var renderSurface: Surface? = null
    private set
  private var ownsSurface = false
  private var rendererEpoch = 0
  private var textureRendering: Boolean? = null

  private val textureFrameReady = MutableStateFlow(false)
  internal val textureFrameReadyState: StateFlow<Boolean> = textureFrameReady

  internal val isTextureSurface: Boolean
    get() = textureRendering == true

  internal fun invalidateTextureFrameReadiness() {
    if (textureRendering == true) textureFrameReady.value = false
  }

  protected abstract fun onRenderSurfaceAvailable(surface: Surface, width: Int, height: Int)
  protected abstract fun onRenderSurfaceSizeChanged(width: Int, height: Int)
  protected abstract fun onRenderSurfaceDestroyed()

  /** Only changes for an appearance/HDR preference, never when controls appear or disappear. */
  internal fun setTextureRendering(enabled: Boolean) {
    if (textureRendering == enabled) return
    val wasConfigured = textureRendering != null
    textureRendering = enabled
    textureFrameReady.value = false
    val epoch = ++rendererEpoch
    if (wasConfigured) onRenderSurfaceDestroyed()
    if (ownsSurface) renderSurface?.release()
    renderSurface = null
    ownsSurface = false
    removeAllViews()
    descendantFocusability = FOCUS_BLOCK_DESCENDANTS

    fun available(surface: Surface, width: Int, height: Int, owned: Boolean) {
      if (epoch != rendererEpoch) {
        if (owned) surface.release()
        return
      }
      renderSurface = surface
      ownsSurface = owned
      onRenderSurfaceAvailable(surface, width, height)
    }

    fun destroyed() {
      if (epoch != rendererEpoch) return
      textureFrameReady.value = false
      onRenderSurfaceDestroyed()
      if (ownsSurface) renderSurface?.release()
      renderSurface = null
      ownsSurface = false
    }

    val child = if (enabled) {
      TextureView(context).apply {
        isOpaque = true
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
          override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            available(Surface(texture), width, height, owned = true)
          }

          override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            if (epoch == rendererEpoch) onRenderSurfaceSizeChanged(width, height)
          }

          override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            destroyed()
            return true
          }

          override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            if (epoch == rendererEpoch && !textureFrameReady.value) {
              textureFrameReady.value = true
            }
          }
        }
      }
    } else {
      SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
          override fun surfaceCreated(holder: SurfaceHolder) {
            available(holder.surface, this@apply.width, this@apply.height, owned = false)
          }

          override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (epoch == rendererEpoch) onRenderSurfaceSizeChanged(width, height)
          }

          override fun surfaceDestroyed(holder: SurfaceHolder) = destroyed()
        })
      }
    }
    addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }
}
