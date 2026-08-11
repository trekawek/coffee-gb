package eu.rekawek.coffeegb.swing

import java.io.File
import java.util.concurrent.FutureTask
import javax.swing.Icon
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileSystemView
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.Test

class RomFileChooserTest {

  @Test
  fun `ordinary entry icons use generic UI icons without shell lookup`() =
      onEdt {
        val chooser = RomFileChooser()
        val fileSystemView = NoSystemIconFileSystemView()
        chooser.fileSystemView = fileSystemView

        val fileIcon = chooser.getIcon(File("game.gb"))
        val secondFileIcon = chooser.getIcon(File("another.gb"))
        val directoryIcon = chooser.getIcon(DirectoryEntry("roms"))

        assertSame(UIManager.getIcon("FileView.fileIcon"), fileIcon)
        assertSame(fileIcon, secondFileIcon)
        assertSame(UIManager.getIcon("FileView.directoryIcon"), directoryIcon)
        assertEquals(0, fileSystemView.systemIconCalls)
      }

  private class DirectoryEntry(path: String) : File(path) {
    override fun isDirectory(): Boolean = true
  }

  private class NoSystemIconFileSystemView : FileSystemView() {
    var systemIconCalls = 0

    override fun createNewFolder(containingDir: File): File {
      throw UnsupportedOperationException("not needed by this test")
    }

    override fun getSystemIcon(file: File): Icon {
      systemIconCalls++
      throw AssertionError("ROM chooser must not resolve shell icons")
    }
  }

  private fun <T> onEdt(action: () -> T): T {
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
