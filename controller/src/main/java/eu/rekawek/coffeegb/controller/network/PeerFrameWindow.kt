package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.StateLimits
import java.io.IOException

/** Bounds peer-advertised replay work without trusting absolute Long frame values. */
internal class PeerFrameWindow {
  private var highestFrame: Long? = null

  @Synchronized
  fun validateStateFrame(frame: Long): Long {
    validateAbsolute(frame)
    val highest = highestFrame
    if (highest != null) validateRelative(frame, highest)
    return frame
  }

  @Synchronized
  fun validateRuntimeFrame(frame: Long): Long {
    validateAbsolute(frame)
    val highest = highestFrame
        ?: throw IOException("Received a frame event before the peer's initial state")
    validateRelative(frame, highest)
    return frame
  }

  @Synchronized
  fun validateCheckpoint(frame: Long, stateFrames: Collection<Long>): Long {
    validateRuntimeFrame(frame)
    if (stateFrames.isEmpty() || stateFrames.any { it != frame }) {
      throw IOException("Checkpoint frame $frame does not match every checkpoint state")
    }
    return frame
  }

  /** Commits a frame only after the complete message has passed structural validation. */
  @Synchronized
  fun accept(frame: Long) {
    val highest = highestFrame
    if (highest == null || frame > highest) highestFrame = frame
  }

  /** An outbound state establishes the shared timeline for subsequent inbound input. */
  @Synchronized
  fun establishSharedFrame(frame: Long) {
    validateStateFrame(frame)
    accept(frame)
  }

  private fun validateAbsolute(frame: Long) {
    if (frame !in 0..StateLimits.NETPLAY_MAX_FRAME) {
      throw IOException("Invalid netplay frame $frame")
    }
  }

  private fun validateRelative(frame: Long, highest: Long) {
    val minimum = (highest - StateLimits.NETPLAY_ROLLBACK_FRAMES).coerceAtLeast(0)
    val maximum =
        (highest + StateLimits.NETPLAY_FUTURE_FRAMES).coerceAtMost(StateLimits.NETPLAY_MAX_FRAME)
    if (frame !in minimum..maximum) {
      throw IOException("Netplay frame $frame is outside the supported window $minimum..$maximum")
    }
  }
}
