package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.state.StateApplyException
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.core.hardware.ClockSpec

/** Validates a complete candidate group before linked execution or rollback construction. */
internal fun requireCompatibleLinkedClock(
    configurations: List<GameboyConfiguration?>,
): ClockSpec {
  val candidates = configurations.filterNotNull()
  val first = candidates.firstOrNull() ?: return ClockSpec.LEGACY
  val clock = first.clockSpec
  candidates.drop(1).forEach { candidate ->
    if (!clock.hasCompatibleClockIdentity(candidate.clockSpec)) {
      throw StateApplyException(
          "Linked profile ${candidate.hardwareProfile.id()} clock ${candidate.clockSpec} " +
              "is incompatible with ${first.hardwareProfile.id()} clock $clock",
      )
    }
  }
  return clock
}
