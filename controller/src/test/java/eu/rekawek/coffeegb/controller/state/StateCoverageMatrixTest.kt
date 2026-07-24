package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.MementoTypeRegistry
import eu.rekawek.coffeegb.core.cpu.SpeedMode
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.FullChanger
import eu.rekawek.coffeegb.core.ir.InfraredPort
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.cart.MemoryController
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import eu.rekawek.coffeegb.core.memory.cart.type.*
import eu.rekawek.coffeegb.core.serial.*
import eu.rekawek.coffeegb.core.sgb.SuperGameboy
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test

class StateCoverageMatrixTest {

  @Test
  fun everyMutableMapperRoundTripsThroughDetachedGraphAfterMutation() {
    val rom = mutableRom()
    val cases =
        listOf(
            mapper("BasicRom") { BasicRom(it, Battery.NULL_BATTERY) },
            mapper("Mbc1") { Mbc1(it, Battery.NULL_BATTERY) },
            mapper("Mbc2") { Mbc2(it, Battery.NULL_BATTERY) },
            mapper("Mbc3") { Mbc3(it, Battery.NULL_BATTERY, VirtualTimeSource(120_000)) },
            mapper("Mbc5") { Mbc5(it, Battery.NULL_BATTERY) },
            mapper("Mbc6") { Mbc6(it, Battery.NULL_BATTERY) },
            mapper("Mbc7") { Mbc7(it, Battery.NULL_BATTERY) },
            mapper("Mmm01") { Mmm01(it, Battery.NULL_BATTERY, false) },
            mapper("PocketCamera") { PocketCamera(it, Battery.NULL_BATTERY) },
            mapper("Huc1") { Huc1(it, Battery.NULL_BATTERY) },
            mapper("Huc3") { Huc3(it, Battery.NULL_BATTERY, VirtualTimeSource(120_000)) },
            mapper("Tama5") { Tama5(it, Battery.NULL_BATTERY, VirtualTimeSource(120_000)) },
            mapper("BungEms") { BungEms(it, Battery.NULL_BATTERY) },
            mapper("BhgosMulticart") { BhgosMulticart(it, Battery.NULL_BATTERY) },
            mapper("MakonNtOld2") { MakonNtOld2(it, Battery.NULL_BATTERY) },
            mapper("DuzMulticart") { DuzMulticart(it, Battery.NULL_BATTERY) },
            mapper("Mani32kMulticart") { Mani32kMulticart(it) },
            mapper("SlMulticart") { SlMulticart(it, Battery.NULL_BATTERY) },
            mapper("Sintax") { Sintax(it, Battery.NULL_BATTERY) },
            mapper("Bbd") { Bbd(it, Battery.NULL_BATTERY) },
            mapper("SachenMmc1") { SachenMmc(it, false, false) },
            mapper("SachenMmc2") { SachenMmc(it, true, false) },
            mapper("WisdomTree") { WisdomTree(it) },
            mapper("Datel") { Datel(it, Battery.NULL_BATTERY) },
        )

    val covered = linkedSetOf<String>()
    cases.forEach { case ->
      val controller = case.factory(rom)
      EventBusImpl().use(controller::init)
      val captured = StateGraph.capture(controller.saveToMemento())
      mutate(controller)
      val mutated = StateGraph.capture(controller.saveToMemento())
      assertNotEquals(captured, mutated, "${case.name} mutation did not exercise owned state")

      @Suppress("UNCHECKED_CAST")
      controller.restoreFromMemento(StateGraph.restore(captured) as Memento<MemoryController>)
      assertEquals(captured, StateGraph.capture(controller.saveToMemento()), case.name)
      covered += case.name
    }

    assertEquals(EXPECTED_MAPPER_FAMILIES, covered)
    val registeredMapperRecords =
        MementoTypeRegistry.recordClassNames.filter {
          it.startsWith("eu.rekawek.coffeegb.core.memory.cart.type.")
        }
    assertEquals(24, registeredMapperRecords.size)
  }

  @Test
  fun everyStatefulSerialPeripheralRoundTripsWithoutCapturingCallbacks() {
    val peer = Peer2PeerSerialEndpoint()
    val otherPeer = Peer2PeerSerialEndpoint().also(peer::init)
    val adapter = FourPlayerAdapter()
    val cases =
        listOf(
            "ByteReceiving" to ByteReceivingSerialEndpoint { _ -> },
            "Peer2Peer" to otherPeer,
            "Printer" to GameboyPrinterSerialEndpoint { _, _, _, _, _, _ -> },
            "GpsReceiver" to GpsReceiverSerialEndpoint(),
            "BarcodeBoy" to BarcodeBoySerialEndpoint(),
            "FourPlayerAdapter" to adapter.endpoint(2),
        )

    cases.forEach { (name, endpoint) ->
      endpoint.setSb(0xa5)
      val captured = StateGraph.capture(endpoint.saveToMemento())
      endpoint.startSending()
      endpoint.setExternalTransfer(true)
      repeat(3) {
        endpoint.sendBit()
        endpoint.tick()
      }
      val mutated = StateGraph.capture(endpoint.saveToMemento())
      assertNotEquals(captured, mutated, "$name mutation did not exercise owned state")

      @Suppress("UNCHECKED_CAST")
      endpoint.restoreFromMemento(StateGraph.restore(captured) as Memento<SerialEndpoint>?)
      assertEquals(captured, StateGraph.capture(endpoint.saveToMemento()), name)
    }

    assertEquals(EXPECTED_SERIAL_FAMILIES, cases.map { it.first }.toSet())
  }

