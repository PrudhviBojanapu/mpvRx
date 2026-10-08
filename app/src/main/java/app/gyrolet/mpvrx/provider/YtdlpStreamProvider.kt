package app.gyrolet.mpvrx.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

class YtdlpStreamProvider : ContentProvider() {
  companion object {
    private const val TAG = "YtdlpStreamProvider"
    private const val CACHE_EXPIRY_MS = 2 * 60 * 60 * 1000L // 2 hours
    private val cache = ConcurrentHashMap<String, Pair<Long, Bundle>>()
  }

  override fun onCreate(): Boolean = true

  override fun call(
    method: String,
    arg: String?,
    extras: Bundle?,
  ): Bundle? {
    if (method != "resolveStream" || arg.isNullOrBlank()) return null
    val url = arg.trim()
    val ctx = context ?: return null

    val cached = cache[url]
    if (cached != null && (System.currentTimeMillis() - cached.first) < CACHE_EXPIRY_MS) {
      Log.d(TAG, "Serving cached stream for $url")
      return cached.second
    }

    val format = extras?.getString("format") ?: "bestvideo+bestaudio/best"
    val formatSort = extras?.getString("format_sort") ?: "res,fps,br"

    return try {
      val result =
        runBlocking {
          YtdlpManager.extractStream(
            context = ctx,
            source = url,
            format = format,
            formatSort = formatSort,
          )
        }.getOrThrow()

      val bundle =
        Bundle().apply {
          putBoolean("success", true)
          putString("video_url", result.url)
          putString("audio_url", result.audioUrl)
          putString("title", result.title)
          putString("resolution", result.resolution)
          putInt("duration", result.durationSeconds)
          putString("thumbnail", result.thumbnailUrl)
          val headerKeys = ArrayList<String>()
          val headerValues = ArrayList<String>()
          result.headers.forEach { (k, v) ->
            headerKeys.add(k)
            headerValues.add(v)
          }
          putStringArrayList("header_keys", headerKeys)
          putStringArrayList("header_values", headerValues)
        }

      cache[url] = System.currentTimeMillis() to bundle
      bundle
    } catch (e: Exception) {
      Log.e(TAG, "Stream resolution error for $url", e)
      Bundle().apply {
        putBoolean("success", false)
        putString("error", e.message)
      }
    }
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(
    uri: Uri,
    values: ContentValues?,
  ): Uri? = null

  override fun delete(
    uri: Uri,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0
}
