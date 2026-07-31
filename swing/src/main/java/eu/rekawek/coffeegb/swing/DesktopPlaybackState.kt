package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller

/**
 * Rejects delayed playback updates from an emulation session that has already been replaced.
 * Calls are EDT-owned by [SwingGui].
 */
internal class DesktopPlaybackState(
    private val applyPaused: (Boolean) -> Unit,
) {
  private var activeGeneration: Long? = null

  fun sessionStarted(generation: Long?) {
    activeGeneration = generation
  }

  fun sessionStopped() {
    activeGeneration = null
  }

  fun playbackChanged(event: Controller.SessionPlaybackStateEvent) {
    if (event.sessionGeneration == activeGeneration) {
      applyPaused(event.paused)
    }
  }
}
