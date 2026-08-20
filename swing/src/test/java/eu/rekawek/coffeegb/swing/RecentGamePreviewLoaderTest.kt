package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.state.StateStorageResolver
import eu.rekawek.coffeegb.controller.state.StateWorkspace
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentGamePreviewLoaderTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `preview loader resolves the persisted duplicate archive occurrence`() {
    val archive = temporaryFolder.newFile("duplicates.zip")
    writeDuplicateZip(
        archive,
        "game.gb" to syntheticRom("FIRST", 0x31),
        "game.gb" to syntheticRom("SECOND", 0x32),
    )
    val path = archive.toPath().toAbsolutePath().normalize()
    val origin = RomOrigin.archiveEntry(path, "game.gb", 1, false)
    val openedTitle = AtomicReference<String>()
    val completed = CountDownLatch(1)
    val output = AtomicReference<List<DesktopRecentGame>>()
    val executor = Executors.newSingleThreadExecutor()
    val loader =
        RecentGamePreviewLoader(
            executor = executor,
            workspace = { saves, rom ->
              openedTitle.set(rom.title)
              StateWorkspace(StateStorageResolver.resolve(saves, rom))
            },
        )

    try {
      loader.load(
          listOf(
              DesktopRecentGame(
                  path = path,
                  title = "SECOND",
                  lastPlayed = Instant.parse("2026-08-18T12:00:00Z"),
                  origin = origin,
              )),
          ApplicationSettings.Saves(directory = temporaryFolder.newFolder("saves").toPath()),
      ) { games ->
        output.set(games)
        completed.countDown()
      }

      assertTrue(completed.await(5, TimeUnit.SECONDS))
      assertEquals("SECOND", openedTitle.get())
      assertEquals(origin, output.get().single().origin)
      assertEquals(Instant.parse("2026-08-18T12:00:00Z"), output.get().single().lastPlayed)
    } finally {
      loader.close()
    }
  }

  @Test
  fun `changed archive never substitutes a different entry for the saved preview`() {
    val archive = temporaryFolder.newFile("changed.zip")
    writeDuplicateZip(archive, "remaining.gb" to syntheticRom("REMAINING", 0x41))
    val path = archive.toPath().toAbsolutePath().normalize()
    val removedOrigin = RomOrigin.archiveEntry(path, "removed.gb", 0, false)

    assertFails { openExactRecentRom(path, removedOrigin) }
    assertFails { openExactRecentRom(path, null) }
  }

  @Test
  fun `active archive candidate replaces stale sidecar identity for current preview`() {
    val path = temporaryFolder.newFile("active.zip").toPath().toAbsolutePath().normalize()
    val persistedOrigin = RomOrigin.archiveEntry(path, "first.gb", 0, false)
    val activeOrigin = RomOrigin.archiveEntry(path, "second.gb", 0, false)
    val metadata =
        DesktopRecentGameMetadata(
            path,
            "FIRST",
            Instant.parse("2026-08-17T12:00:00Z"),
            persistedOrigin,
        )

    val current = recentGameSeed(path, metadata, activeOrigin, "SECOND")

    assertTrue(current.active)
    assertEquals("SECOND", current.title)
    assertEquals(activeOrigin, current.origin)
  }

  @Test
  fun `concise qualifiers distinguish equal filenames and duplicate archive occurrences`() {
    val firstPath = Path.of("/library/one/game.gb")
    val secondPath = Path.of("/library/two/game.gb")
    assertEquals("one/game.gb", recentGameQualifier(DesktopRecentGame(firstPath)))
    assertEquals("two/game.gb", recentGameQualifier(DesktopRecentGame(secondPath)))

    val archive = Path.of("/library/games.zip")
    val first =
        DesktopRecentGame(
            path = archive,
            origin = RomOrigin.archiveEntry(archive, "game.gb", 0, false),
        )
    val second =
        DesktopRecentGame(
            path = archive,
            origin = RomOrigin.archiveEntry(archive, "game.gb", 1, false),
        )
    assertEquals("game.gb", recentGameQualifier(first))
    assertEquals("game.gb #2", recentGameQualifier(second))
  }

  private fun writeDuplicateZip(target: File, vararg entries: Pair<String, ByteArray>) {
    ZipArchiveOutputStream(target).use { output ->
      entries.forEach { (name, bytes) ->
        output.putArchiveEntry(ZipArchiveEntry(name))
        output.write(bytes)
        output.closeArchiveEntry()
      }
    }
  }

  private fun syntheticRom(title: String, marker: Int): ByteArray {
    val rom = ByteArray(0x8000)
    title.toByteArray(StandardCharsets.US_ASCII).copyInto(rom, 0x134)
    rom[0x147] = 0
    rom[0x148] = 0
    rom[0x149] = 0
    rom[0x200] = marker.toByte()
    var checksum = 0
    for (address in 0x134..0x14c) {
      checksum = (checksum - (rom[address].toInt() and 0xff) - 1) and 0xff
    }
    rom[0x14d] = checksum.toByte()
    return rom
  }
}
