package eu.rekawek.coffeegb.controller.state.bess

import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.state.bess.BessCodec
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** File work and BESS reconstruction only touch an isolated, unpublished machine. */
internal object BessFiles {
  fun load(path: Path, configuration: Gameboy.GameboyConfiguration): MachineState {
    val bytes = Files.newInputStream(path).use { it.readNBytes(BessCodec.MAX_FILE_SIZE + 1) }
    if (bytes.size > BessCodec.MAX_FILE_SIZE) throw IOException("BESS state is too large")
    val bess = BessCodec.read(bytes)
    return withMachine(configuration) { candidate ->
      candidate.restoreBessState(bess)
      DetachedStateAdapter.capture(candidate)
    }
  }

  fun save(path: Path, configuration: Gameboy.GameboyConfiguration, state: MachineState) {
    val bytes = withMachine(configuration) { candidate ->
      DetachedStateAdapter.apply(candidate, state)
      BessCodec.write(candidate.captureBessState())
    }
    AtomicFileWriter.system().write(path, bytes)
  }

  private fun <T> withMachine(configuration: Gameboy.GameboyConfiguration, block: (Gameboy) -> T): T {
    check(configuration.isBessTransfer) { "BESS work requires an isolated transfer configuration" }
    val candidate = configuration.forRestore().build()
    try {
      candidate.init(EventBus.NULL_EVENT_BUS, SerialEndpoint.NULL_ENDPOINT,
          InfraredEndpoint.NULL_ENDPOINT, null)
      return block(candidate)
    } finally {
      candidate.discardUnstarted()
    }
  }
}
