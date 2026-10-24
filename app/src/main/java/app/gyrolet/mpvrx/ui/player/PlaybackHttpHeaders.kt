/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

/** Header parsing and serialization shared by every HTTP playback entry point. */
internal object PlaybackHttpHeaders {
  private val headerNamePattern = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

  fun fromIntent(intent: android.content.Intent?): Map<String, String> {
    if (intent == null) return emptyMap()
    val headers = linkedMapOf<String, String>()

    // 1. Direct extras
    val directUa = intent.getStringExtra("user_agent") ?: intent.getStringExtra("User-Agent")
    if (!directUa.isNullOrBlank()) {
      put(headers, "User-Agent", directUa)
    }
    val directRef = intent.getStringExtra("referrer") ?: intent.getStringExtra("Referer") ?: intent.getStringExtra("referer")
    if (!directRef.isNullOrBlank()) {
      put(headers, "Referer", directRef)
    }

    // 2. android.media.intent.extra.HTTP_HEADERS Bundle
    val headersBundle = intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")
    headersBundle?.keySet()?.forEach { key ->
      val v = headersBundle.getString(key)
      if (!v.isNullOrBlank()) {
        put(headers, key, v)
      }
    }

    // 3. "headers" array or string list
    val rawArray = intent.getStringArrayExtra("headers")
      ?: intent.getStringArrayListExtra("headers")?.toTypedArray()
    if (rawArray != null) {
      parseHeadersArray(rawArray, headers)
    }

    return headers
  }

  fun parseHeadersArray(values: Array<String>?, target: MutableMap<String, String> = linkedMapOf()): Map<String, String> {
    if (values == null) return target
    val hasColon = values.any { it.contains(":") }
    if (hasColon) {
      for (item in values) {
        val colonIdx = item.indexOf(':')
        if (colonIdx > 0) {
          put(target, item.substring(0, colonIdx), item.substring(colonIdx + 1))
        }
      }
    } else {
      values.asList().chunked(2).forEach { pair ->
        if (pair.size == 2) put(target, pair[0], pair[1])
      }
    }
    return target
  }

  fun fromFlatPairs(values: Array<String>?): Map<String, String> {
    if (values == null) return emptyMap()
    val headers = linkedMapOf<String, String>()
    return parseHeadersArray(values, headers)
  }

  /** Values already parsed by mpv, so commas in a header value are not list separators. */
  fun fromHeaderFields(values: Iterable<String>): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    values.forEach { field ->
      val separator = field.indexOf(':')
      if (separator > 0) put(headers, field.substring(0, separator), field.substring(separator + 1))
    }
    return headers
  }

  fun merge(vararg sources: Map<String, String>): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    sources.forEach { source -> source.forEach { (name, value) -> put(headers, name, value) } }
    return headers
  }

  fun withDefault(
    headers: Map<String, String>,
    name: String,
    value: String?,
  ): Map<String, String> {
    if (value.isNullOrBlank() || value(headers, name) != null) return headers
    return merge(headers, mapOf(name to value))
  }

  fun value(
    headers: Map<String, String>,
    name: String,
  ): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

  fun userAgent(headers: Map<String, String>): String? = value(headers, "User-Agent")

  fun toMpvHeaderFields(headers: Map<String, String>): String =
    headers.entries
      .filterNot { it.key.equals("User-Agent", ignoreCase = true) }
      .joinToString(",") { (name, value) -> "$name: ${escapeMpvListValue(value)}" }

  private fun put(
    target: MutableMap<String, String>,
    rawName: String,
    rawValue: String,
  ) {
    val name = rawName.trim()
    val value = rawValue.trim()
    if (!headerNamePattern.matches(name) || value.any { it == '\r' || it == '\n' || it == '\u0000' }) return
    target.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { existing -> target.remove(existing) }
    target[name] = value
  }

  private fun escapeMpvListValue(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace(",", "\\,")
}
