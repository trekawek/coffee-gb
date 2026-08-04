package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.BatteryStore
import eu.rekawek.coffeegb.controller.state.FileStateStore
import eu.rekawek.coffeegb.controller.state.RomPersistenceStore
import eu.rekawek.coffeegb.controller.state.SessionPersistence
import eu.rekawek.coffeegb.controller.state.StateStorageLayout
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.RomImage
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class RomSessionPreparerTest {

  @Test
  fun reusesExactBootStateForRepeatedRom() {
    val cache = BootStateCache(2)
    val preparer = RomSessionPreparer(cache)

    val first =
        assertIs<PreparedSession.FromBootState>(
            preparer.prepare(FAST_FORWARD_PROPERTIES, LoadRomEvent(ROM)))
    val second =
        assertIs<PreparedSession.FromBootState>(
            preparer.prepare(FAST_FORWARD_PROPERTIES, LoadRomEvent(ROM)))

    assertSame(first.bootState, second.bootState)
    assertEquals(1, cache.size)
    assertEquals(1, cache.hitCount)

    val restored = second.materialize()
    try {
      assertEquals(0x0100, restored.cpu.registers.pc)
    } finally {
      restored.discardUnstarted()
    }
  }

  @Test
  fun suppliedDetachedStateSkipsBootAndRestoresDirectly() {
    val config =
        Controller.createGameboyConfig(PROPERTIES, Rom(ROM)).setBootstrapMode(BootstrapMode.SKIP)
    val source = config.build()
    source.addressSpace.setByte(0xc123, 0x5a)
    val state = DetachedStateAdapter.capture(source)
    source.discardUnstarted()

    val cache = BootStateCache(2)
    val prepared =
        assertIs<PreparedSession.FromDetachedState>(
            RomSessionPreparer(cache).prepare(PROPERTIES, LoadRomEvent(ROM, state)))
    val restored = prepared.materialize()
    try {
      assertEquals(0x5a, restored.addressSpace.getByte(0xc123))
      assertEquals(0, cache.size)
    } finally {
      restored.discardUnstarted()
    }
  }

  @Test
  fun exactArchiveImagePreservesSelectedEntryAcrossControllerContract() {
    val container = Files.createTempFile("coffee-gb-selected-entry", ".zip")
    try {
      val bytes = ROM.readBytes()
      val origin = RomOrigin.archiveEntry(container, "nested/selected.GBC", false)
      val image = RomImage(origin, bytes)

      val prepared =
          RomSessionPreparer(BootStateCache(2))
              .prepare(PROPERTIES, LoadRomEvent(image))

      assertEquals(origin, prepared.config.rom.origin)
      assertEquals("nested/selected.GBC", prepared.config.rom.origin.archiveEntry().orElseThrow())
      assertEquals(bytes.toList(), prepared.config.rom.image.bytes().toList())
    } finally {
      Files.deleteIfExists(container)
    }
  }

  @Test
  fun pathlessRomUsesItsProvidedHostPersistenceStore() {
    val root = Files.createTempDirectory("coffee-gb-pathless-store")
    try {
      val image = RomImage.memory(ROM.readBytes(), "picked.gb")
      lateinit var stateStore: FileStateStore
      val store =
          RomPersistenceStore { _, hashes ->
            val layout = StateStorageLayout(root.resolve("games").resolve(hashes.primaryRom.hex()))
            val battery =
                BatteryStorage(
                    BatteryStorage.Source.managed(layout.batteryFile, root),
                    emptyList(),
                )
            stateStore = FileStateStore(layout)
            SessionPersistence(
                stateStore,
                BatteryStore { battery },
                null,
            )
          }

      val prepared =
          RomSessionPreparer(BootStateCache(2)).prepare(
              PROPERTIES,
              LoadRomEvent(image, persistenceStore = store),
          )

      assertNull(prepared.config.rom.file)
      assertSame(stateStore, prepared.stateStore)
      assertTrue(prepared.config.batteryStorage.targetPath().startsWith(root))
      assertTrue(prepared.config.batteryStorage.targetPath().fileName.toString() == "battery.sav")
    } finally {
      Files.walk(root).use { paths ->
        paths.sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
      }
    }
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    val PROPERTIES = EmulatorProperties()

    val FAST_FORWARD_PROPERTIES =
        EmulatorProperties().also {
          it.properties[EmulatorProperties.Key.BootstrapMode.propertyName] =
              BootstrapMode.FAST_FORWARD.name
        }
  }
}
