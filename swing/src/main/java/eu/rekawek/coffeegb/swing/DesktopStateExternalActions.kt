package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateExternalActions
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.nio.file.Path

/** Desktop integration invoked only from Coffee GB-owned background workers. */
internal class DesktopStateExternalActions : StateExternalActions {
  override fun openDirectory(directory: Path): Boolean = openPath(directory)

  internal fun openPath(path: Path): Boolean {
    if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) return false
    return try {
      val desktop = Desktop.getDesktop()
      if (!desktop.isSupported(Desktop.Action.OPEN)) return false
      desktop.open(path.toFile())
      true
    } catch (_: IOException) {
      false
    } catch (_: SecurityException) {
      false
    } catch (_: IllegalArgumentException) {
      false
    } catch (_: UnsupportedOperationException) {
      false
    }
  }
}
