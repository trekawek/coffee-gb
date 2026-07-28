package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateExternalActions
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.nio.file.Path

/** Desktop integration called exclusively on Coffee GB's state worker. */
internal class DesktopStateExternalActions : StateExternalActions {
  override fun openDirectory(directory: Path): Boolean {
    if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) return false
    return try {
      val desktop = Desktop.getDesktop()
      if (!desktop.isSupported(Desktop.Action.OPEN)) return false
      desktop.open(directory.toFile())
      true
    } catch (_: IOException) {
      false
    } catch (_: SecurityException) {
      false
    } catch (_: UnsupportedOperationException) {
      false
    }
  }
}
