package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.state.StateApplyException
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.core.hardware.ClockSpec

/** Validates a complete candidate group before linked execution or rollback construction. */
internal fun requireCompatibleLinkedClock(
    configurations: List<GameboyConfiguration?>,
): ClockSpec {
  return requireCompatibleLinkedClockIdentities(
      configurations.filterNotNull().map { LinkedClockIdentity(it.hardwareProfile.id(), it.clockSpec) })
}

internal data class LinkedClockIdentity(val profileId: String, val clockSpec: ClockSpec)

/** Testable narrow preflight also used by future registered profile additions. */
internal fun requireCompatibleLinkedClockIdentities(
    identities: List<LinkedClockIdentity>,
): ClockSpec {
  val first = identities.firstOrNull() ?: return ClockSpec.LEGACY
  val clock = first.clockSpec
  identities.drop(1).forEach { identity ->
    if (!clock.hasCompatibleControllerBudget(identity.clockSpec)) {
      throw StateApplyException(
          "Linked profile ${identity.profileId} clock ${identity.clockSpec} " +
              "is incompatible with ${first.profileId} clock $clock",
      )
    }
  }
  return clock
}
