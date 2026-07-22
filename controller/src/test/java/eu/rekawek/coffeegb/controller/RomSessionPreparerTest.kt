package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
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
  fun suppliedMementoSkipsBootAndRestoresDirectly() {
    val config =
        Controller.createGameboyConfig(PROPERTIES, Rom(ROM)).setBootstrapMode(BootstrapMode.SKIP)
    val source = config.build()
    source.addressSpace.setByte(0xc123, 0x5a)
    val memento = source.saveToMemento()
    source.discardUnstarted()

    val cache = BootStateCache(2)
    val prepared =
        assertIs<PreparedSession.FromMemento>(
            RomSessionPreparer(cache).prepare(PROPERTIES, LoadRomEvent(ROM, memento)))
    val restored = prepared.materialize()
    try {
      assertEquals(0x5a, restored.addressSpace.getByte(0xc123))
      assertEquals(0, cache.size)
    } finally {
      restored.discardUnstarted()
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
