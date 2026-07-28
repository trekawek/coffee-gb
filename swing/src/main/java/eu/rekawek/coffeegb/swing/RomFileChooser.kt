package eu.rekawek.coffeegb.swing

import java.io.File
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Applies a configured initial directory without synchronously probing that arbitrary path.
 *
 * JFileChooser normally calls File.exists() and isTraversable() inside setCurrentDirectory(),
 * which can block the EDT for a stale network mount. The directory model still enumerates the
 * selected path through JFileChooser's normal background loader after the dialog opens.
 */
internal class RomFileChooser : JFileChooser() {
  fun useConfiguredDirectory(path: Path) {
    putClientProperty("FileChooser.useShellFolder", false)
    currentDirectory = SyntacticDirectoryFile(path.toAbsolutePath().normalize().toString())
  }

  override fun isTraversable(file: File?): Boolean =
      when (file) {
        is SyntacticDirectoryFile -> true
        null -> false
        else -> super.isTraversable(file)
      }

  override fun getName(file: File?): String? =
      when (file) {
        is SyntacticDirectoryFile -> file.name.ifEmpty { file.path }
        null -> null
        else -> super.getName(file)
      }

  override fun getIcon(file: File?): Icon? =
      when (file) {
        is SyntacticDirectoryFile -> UIManager.getIcon("FileView.directoryIcon")
        null -> null
        else -> super.getIcon(file)
      }

  private class SyntacticDirectoryFile(path: String) : File(path) {
    override fun exists(): Boolean = true

    override fun isDirectory(): Boolean = true

    override fun canWrite(): Boolean = false

    override fun getCanonicalFile(): File = SyntacticDirectoryFile(absolutePath)

    override fun getCanonicalPath(): String = absolutePath

    override fun getParentFile(): File? = parent?.let(::SyntacticDirectoryFile)
  }
}
