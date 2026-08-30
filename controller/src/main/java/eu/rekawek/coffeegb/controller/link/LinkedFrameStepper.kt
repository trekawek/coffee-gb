package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.events.withPresentationSuppressed
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import org.slf4j.LoggerFactory

/**
 * Advances one linked controller frame while resolving an otherwise perfectly mirrored software
 * role election. Real consoles never reach a link handshake with identical CPU, DIV, PPU, and
 * serial phase; two emulated copies can, and then both ROMs can make the same master/slave choice.
 *
 * The escape is deliberately conditional and deterministic. Ordinary asymmetric links retain the
 * exact scalar P1-then-P2 schedule, while rollback replay observes the same canonical-player
 * decision as live execution regardless of which player is local.
 */
internal object LinkedFrameStepper {

  // A whole number of controller budgets keeps dropped audio/display output bounded while giving
  // a slow mirrored master-selection loop about 217 ms of independent machine time.
  private const val INTERNAL_COLLISION_ESCAPE_FRAMES = 13

  // DMG-speed listeners need enough independent execution to reach their timeout fallback.
  private const val EXTERNAL_ELECTION_ESCAPE_FRAMES = 2

  // A native-CGB fast byte is far shorter than a controller frame. A single T-cycle gives later
  // role election a deterministic order without running a valid passive listener thousands of
  // byte periods ahead of an input-driven peer.
  private const val FAST_EXTERNAL_ELECTION_PHASE_TICKS = 1L

  fun advanceFrame(
      mode: LinkMode,
      sessions: List<Session?>,
      clockSpec: ClockSpec,
  ) {
    require(sessions.size == mode.playerCount)
    repeat(clockSpec.controllerTicksPerFrame()) {
      sessions.forEach { it?.gameboy?.tick() }
      if (mode == LinkMode.NORMAL) {
        breakMirroredRoleElection(sessions, clockSpec)
      }
    }
  }

  /** Returns the applied escape for focused scheduler tests, or null for ordinary lockstep. */
  internal fun breakMirroredRoleElection(
      sessions: List<Session?>,
      clockSpec: ClockSpec,
  ): SymmetryBreak? {
    if (sessions.size != LinkMode.NORMAL.playerCount) return null
    val first = sessions[0] ?: return null
    val second = sessions[1] ?: return null
    if (!clockSpec.hasCompatibleClockIdentity(first.gameboy.clockSpec)) return null
    val bothInternal =
        first.gameboy.isInternalClockTransferActive &&
            second.gameboy.isInternalClockTransferActive
    val bothExternal =
        first.gameboy.isExternalClockTransferActive &&
            second.gameboy.isExternalClockTransferActive
    if (!bothInternal && !bothExternal) return null
    val firstEndpoint = first.serialEndpoint as? Peer2PeerSerialEndpoint ?: return null
    val secondEndpoint = second.serialEndpoint as? Peer2PeerSerialEndpoint ?: return null
    if (!firstEndpoint.isConnected || !secondEndpoint.isConnected) return null
    if (!firstEndpoint.hasSameTransferState(secondEndpoint)) return null
    if (!first.gameboy.hasSameLinkTimingPhase(second.gameboy)) return null

    return when {
      bothInternal -> {
        val ticks =
            Math.multiplyExact(
                clockSpec.controllerTicksPerFrame().toLong(),
                INTERNAL_COLLISION_ESCAPE_FRAMES.toLong(),
            )
        advanceUnilaterally(second, ticks)
        LOG.atDebug().log(
            "Resolved mirrored internal-clock link election with a {}-tick P2 lead",
            ticks,
        )
        SymmetryBreak.INTERNAL_CLOCK_COLLISION
      }
      bothExternal -> {
        val limit =
            if (first.gameboy.isFastSerialClockSelectedForActiveTransfer) {
              FAST_EXTERNAL_ELECTION_PHASE_TICKS
            } else {
              Math.multiplyExact(
                  clockSpec.controllerTicksPerFrame().toLong(),
                  EXTERNAL_ELECTION_ESCAPE_FRAMES.toLong(),
              )
            }
        val ticks =
            advanceUnilaterally(first, limit) {
              first.gameboy.isInternalClockTransferActive
            }
        LOG.atDebug().log(
            "Resolved mirrored external-clock link election with a {}-tick P1 lead",
            ticks,
        )
        SymmetryBreak.EXTERNAL_CLOCK_DEADLOCK
      }
      else -> null
    }
  }

  private fun advanceUnilaterally(
      session: Session,
      limit: Long,
      stopAfterTick: () -> Boolean = { false },
  ): Long {
    val previousRumble = session.gameboy.isRumbleActive
    var ticks = 0L
    var failure: Throwable? = null
    try {
      session.eventBus.withPresentationSuppressed {
        while (ticks < limit) {
          session.gameboy.tick()
          ticks++
          if (stopAfterTick()) break
        }
      }
    } catch (caught: Throwable) {
      failure = caught
      throw caught
    } finally {
      try {
        session.gameboy.synchronizeRumbleOutput(previousRumble)
      } catch (rumbleFailure: Throwable) {
        if (failure != null) {
          failure.addSuppressed(rumbleFailure)
        } else {
          throw rumbleFailure
        }
      }
    }
    return ticks
  }

  internal enum class SymmetryBreak {
    INTERNAL_CLOCK_COLLISION,
    EXTERNAL_CLOCK_DEADLOCK,
  }

  private val LOG = LoggerFactory.getLogger(LinkedFrameStepper::class.java)
}
