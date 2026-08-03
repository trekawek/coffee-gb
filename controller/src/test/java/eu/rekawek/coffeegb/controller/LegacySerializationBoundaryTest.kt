package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class LegacySerializationBoundaryTest {

  @Test
  fun normalCaptureRewindBootSessionAndPortableDiskPathsDoNotInvokeImporter() {
    var imports = 0
    LegacySnapshotImporter.importObserver = { imports++ }
    val directory = Files.createTempDirectory("coffee-gb-no-legacy-runtime")
    try {
      val romFile =
          directory.resolve("normal.gb").toFile().also {
            it.writeBytes(Paths.get("src/test/resources/roms/cpu_instrs.gb").toFile().readBytes())
          }
      val configuration =
          Gameboy.GameboyConfiguration(Rom(romFile))
              .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
      val bus = EventBusImpl()
      val gameboy = configuration.build()
      gameboy.init(bus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
      try {
        repeat(128) { gameboy.tick() }
        val detached = DetachedStateAdapter.capture(gameboy)
        DetachedStateAdapter.apply(gameboy, detached)
        val portable = StateCodec.encode(StateCodec.capture(configuration, gameboy))
        StateCodec.decodeAndApply(portable, configuration, gameboy)

        val rewind = RewindManager()
        rewind.record(gameboy)
        repeat(64) { gameboy.tick() }
        assertTrue(rewind.rewindOneStep(gameboy))

        val boot = gameboy.saveBootState()
        gameboy.restoreBootState(boot)

        val snapshots = SnapshotManager(configuration)
        snapshots.saveSnapshot(0, gameboy)
        assertTrue(snapshots.loadSnapshot(0, gameboy))
      } finally {
        gameboy.stop()
        gameboy.close()
        bus.close()
      }

      val session = Session(configuration, EventBusImpl(), null)
      try {
        val sessionState = session.captureDetachedState()
        session.restoreDetachedState(sessionState)
      } finally {
        session.close()
      }
      assertEquals(0, imports)
    } finally {
      LegacySnapshotImporter.importObserver = null
      Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }
}
