package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Controller

/**
 * Called by both peers during the host's START handoff from standalone to linked emulation.
 * Games that probe the cable only at startup must discard their standalone machine state.
 * LinkedController holds the fresh local machine until the other ROM arrives, so both machines
 * execute cartridge code with their serial endpoints attached, regardless of loading latency.
 */
fun createNetplayLoadEvent(
    state: Controller.ControllerState,
    mode: LinkMode,
): Controller.LoadRomEvent {
  val restart = mode == LinkMode.NORMAL && state.rom.cartridgeProperties.isLinkRequiredAtBoot
  return Controller.LoadRomEvent(
      state.rom.image,
      state = if (restart) null else state.state,
      // A coordinated fresh boot must also bypass any automatic or prompted state resume.
      allowAutosaveResume = !restart,
  )
}
