package eu.rekawek.coffeegb.swing

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DesktopRomFileBrowserTest {

  @Test
  fun `initial location is syntactic and prefers configured directory`() {
    var fallbackCalls = 0
    var rootCalls = 0
    val browser =
        DesktopRomFileBrowser(
            fallbackDirectory = {
              fallbackCalls++
              Path.of("fallback")
            },
            rootDirectories = {
              rootCalls++
              error("root discovery is filesystem work")
            },
        )
    val preferred = Path.of("configured", "missing", "..", "roms")

    assertEquals(preferred.toAbsolutePath().normalize(), browser.initialLocation(preferred))
    assertEquals(0, fallbackCalls)
    assertEquals(0, rootCalls)
  }

  @Test
  fun `initial location falls back to injected user home syntactically`() {
    val fallback = Path.of("fallback", "home", "..", "roms")
    val browser = DesktopRomFileBrowser(fallbackDirectory = { fallback })

    assertEquals(fallback.toAbsolutePath().normalize(), browser.initialLocation(null))
  }

  @Test
  fun `directory listing keeps exact paths filters extensions and sorts directories first`() {
    val directory = Files.createTempDirectory("coffee-gb-rom-browser")
    try {
      val alphaDirectory = Files.createDirectory(directory.resolve("Alpha"))
      val betaDirectory = Files.createDirectory(directory.resolve("beta"))
      val paths =
          listOf("z.GBC", "a.gb", "cart.ROM", "pack.ZiP", "seven.7Z", "ignored.txt")
              .associateWith { Files.writeString(directory.resolve(it), it) }

      val result = DesktopRomFileBrowser().list(directory)

      assertNull(result.errorMessage)
      assertFalse(result.truncated)
      assertEquals(directory, result.location)
      assertEquals(
          listOf("..", "Alpha", "beta", "a.gb", "cart.ROM", "pack.ZiP", "seven.7Z", "z.GBC"),
          result.entries.map { it.label },
      )
      assertEquals(DesktopRomFileBrowser.EntryKind.PARENT, result.entries[0].kind)
      assertEquals(directory.parent, result.entries[0].path)
      assertEquals(alphaDirectory, result.entries[1].path)
      assertEquals(betaDirectory, result.entries[2].path)
      assertEquals(paths.getValue("a.gb"), result.entries[3].path)
      assertTrue(result.entries.drop(3).all { it.kind == DesktopRomFileBrowser.EntryKind.ROM })
      assertFalse(result.entries.any { it.path == paths.getValue("ignored.txt") })
    } finally {
      deleteTree(directory)
    }
  }

  @Test
  fun `parent from a filesystem root targets the roots view`() {
    val root = Path.of("/").toAbsolutePath().normalize()
    val result = DesktopRomFileBrowser(maxEntries = 1).list(root)

    val parent = result.entries.first()
    assertEquals(DesktopRomFileBrowser.EntryKind.PARENT, parent.kind)
    assertEquals("..", parent.label)
    assertNull(parent.path)
  }

  @Test
  fun `roots view is injectable sorted and retains exact targets`() {
    val base = Path.of("injected-roots").toAbsolutePath().normalize()
    val upper = base.resolve("Z-drive")
    val lower = base.resolve("a-drive")
    val result =
        DesktopRomFileBrowser(rootDirectories = { listOf(upper, lower) }).list(null)

    assertNull(result.location)
    assertNull(result.errorMessage)
    assertEquals(listOf("a-drive", "Z-drive"), result.entries.map { it.label })
    assertEquals(listOf(lower, upper), result.entries.map { it.path })
    assertTrue(result.entries.all { it.kind == DesktopRomFileBrowser.EntryKind.DIRECTORY })
  }

  @Test
  fun `missing directory returns readable error and preserves parent escape`() {
    val missing =
        Path.of(System.getProperty("java.io.tmpdir"), "coffee-gb-missing-${System.nanoTime()}")
            .toAbsolutePath()
            .normalize()

    val result = DesktopRomFileBrowser().list(missing)

    assertEquals(missing, result.location)
    assertEquals(listOf(".."), result.entries.map { it.label })
    assertEquals(missing.parent, result.entries.single().path)
    val error = assertNotNull(result.errorMessage)
    assertTrue(error.startsWith("Unable to read"))
    assertTrue(error.contains(missing.fileName.toString()))
  }

  @Test
  fun `root discovery failure is returned as readable error`() {
    val result =
        DesktopRomFileBrowser(
                rootDirectories = { throw SecurityException("roots denied") },
            )
            .list(null)

    assertTrue(result.entries.isEmpty())
    assertTrue(assertNotNull(result.errorMessage).contains("roots denied"))
  }

  @Test
  fun `listing is deterministically bounded after directory-first sorting`() {
    val directory = Files.createTempDirectory("coffee-gb-rom-browser-bounded")
    try {
      val directoryEntry = Files.createDirectory(directory.resolve("z-directory"))
      val a = Files.writeString(directory.resolve("a.gb"), "a")
      val b = Files.writeString(directory.resolve("b.gb"), "b")
      Files.writeString(directory.resolve("c.gb"), "c")

      val result = DesktopRomFileBrowser(maxEntries = 3).list(directory)

      assertTrue(result.truncated)
      assertEquals(listOf("..", "z-directory", "a.gb", "b.gb"), result.entries.map { it.label })
      assertEquals(listOf(directory.parent, directoryEntry, a, b), result.entries.map { it.path })
    } finally {
      deleteTree(directory)
    }
  }

  private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach { path ->
        try {
          Files.deleteIfExists(path)
        } catch (failure: IOException) {
          throw AssertionError("Unable to clean test path $path", failure)
        }
      }
    }
  }
}
