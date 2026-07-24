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
            mapper("BasicRom", listOf(0xa000 to 0x31), listOf(0xa000)) {
              BasicRom(it, Battery.NULL_BATTERY)
            },
            mapper("Mbc1", listOf(0x0000 to 0x0a, 0x2000 to 0x03, 0xa000 to 0x32)) {
              Mbc1(it, Battery.NULL_BATTERY)
            },
            mapper("Mbc2", listOf(0x0000 to 0x0a, 0x2100 to 0x05, 0xa000 to 0x03)) {
              Mbc2(it, Battery.NULL_BATTERY)
            },
            mapper("Mbc3", rtcSetup(0x13), listOf(0xa000, 0x4000)) {
              Mbc3(it, Battery.NULL_BATTERY, VirtualTimeSource(120_000))
            },
            mapper("Mbc5", listOf(0x0000 to 0x0a, 0x2000 to 0x25, 0x4000 to 0x02, 0xa000 to 0x34)) {
              Mbc5(it, Battery.NULL_BATTERY)
            },
            mapper("Mbc6", listOf(0x0000 to 0x0a, 0x2000 to 0x03, 0x3000 to 0x04, 0xa000 to 0x35)) {
              Mbc6(it, Battery.NULL_BATTERY)
            },
            mapper("Mbc7", listOf(0x0000 to 0x0a, 0x2000 to 0x07, 0xa000 to 0x36)) {
              Mbc7(it, Battery.NULL_BATTERY)
            },
            mapper("Mmm01", listOf(0x0000 to 0x0a, 0x2000 to 0x06, 0x4000 to 0x02)) {
              Mmm01(it, Battery.NULL_BATTERY, false)
            },
            mapper("PocketCamera", listOf(0x0000 to 0x0a, 0x4000 to 0x10, 0xa001 to 0x37)) {
              PocketCamera(it, Battery.NULL_BATTERY)
            },
            mapper("Huc1", listOf(0x0000 to 0x0a, 0x2000 to 0x09, 0xa000 to 0x38)) {
              Huc1(it, Battery.NULL_BATTERY)
            },
            mapper("Huc3", listOf(0x0000 to 0x0a, 0x4000 to 0x0b, 0xa000 to 0x39)) {
              Huc3(it, Battery.NULL_BATTERY, VirtualTimeSource(120_000))
            },
            mapper("Tama5", listOf(0xa001 to 0x04, 0xa000 to 0x0a, 0xa001 to 0x00), listOf(0xa000)) {
              Tama5(it, Battery.NULL_BATTERY, VirtualTimeSource(120_000))
            },
            mapper("BungEms", listOf(0x2000 to 0x0a, 0x4000 to 0x03, 0xa000 to 0x3a)) {
              BungEms(it, Battery.NULL_BATTERY)
            },
            mapper("BhgosMulticart", listOf(0x2000 to 0x0b, 0x6000 to 0x01)) {
              BhgosMulticart(it, Battery.NULL_BATTERY)
            },
            mapper("MakonNtOld2", listOf(0x2000 to 0x0c, 0x5000 to 0x06)) {
              MakonNtOld2(it, Battery.NULL_BATTERY)
            },
            mapper("DuzMulticart", listOf(0x2000 to 0x0d, 0x4000 to 0x02)) {
              DuzMulticart(it, Battery.NULL_BATTERY)
            },
            mapper("Mani32kMulticart", listOf(0x4000 to 0x0e)) {
              Mani32kMulticart(it)
            },
            mapper("SlMulticart", listOf(0x2000 to 0x0f, 0x5000 to 0x06)) {
              SlMulticart(it, Battery.NULL_BATTERY)
            },
            mapper("Sintax", listOf(0x2000 to 0x10, 0x5000 to 0x06, 0x7000 to 0x02)) {
              Sintax(it, Battery.NULL_BATTERY)
            },
            mapper("Bbd", listOf(0x2000 to 0x11, 0x4000 to 0x03)) {
              Bbd(it, Battery.NULL_BATTERY)
            },
            mapper("SachenMmc1", listOf(0x0000 to 0x0a, 0x2000 to 0x12, 0x4000 to 0x02)) {
              SachenMmc(it, false, false)
            },
            mapper("SachenMmc2", listOf(0x0000 to 0x0a, 0x2000 to 0x13, 0x4000 to 0x03)) {
              SachenMmc(it, true, false)
            },
            mapper("WisdomTree", listOf(0x0011 to 0x00), listOf(0x4000, 0x7fff)) {
              WisdomTree(it)
            },
            mapper(
                "Datel",
                listOf(
                    0x7fe5 to 0x01,
                    0x0000 to 0x0a,
                    0x4000 to 0x08,
                    0xa000 to 0x21,
                    0x7fe5 to 0x10,
                ),
                listOf(0xa000, 0x4000, 0x7fe5),
            ) { datelWithRtcSlot(it) },
        )

    val covered = linkedSetOf<String>()
    cases.forEach { case ->
      val controller = case.factory(rom)
      EventBusImpl().use(controller::init)
      val idle = StateGraph.capture(controller.saveToMemento())
      case.setup(controller)
      val captured = StateGraph.capture(controller.saveToMemento())
      assertNotEquals(idle, captured, "${case.name} setup did not exercise owned state")

      val expectedTrace = continueMapper(controller, case.probes)
      val expectedState = StateGraph.capture(controller.saveToMemento())
      disturbMapper(controller)

      @Suppress("UNCHECKED_CAST")
      controller.restoreFromMemento(StateGraph.restore(captured) as Memento<MemoryController>)
      val actualTrace = continueMapper(controller, case.probes)
      assertEquals(expectedTrace, actualTrace, "${case.name} continuation trace")
      assertEquals(expectedState, StateGraph.capture(controller.saveToMemento()), case.name)
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
    val receivedBytes = mutableListOf<Int>()
    val peer = Peer2PeerSerialEndpoint()
    val otherPeer = Peer2PeerSerialEndpoint().also(peer::init)
    val adapter = FourPlayerAdapter()
    val cases =
        listOf(
            peripheral(
                "ByteReceiving",
                ByteReceivingSerialEndpoint(receivedBytes::add),
                observed = { receivedBytes.toList() },
                clearObserved = receivedBytes::clear,
            ) {
              setSb(0xa5)
              startSending()
              repeat(3) { sendBit() }
            },
            peripheral("Peer2Peer", otherPeer) {
              peer.setSb(0x3c)
              peer.startSending()
              setSb(0xc3)
              startSending()
              repeat(3) {
                peer.sendBit()
                sendBit()
              }
            },
            peripheral("Printer", GameboyPrinterSerialEndpoint { _, _, _, _, _, _ -> }) {
              setSb(0x88)
              startSending()
              repeat(5) { sendBit() }
            },
            peripheral("GpsReceiver", GpsReceiverSerialEndpoint()) {
              setSb(0x47)
              startSending()
              repeat(4) { sendBit() }
              repeat(12) { tick() }
            },
            peripheral("BarcodeBoy", BarcodeBoySerialEndpoint()) {
              startSending()
              repeat(11) { sendBit() }
            },
            peripheral("FourPlayerAdapter", adapter.endpoint(2)) {
              setSb(0x88)
              setExternalTransfer(true)
              startSending()
              repeat(140) { tick() }
            },
        )

    cases.forEach { case ->
      val endpoint = case.endpoint
      val idle = StateGraph.capture(endpoint.saveToMemento())
      case.setup(endpoint)
      val captured = StateGraph.capture(endpoint.saveToMemento())
      assertNotEquals(idle, captured, "${case.name} setup did not exercise owned state")
      case.clearObserved()
      val expectedTrace = continuePeripheral(endpoint) + case.observed()
      val expectedState = StateGraph.capture(endpoint.saveToMemento())
      disturbPeripheral(endpoint)

      @Suppress("UNCHECKED_CAST")
      endpoint.restoreFromMemento(StateGraph.restore(captured) as Memento<SerialEndpoint>?)
      case.clearObserved()
      val actualTrace = continuePeripheral(endpoint) + case.observed()
      assertEquals(expectedTrace, actualTrace, "${case.name} continuation trace")
      assertEquals(expectedState, StateGraph.capture(endpoint.saveToMemento()), case.name)
    }

    assertEquals(EXPECTED_SERIAL_FAMILIES, cases.map { it.name }.toSet())
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

      val expectedTrace = continueInfrared(infrared, 500)
      val expected = StateGraph.capture(infrared.saveToMemento())
      infrared.setByte(0xff56, 0xc0)
      irBus.post(FullChanger.TransformEvent(9))
      infrared.getByte(0xff56)
      repeat(41) { infrared.tick() }
      @Suppress("UNCHECKED_CAST")
      infrared.restoreFromMemento(StateGraph.restore(active) as Memento<InfraredPort>)
      val actualTrace = continueInfrared(infrared, 500)
      assertEquals(expectedTrace, actualTrace)
      assertEquals(expected, StateGraph.capture(infrared.saveToMemento()))
    }
  }

  private data class MapperCase(
      val name: String,
      val factory: (Rom) -> MemoryController,
      val setup: (MemoryController) -> Unit,
      val probes: List<Int>,
  )

  private data class PeripheralCase(
      val name: String,
      val endpoint: SerialEndpoint,
      val setup: SerialEndpoint.() -> Unit,
      val observed: () -> List<Int>,
      val clearObserved: () -> Unit,
  )

  private fun mapper(
      name: String,
      setup: List<Pair<Int, Int>>,
      probes: List<Int> = DEFAULT_MAPPER_PROBES,
      factory: (Rom) -> MemoryController,
  ) =
      MapperCase(
          name,
          factory,
          { controller -> setup.forEach { (address, value) -> controller.setByte(address, value) } },
          probes,
      )

  private fun peripheral(
      name: String,
      endpoint: SerialEndpoint,
      observed: () -> List<Int> = { emptyList() },
      clearObserved: () -> Unit = {},
      setup: SerialEndpoint.() -> Unit,
  ) = PeripheralCase(name, endpoint, setup, observed, clearObserved)

  private fun continueMapper(
      controller: MemoryController,
      probes: List<Int>,
  ): List<Int> {
    val trace = mutableListOf<Int>()
    repeat(4) { round ->
      probes.forEach { trace += controller.getByte(it) }
      controller.tick()
      controller.setByte(0xa010 + round, 0x40 + round)
      probes.forEach { trace += controller.getByte(it) }
    }
    return trace
  }

  private fun continuePeripheral(endpoint: SerialEndpoint): List<Int> {
    val trace = mutableListOf<Int>()
    endpoint.setExternalTransfer(true)
    repeat(6) {
      trace += endpoint.sendBit()
      trace += endpoint.recvBit()
      repeat(19) { endpoint.tick() }
      trace += if (endpoint.isSerialInputHigh) 1 else 0
    }
    repeat(3) { byte ->
      endpoint.setSb(0x31 + byte)
      endpoint.startSending()
      repeat(8) {
        trace += endpoint.sendBit()
        trace += endpoint.recvBit()
        repeat(19) { endpoint.tick() }
        trace += if (endpoint.isSerialInputHigh) 1 else 0
      }
    }
    endpoint.setExternalTransfer(false)
    return trace
  }

  private fun disturbMapper(controller: MemoryController) {
    listOf(
            0x0000 to 0x0a,
            0x2001 to 0x25,
            0x2011 to 0x11,
            0x4000 to 0x03,
            0x5000 to 0x06,
            0x6000 to 0x01,
            0x7fff to 0x02,
            0xa001 to 0x07,
            0xa000 to 0x09,
        )
        .forEach { (address, value) -> controller.setByte(address, value) }
    if (controller is WisdomTree) controller.setByte(0x0029, 0)
    repeat(3) { controller.tick() }
  }

  private fun disturbPeripheral(endpoint: SerialEndpoint) {
    endpoint.setSb(0xe7)
    endpoint.startSending()
    repeat(5) {
      endpoint.sendBit()
      repeat(37) { endpoint.tick() }
    }
    endpoint.setExternalTransfer(false)
  }

  private fun continueInfrared(infrared: InfraredPort, ticks: Int): List<Int> {
    val trace = mutableListOf<Int>()
    repeat(ticks) { tick ->
      infrared.tick()
      if (tick % 11 == 0) trace += infrared.getByte(0xff56)
    }
    return trace
  }

  private fun rtcSetup(seconds: Int) =
      listOf(
          0x0000 to 0x0a,
          0x4000 to 0x08,
          0xa000 to seconds,
          0x6000 to 0x00,
          0x6000 to 0x01,
      )

  private fun datelWithRtcSlot(rom: Rom): Datel {
    val datel = Datel(rom, Battery.NULL_BATTERY)
    datel.setSlotCartridge(
        Mbc3(rom, Battery.NULL_BATTERY, VirtualTimeSource(120_000)),
        false,
    )
    return datel
  }

  private fun mutableRom(): Rom {
    val bytes = ByteArray(0x200000)
    bytes[0x147] = 0x1b // MBC5 + RAM + battery; constructors are selected explicitly.
    bytes[0x148] = 0x06
    bytes[0x149] = 0x03
    return Rom(bytes)
  }

  private companion object {
    val DEFAULT_MAPPER_PROBES = listOf(0x0100, 0x4000, 0x7000, 0x7fe1, 0xa000, 0xa001)

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