  @Test
  fun activeSgbMultipacketAndInfraredPulsePhasesContinueDeterministically() {
    EventBusImpl().use { sgbBus ->
      val sgb = SuperGameboy(sgbBus)
      val idle = StateGraph.capture(sgb.saveToMemento())
      val firstPacket = IntArray(16).also {
        it[0] = 2 // first half of a two-packet PAL01 command
        it[1] = 0x34
        it[2] = 0x12
      }
      sgbBus.post(SuperGameboy.PacketReceivedEvent(firstPacket))
      val partial = StateGraph.capture(sgb.saveToMemento())
      assertNotEquals(idle, partial)

      sgbBus.post(SuperGameboy.PacketReceivedEvent(IntArray(16)))
      val expected = StateGraph.capture(sgb.saveToMemento())
      @Suppress("UNCHECKED_CAST")
      sgb.restoreFromMemento(StateGraph.restore(partial) as Memento<SuperGameboy>)
      sgbBus.post(SuperGameboy.PacketReceivedEvent(IntArray(16)))
      assertEquals(expected, StateGraph.capture(sgb.saveToMemento()))
    }

    EventBusImpl().use { irBus ->
      val infrared = InfraredPort(true, SpeedMode(true))
      infrared.init(irBus)
      val idle = StateGraph.capture(infrared.saveToMemento())
      infrared.setByte(0xff56, 0xc0)
      irBus.post(FullChanger.TransformEvent(5))
      infrared.getByte(0xff56) // arm the first pulse at the emulated polling edge
      repeat(30) { infrared.tick() }
      val active = StateGraph.capture(infrared.saveToMemento())
      assertNotEquals(idle, active)

      repeat(500) { infrared.tick() }
      val expected = StateGraph.capture(infrared.saveToMemento())
      @Suppress("UNCHECKED_CAST")
      infrared.restoreFromMemento(StateGraph.restore(active) as Memento<InfraredPort>)
      repeat(500) { infrared.tick() }
      assertEquals(expected, StateGraph.capture(infrared.saveToMemento()))
    }
  }

  private data class MapperCase(
      val name: String,
      val factory: (Rom) -> MemoryController,
  )

  private fun mapper(name: String, factory: (Rom) -> MemoryController) = MapperCase(name, factory)

  private fun mutate(controller: MemoryController) {
    listOf(
            0x2001 to 0x25,
            0x2011 to 0x11,
            0x4000 to 0x03,
            0x5000 to 0x06,
            0x6000 to 0x01,
            0x7fff to 0x02,
            0xa001 to 0x07,
            0x0000 to 0x0a,
            0xa000 to 0x09,
        )
        .forEach { (address, value) -> controller.setByte(address, value) }
    when (controller) {
      is WisdomTree -> controller.setByte(0x0011, 0)
      is Mbc2 -> {
        controller.setByte(0x0000, 0x0a)
        controller.setByte(0xa000, 0x09)
      }
    }
    repeat(3) { controller.tick() }
  }

  private fun mutableRom(): Rom {
    val bytes = ByteArray(0x200000)
    bytes[0x147] = 0x1b // MBC5 + RAM + battery; constructors are selected explicitly.
    bytes[0x148] = 0x06
    bytes[0x149] = 0x03
    return Rom(bytes)
  }

  private companion object {
    val EXPECTED_MAPPER_FAMILIES =
        setOf(
            "BasicRom",
            "Mbc1",
            "Mbc2",
            "Mbc3",
            "Mbc5",
            "Mbc6",
            "Mbc7",
            "Mmm01",
            "PocketCamera",
            "Huc1",
            "Huc3",
            "Tama5",
            "BungEms",
            "BhgosMulticart",
            "MakonNtOld2",
            "DuzMulticart",
            "Mani32kMulticart",
            "SlMulticart",
            "Sintax",
            "Bbd",
            "SachenMmc1",
            "SachenMmc2",
            "WisdomTree",
            "Datel",
        )

    val EXPECTED_SERIAL_FAMILIES =
        setOf(
            "ByteReceiving",
            "Peer2Peer",
            "Printer",
            "GpsReceiver",
            "BarcodeBoy",
            "FourPlayerAdapter",
        )
  }
}
