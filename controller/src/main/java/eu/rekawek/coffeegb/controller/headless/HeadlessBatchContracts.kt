package eu.rekawek.coffeegb.controller.headless

import eu.rekawek.coffeegb.controller.replay.ReplayPlaybackStatus
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingOptions
import eu.rekawek.coffeegb.controller.replay.ReplayStateHashes
import eu.rekawek.coffeegb.controller.state.StateImage
import eu.rekawek.coffeegb.core.debug.DebugBreakpointHit
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionResult
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import java.util.Collections

/** Exact, finite amount of emulated work requested from a headless run. */
sealed interface HeadlessExecutionLimit {
  val count: Long

  data class Ticks(override val count: Long) : HeadlessExecutionLimit {
    init {
      require(count >= 0L) { "Headless tick count cannot be negative" }
    }
  }

  data class Frames(override val count: Long) : HeadlessExecutionLimit {
    init {
      require(count >= 0L) { "Headless frame count cannot be negative" }
    }
  }
}

/** Absolute physical joypad state installed immediately before [tick] executes. */
data class HeadlessInputTransition(
    val tick: Long,
    val player: Int,
    val absoluteMask: Int,
) {
  init {
    require(tick >= 0L) { "Headless input tick cannot be negative" }
    require(player in 0..3) { "Headless input player must be in 0..3" }
    require(absoluteMask in 0..0xff) { "Headless input mask must be one byte" }
  }
}

data class HeadlessCaptureOptions(
    val latestFrame: Boolean = false,
    val pcm16: Boolean = false,
    val maximumPcmBytes: Int = MAXIMUM_PCM_BYTES,
) {
  init {
    require(maximumPcmBytes in 0..MAXIMUM_PCM_BYTES) {
      "Headless PCM byte limit must be in 0..$MAXIMUM_PCM_BYTES"
    }
  }

  companion object {
    const val MAXIMUM_PCM_BYTES = 64 * 1024 * 1024

    @JvmField val NONE = HeadlessCaptureOptions()
  }
}

/** Immutable one-shot request; transitions are copied before the owner thread is started. */
class HeadlessRunRequest(
    val limit: HeadlessExecutionLimit,
    inputs: Collection<HeadlessInputTransition> = emptyList(),
    val rtcEpochMillis: Long = ReplayRecordingOptions.DEFAULT_RTC_EPOCH_MILLIS,
    val breakpoint: DebugBreakpoint? = null,
    val inspection: DebugInspectionRequest = HeadlessBatchDefaults.EMPTY_INSPECTION,
    val capture: HeadlessCaptureOptions = HeadlessCaptureOptions.NONE,
) {
  val inputs: List<HeadlessInputTransition> =
      Collections.unmodifiableList(ArrayList(inputs.map { it.copy() }))

  init {
    require(rtcEpochMillis >= 0L) { "Headless RTC epoch cannot be negative" }
    require(inspection.traceRequest().isEmpty) {
      "Headless terminal inspection does not accept an unconfigured trace request"
    }
  }
}

data class HeadlessReplayRequest(
    val maximumTicks: Long,
    val breakpoint: DebugBreakpoint? = null,
    val inspection: DebugInspectionRequest = HeadlessBatchDefaults.EMPTY_INSPECTION,
    val capture: HeadlessCaptureOptions = HeadlessCaptureOptions.NONE,
) {
  init {
    require(maximumTicks > 0L) { "Headless replay budget must be positive" }
    require(inspection.traceRequest().isEmpty) {
      "Headless terminal inspection does not accept an unconfigured trace request"
    }
  }
}

enum class HeadlessTerminationReason {
  TICK_LIMIT,
  FRAME_LIMIT,
  BREAKPOINT,
  REPLAY_COMPLETED,
  REPLAY_DIVERGED,
  REPLAY_BUDGET_EXHAUSTED,
}

/** Position after all requested work and terminal inspection have completed. */
data class HeadlessPosition(
    val completedTicks: Long,
    val frame: Long,
    val ticksIntoFrame: Int,
)

/** Path-free cartridge facts safe for deterministic reports. */
class HeadlessRomMetadata(
    val title: String,
    val sizeBytes: Int,
    sha256: ByteArray,
    val profileId: String,
    val cgbFlag: Int,
    val sgbFlag: Int,
    val cartridgeType: Int,
    val romSizeCode: Int,
    val ramSizeCode: Int,
    val nintendoLogoValid: Boolean,
    val headerChecksumValid: Boolean,
) {
  private val ownedSha256 = sha256.clone()

  init {
    require(ownedSha256.size == 32) { "ROM SHA-256 must contain exactly 32 bytes" }
  }

  val sha256: ByteArray
    get() = ownedSha256.clone()
}

data class HeadlessFrame(
    val completedTicks: Long,
    val frame: Long,
    val image: StateImage,
)

class HeadlessPcm16(
    val sampleRate: Int,
    val channels: Int,
    val completedTicks: Long,
    bytes: ByteArray,
) {
  private val ownedBytes = bytes.clone()

  init {
    require(sampleRate > 0) { "PCM sample rate must be positive" }
    require(channels == 2) { "Headless PCM must be stereo" }
    require(completedTicks >= 0L) { "PCM completed tick count cannot be negative" }
    require(ownedBytes.size % (channels * 2) == 0) {
      "PCM16 byte count must contain complete interleaved sample frames"
    }
  }

  val sampleFrames: Int
    get() = ownedBytes.size / (channels * 2)

  val bytes: ByteArray
    get() = ownedBytes.clone()
}

/** Fully detached output from one owner-thread batch execution. */
data class HeadlessBatchResult(
    val reason: HeadlessTerminationReason,
    val position: HeadlessPosition,
    val rom: HeadlessRomMetadata,
    val rtcEpochMillis: Long,
    val hashes: ReplayStateHashes,
    val inspection: DebugInspectionResult,
    val breakpointHit: DebugBreakpointHit?,
    val replayStatus: ReplayPlaybackStatus?,
    val latestFrame: HeadlessFrame?,
    val pcm: HeadlessPcm16?,
)

object HeadlessBatchDefaults {
  @JvmField
  val EMPTY_INSPECTION =
      DebugInspectionRequest(emptyList(), emptyList(), emptySet())
}
