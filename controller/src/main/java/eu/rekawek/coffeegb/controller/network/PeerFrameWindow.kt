package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.StateLimits
import java.io.IOException

/** Bounds peer-advertised replay work against the controller-owned emulation clock. */
internal object PeerFrameWindow {

  fun validateRuntimeFrame(frame: Long, authoritativeFrame: Long): Long {
    validateAbsolute(frame)
    validateAbsolute(authoritativeFrame)
    validateRelative(frame, authoritativeFrame)
    return frame
  }

  fun validateCheckpoint(frame: Long, stateFrames: Collection<Long>): Long {
    validateRebaseFrame(frame)
    if (stateFrames.any { it != frame }) {
      throw IOException("Checkpoint frame $frame does not match every checkpoint state")
    }
    return frame
  }

  fun validateAbsolute(frame: Long): Long {
    if (frame !in 0..StateLimits.NETPLAY_MAX_FRAME) {
      throw IOException("Invalid netplay frame $frame")
    }
    return frame
  }

  private fun validateRebaseFrame(frame: Long) {
    validateAbsolute(frame)
    if (frame > StateLimits.NETPLAY_MAX_REBASE_FRAME) {
      throw IOException("Netplay checkpoint frame $frame leaves no supported runtime headroom")
    }
  }

  private fun validateRelative(frame: Long, authoritativeFrame: Long) {
    val minimum =
        (authoritativeFrame - StateLimits.NETPLAY_ROLLBACK_FRAMES).coerceAtLeast(0)
    val maximum =
        (authoritativeFrame + StateLimits.NETPLAY_FUTURE_FRAMES)
            .coerceAtMost(StateLimits.NETPLAY_MAX_FRAME)
    if (frame !in minimum..maximum) {
      throw IOException("Netplay frame $frame is outside the supported window $minimum..$maximum")
    }
  }
}
