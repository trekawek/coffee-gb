package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.Ram
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class SnapshotManagerTest {

  @Test
  fun priorReleaseFixtureLoadsThroughSnapshotManager() {
    withGameboy { rom, gameboy ->
      snapshotFile(rom, 0).writeBytes(LEGACY_FIXTURE.readBytes())

      assertTrue(SnapshotManager(rom).loadSnapshot(0, gameboy))
    }
  }

  @Test
  fun rejectedSnapshotLeavesGameStateUnchanged() {
    withGameboy { rom, gameboy ->
      val before = PortableMementoCodec.encodeGameboy(gameboy.saveToMemento())
      snapshotFile(rom, 0).writeBytes(byteArrayOf(1, 2, 3, 4))

      assertFailsWith<IOException> { SnapshotManager(rom).loadSnapshot(0, gameboy) }

      assertContentEquals(before, PortableMementoCodec.encodeGameboy(gameboy.saveToMemento()))
    }
  }

  @Test
  fun restoreFailureRollsBackEveryPartialMutation() {
    withGameboy { rom, gameboy ->
      repeat(100) { gameboy.tick() }
      val before = PortableMementoCodec.encodeGameboy(gameboy.saveToMemento())
      repeat(2_000) { gameboy.tick() }
      val later = gameboy.saveToMemento()
      val mmu = recordComponent(later, "mmuMemento")!!
      val invalidMmu = replaceRecordComponent(mmu, "ramC000Memento", Ram.RamMemento(IntArray(0)))
      @Suppress("UNCHECKED_CAST")
      val invalid = replaceRecordComponent(later, "mmuMemento", invalidMmu) as Memento<Gameboy>
      snapshotFile(rom, 0).writeBytes(invalid.serialize())
      gameboy.restoreFromMemento(PortableMementoCodec.decodeGameboy(before))

      assertFailsWith<IOException> { SnapshotManager(rom).loadSnapshot(0, gameboy) }

      assertContentEquals(before, PortableMementoCodec.encodeGameboy(gameboy.saveToMemento()))
    }
  }

  private fun withGameboy(block: (java.io.File, Gameboy) -> Unit) {
    val directory = Files.createTempDirectory("coffee-gb-snapshot-test")
    val rom = directory.resolve("fixture.gb").toFile().also { it.writeBytes(ROM.readBytes()) }
    val eventBus = EventBusImpl()
    val gameboy =
        Gameboy.GameboyConfiguration(Rom(rom))
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .build()
    gameboy.init(
        eventBus,
        SerialEndpoint.NULL_ENDPOINT,
        InfraredEndpoint.NULL_ENDPOINT,
        null,
    )
    try {
      block(rom, gameboy)
    } finally {
      gameboy.stop()
      gameboy.close()
      eventBus.close()
      Files.list(directory).use { files -> files.forEach { it.deleteIfExists() } }
      directory.deleteIfExists()
    }
  }

  private fun snapshotFile(rom: java.io.File, slot: Int) =
      rom.parentFile.resolve("${rom.nameWithoutExtension}.sn$slot")

  private fun recordComponent(record: Any, name: String): Any? =
      record.javaClass.recordComponents.single { it.name == name }.accessor.let { accessor ->
        accessor.isAccessible = true
        accessor.invoke(record)
      }

  private fun replaceRecordComponent(record: Any, name: String, replacement: Any?): Any {
    val components = record.javaClass.recordComponents
    val constructor =
        record.javaClass.getDeclaredConstructor(*components.map { it.type }.toTypedArray()).also {
          it.isAccessible = true
        }
    val arguments =
        components.map { component ->
          if (component.name == name) {
            replacement
          } else {
            component.accessor.let { accessor ->
              accessor.isAccessible = true
              accessor.invoke(record)
            }
          }
        }.toTypedArray()
    return constructor.newInstance(*arguments)
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
    val LEGACY_FIXTURE =
        Paths.get("src/test/resources/legacy", "coffee-gb-1.7.14-cpu-instrs.sn").toFile()
  }
}
