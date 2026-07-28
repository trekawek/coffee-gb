package eu.rekawek.coffeegb.controller.state

import java.nio.file.Path

/**
 * Optional platform integration invoked only by the state worker. Implementations must not mutate
 * Swing and return false when no supported file-manager integration is available.
 */
fun interface StateExternalActions {
  fun openDirectory(directory: Path): Boolean

  companion object {
    @JvmField val UNSUPPORTED = StateExternalActions { false }
  }
}
