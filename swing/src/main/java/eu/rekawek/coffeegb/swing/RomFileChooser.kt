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

  override fun isTraversable(file: File): Boolean =
      if (file is SyntacticDirectoryFile) true else super.isTraversable(file)

  override fun getName(file: File): String =
      if (file is SyntacticDirectoryFile) file.name.ifEmpty { file.path } else super.getName(file)

  override fun getIcon(file: File): Icon? =
      if (file is SyntacticDirectoryFile) UIManager.getIcon("FileView.directoryIcon")
      else super.getIcon(file)

  private class SyntacticDirectoryFile(path: String) : File(path) {
    override fun exists(): Boolean = true

    override fun isDirectory(): Boolean = true

    override fun canWrite(): Boolean = false

    override fun getCanonicalFile(): File = SyntacticDirectoryFile(absolutePath)

    override fun getCanonicalPath(): String = absolutePath

    override fun getParentFile(): File? = parent?.let(::SyntacticDirectoryFile)
  }
}
