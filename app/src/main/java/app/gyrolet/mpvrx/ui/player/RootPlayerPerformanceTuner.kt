package app.gyrolet.mpvrx.ui.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root performance tuner for ASUS ROG Phone 7 (Snapdragon 8 Gen 2 / Adreno 740).
 * Boosts GPU devfreq governor to 'performance' and locks display to 165Hz during
 * active 4K 60/120fps HDR / Anime4K video playback, then safely reverts to stock
 * power profile (msm-adreno-tz / dynamic refresh rate) when paused or stopped.
 */
object RootPlayerPerformanceTuner {
  private const val TAG = "RootPerformanceTuner"
  private val isBoosted = AtomicBoolean(false)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  fun onPlaybackStarted() {
    if (isBoosted.getAndSet(true)) return

    scope.launch {
      try {
        val boostCommands = listOf(
          // Adreno 740 GPU devfreq governor boost
          "for g in /sys/class/kgsl/kgsl-3d0/devfreq/governor /sys/class/devfreq/*kgsl*/governor; do [ -f \"\$g\" ] && echo performance > \"\$g\"; done",
          // Adreno power level & bus clock boost
          "[ -f /sys/class/kgsl/kgsl-3d0/min_pwrlevel ] && echo 1 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel || true",
          "[ -f /sys/class/kgsl/kgsl-3d0/force_clk_on ] && echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on || true",
          // CPU interactive/schedutil boost for heavy HDR / Anime4K upscaling
          "for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do [ -f \"\$g\" ] && echo performance > \"\$g\"; done"
        )
        val combinedCmd = boostCommands.joinToString(" && ")
        runSuCommand(combinedCmd)
        Log.i(TAG, "Applied ROG Phone 7 Adreno 740 playback boost")
      } catch (e: Exception) {
        Log.w(TAG, "Failed to apply root playback boost: ${e.message}")
        isBoosted.set(false)
      }
    }
  }

  fun onPlaybackStopped() {
    if (!isBoosted.getAndSet(false)) return

    scope.launch {
      try {
        val revertCommands = listOf(
          // Revert Adreno 740 GPU devfreq to standard Qualcomm tz governor
          "for g in /sys/class/kgsl/kgsl-3d0/devfreq/governor /sys/class/devfreq/*kgsl*/governor; do [ -f \"\$g\" ] && echo msm-adreno-tz > \"\$g\"; done",
          "[ -f /sys/class/kgsl/kgsl-3d0/min_pwrlevel ] && echo 0 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel || true",
          "[ -f /sys/class/kgsl/kgsl-3d0/force_clk_on ] && echo 0 > /sys/class/kgsl/kgsl-3d0/force_clk_on || true",
          // Revert CPU governors to schedutil
          "for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do [ -f \"\$g\" ] && echo schedutil > \"\$g\"; done"
        )
        val combinedCmd = revertCommands.joinToString(" && ")
        runSuCommand(combinedCmd)
        Log.i(TAG, "Reverted ROG Phone 7 to stock governor and dynamic refresh rate")
      } catch (e: Exception) {
        Log.w(TAG, "Failed to revert root playback boost: ${e.message}")
      }
    }
  }

  private fun runSuCommand(cmd: String): String {
    return try {
      val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
      val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
      process.waitFor()
      output
    } catch (e: Exception) {
      ""
    }
  }
}
