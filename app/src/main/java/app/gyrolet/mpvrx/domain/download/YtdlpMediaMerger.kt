/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object YtdlpMediaMerger {
  suspend fun merge(
    context: Context,
    candidates: List<File>,
    outputFile: File,
  ): File = withContext(Dispatchers.IO) {
    val inputs = findInputs(candidates)
    val stagingFile = File(outputFile.parentFile, "${outputFile.name}.merging")
    stagingFile.delete()

    val videoExtractor = MediaExtractor()
    val audioExtractor = MediaExtractor()
    var muxer: MediaMuxer? = null

    try {
      videoExtractor.setDataSource(inputs.video.absolutePath)
      audioExtractor.setDataSource(inputs.audio.absolutePath)

      var videoTrackIndex = -1
      var audioTrackIndex = -1
      var videoFormat: MediaFormat? = null
      var audioFormat: MediaFormat? = null

      for (i in 0 until videoExtractor.trackCount) {
        val format = videoExtractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("video/")) {
          videoTrackIndex = i
          videoFormat = format
          break
        }
      }

      for (i in 0 until audioExtractor.trackCount) {
        val format = audioExtractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("audio/")) {
          audioTrackIndex = i
          audioFormat = format
          break
        }
      }

      check(videoTrackIndex >= 0 && videoFormat != null) { "No video track found in ${inputs.video.name}" }
      check(audioTrackIndex >= 0 && audioFormat != null) { "No audio track found in ${inputs.audio.name}" }

      muxer = MediaMuxer(stagingFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val muxerVideoTrack = muxer.addTrack(videoFormat)
      val muxerAudioTrack = muxer.addTrack(audioFormat)
      muxer.start()

      // Mux video track
      videoExtractor.selectTrack(videoTrackIndex)
      val buffer = ByteBuffer.allocateDirect(1024 * 1024 * 2) // 2MB buffer
      val bufferInfo = MediaCodec.BufferInfo()

      while (true) {
        val sampleSize = videoExtractor.readSampleData(buffer, 0)
        if (sampleSize < 0) break
        bufferInfo.offset = 0
        bufferInfo.size = sampleSize
        bufferInfo.presentationTimeUs = videoExtractor.sampleTime
        bufferInfo.flags = videoExtractor.sampleFlags
        muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
        videoExtractor.advance()
      }

      // Mux audio track
      audioExtractor.selectTrack(audioTrackIndex)
      while (true) {
        val sampleSize = audioExtractor.readSampleData(buffer, 0)
        if (sampleSize < 0) break
        bufferInfo.offset = 0
        bufferInfo.size = sampleSize
        bufferInfo.presentationTimeUs = audioExtractor.sampleTime
        bufferInfo.flags = audioExtractor.sampleFlags
        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
        audioExtractor.advance()
      }

      muxer.stop()
      muxer.release()
      muxer = null

      publish(stagingFile, outputFile)
    } finally {
      runCatching { videoExtractor.release() }
      runCatching { audioExtractor.release() }
      runCatching { muxer?.release() }
      stagingFile.delete()
    }
  }

  private fun findInputs(candidates: List<File>): MergeInputs {
    val inspected =
      candidates
        .asSequence()
        .filter { it.isFile && it.length() > 0L }
        .mapNotNull(::inspect)
        .toList()
    val video = inspected.firstOrNull { it.hasVideo && !it.hasAudio } ?: inspected.firstOrNull { it.hasVideo }
      ?: throw IllegalStateException("Downloaded video stream was not found")
    val audio =
      inspected.firstOrNull { it.file != video.file && it.hasAudio && !it.hasVideo }
        ?: inspected.firstOrNull { it.file != video.file && it.hasAudio }
        ?: throw IllegalStateException("Downloaded audio stream was not found")
    return MergeInputs(video.file, audio.file)
  }

  private fun inspect(file: File): InspectedFile? =
    runCatching {
      val extractor = MediaExtractor()
      try {
        extractor.setDataSource(file.absolutePath)
        var hasVideo = false
        var hasAudio = false
        repeat(extractor.trackCount) { index ->
          when {
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true -> hasVideo = true
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true -> hasAudio = true
          }
        }
        InspectedFile(file, hasVideo, hasAudio).takeIf { it.hasVideo || it.hasAudio }
      } finally {
        extractor.release()
      }
    }.getOrNull()

  private fun publish(
    stagingFile: File,
    outputFile: File,
  ): File {
    check(stagingFile.isFile && stagingFile.length() > 0L) { "Media merge produced no output" }
    outputFile.parentFile?.mkdirs()
    try {
      Files.move(
        stagingFile.toPath(),
        outputFile.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (_: IOException) {
      Files.move(stagingFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    check(outputFile.isFile && outputFile.length() > 0L) { "Could not finalize merged download" }
    return outputFile
  }

  private data class InspectedFile(
    val file: File,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
  )

  private data class MergeInputs(
    val video: File,
    val audio: File,
  )
}
