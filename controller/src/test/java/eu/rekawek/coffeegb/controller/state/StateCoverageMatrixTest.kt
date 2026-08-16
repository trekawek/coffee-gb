package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.core.cpu.SpeedMode
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.FullChanger
import eu.rekawek.coffeegb.core.ir.InfraredPort
import eu.rekawek.coffeegb.core.state.MachineStateCapture
import eu.rekawek.coffeegb.core.state.ComponentState
import eu.rekawek.coffeegb.core.memory.cart.MemoryController
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import eu.rekawek.coffeegb.core.memory.cart.type.*
import eu.rekawek.coffeegb.core.serial.*
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.sgb.SuperGameboy
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
            mapper(
                "Gowin",
                listOf(0x2000 to 0x03, 0x6080 to 0x65),
                listOf(0x4000, 0xa080),
            ) { Gowin(it, Battery.NULL_BATTERY) },
            mapper("Mbc2", listOf(0x0000 to 0x0a, 0x2100 to 0x05, 0xa000 to 0x03)) {
              Mbc2(it, Battery.NULL_BATTERY)
            },
            mapper("Mbc3", rtcSetup(0x13), listOf(0xa000, 0x4000)) {
              Mbc3(it, Battery.NULL_BATTERY, VirtualTimeSource(120_000))
            },
            mapper("Mbc5", listOf(0x0000 to 0x0a, 0x2000 to 0x25, 0x4000 to 0x0a, 0xa000 to 0x34)) {
              Mbc5(mutableRom(0x1e), Battery.NULL_BATTERY)
            },
            mapper(
                "Mbc5Multicart",
                listOf(
                    0xb000 to 0x20,
                    0xb100 to 0xe0,
                    0xb200 to 0xa0,
                    0x0000 to 0x0a,
                    0x4000 to 0x08,
                    0xa000 to 0x2a,
                ),
                listOf(0x0100, 0x4000, 0xa000),
            ) {
              Mbc5Multicart(
                  multicartFixture(),
                  Battery.NULL_BATTERY,
                  VirtualTimeSource(120_000),
                  ClockSpec.LEGACY,
              )
            },
            mapper(
                "LiCheng",
                listOf(
                    0x0000 to 0x0a,
                    0x2100 to 0x25,
                    0x2200 to 0x11,
                    0x4000 to 0x0a,
                    0xa000 to 0x3b,
                ),
            ) { LiCheng(mutableRom(0x1e), Battery.NULL_BATTERY) },
            mapper("XploderGb", listOf(0x0006 to 0x25, 0x0007 to 0x0a, 0xa000 to 0x3b)) {
              XploderGb(it, Battery.NULL_BATTERY)
            },
            mapper("Vf001Zook", listOf(0x7081 to 0x46, 0x7081 to 0x58, 0x7081 to 0x54)) {
              Vf001Zook(it, Battery.NULL_BATTERY)
            },
            mapper(
                "Vf001General",
                listOf(
                    0x0000 to 0x0a,
                    0x7000 to 0x96,
                    0x7001 to 0x00,
                    0x7002 to 0x01,
                    0x7003 to 0x80,
                    0x7004 to 0x11,
                    0x7005 to 0xaa,
                    0x7006 to 0x22,
                    0x7007 to 0xdd,
                    0x7000 to 0x25,
                    0x2000 to 0x03,
                    0xa000 to 0x3b,
                ),
                listOf(0x0100, 0x0101, 0x0102, 0x0103, 0x4000, 0xa000),
            ) { Vf001General(it, Battery.NULL_BATTERY) },
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
            mapper("Hitek", listOf(0x2001 to 0x04, 0x2080 to 0x03, 0x2000 to 0x11)) {
              Hitek(it, Battery.NULL_BATTERY)
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
      val idle = StateGraph.capture(controller.captureState())
      case.setup(controller)
      val captured = StateGraph.capture(controller.captureState())
      val direct = directState(controller)
      assertEquals(
          captured,
          direct.state,
          "${case.name} direct capture must use identity-verified live payloads",
      )
      assertEquals(
          primitivePayloadCount(captured),
          direct.identityVerifiedPayloadArrays,
          "${case.name} must explicitly declare every mapper-owned primitive payload",
      )
      assertNotEquals(idle, captured, "${case.name} setup did not exercise owned state")

      val expectedTrace = continueMapper(controller, case.probes)
      val expectedState = StateGraph.capture(controller.captureState())
      disturbMapper(controller)

      val restored = StateGraph.restore(captured)
      StateSemantics.validate(restored)
      @Suppress("UNCHECKED_CAST")
      controller.restoreState(restored as ComponentState<MemoryController>)
      val actualTrace = continueMapper(controller, case.probes)
      assertEquals(expectedTrace, actualTrace, "${case.name} continuation trace")
      assertEquals(expectedState, StateGraph.capture(controller.captureState()), case.name)
      covered += case.name
    }

    assertEquals(EXPECTED_MAPPER_FAMILIES, covered)
    val registeredMapperRecords =
        StateTypeRegistry.recordClassNames.filter {
          it.startsWith("eu.rekawek.coffeegb.core.memory.cart.type.")
        }
    assertEquals(31, registeredMapperRecords.size)
  }

  private fun directState(controller: MemoryController): DirectState =
      MachineStateCapture.withVerifiedView(
          controller::declareMachineStatePayloads,
          { capture -> controller.captureState(capture) },
          { view, capture ->
            DirectState(StateGraph.capture(view), capture.verifiedPayloadArrays)
          },
      )

  private fun primitivePayloadCount(value: StateValue): Int =
      when (value) {
        is PrimitiveArrayState<*> -> 1
        is RecordState -> value.fields.sumOf { primitivePayloadCount(it.value) }
        is ObjectArrayState -> value.values.sumOf(::primitivePayloadCount)
        is ListState -> value.values.sumOf(::primitivePayloadCount)
        is Int32MapState -> value.entries.sumOf { primitivePayloadCount(it.value) }
        else -> 0
      }

  private data class DirectState(
      val state: StateValue,
      val identityVerifiedPayloadArrays: Int,
  )

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
            peripheral(
                "MobileAdapter",
                MobileAdapterSerialEndpoint(ClockSpec.LEGACY, 0x08, ByteArray(256)),
            ) {
              setSb(0x99)
              startSending()
              repeat(8) { sendBit() }
              setSb(0x66)
              startSending()
              // Leave six clocks for continuePeripheral so this covers an armed ID 99 wire state.
              repeat(2) { sendBit() }
            },
        )

    cases.forEach { case ->
      val endpoint = case.endpoint
      val idle = StateGraph.capture(endpoint.captureState())
      case.setup(endpoint)
      val captured = StateGraph.capture(endpoint.captureState())
      assertNotEquals(idle, captured, "${case.name} setup did not exercise owned state")
      case.clearObserved()
      val expectedTrace = continuePeripheral(endpoint) + case.observed()
      val expectedState = StateGraph.capture(endpoint.captureState())
      disturbPeripheral(endpoint)

      val restored = StateGraph.restore(captured)
      StateSemantics.validate(restored)
      @Suppress("UNCHECKED_CAST")
      endpoint.restoreState(restored as ComponentState<SerialEndpoint>?)
      case.clearObserved()
      val actualTrace = continuePeripheral(endpoint) + case.observed()
      assertEquals(expectedTrace, actualTrace, "${case.name} continuation trace")
      assertEquals(expectedState, StateGraph.capture(endpoint.captureState()), case.name)
    }

    assertEquals(EXPECTED_SERIAL_FAMILIES, cases.map { it.name }.toSet())
  }

  @Test
  fun activeSgbMultipacketAndInfraredPulsePhasesContinueDeterministically() {
    EventBusImpl().use { sgbBus ->
      val sgb = SuperGameboy(sgbBus)
      val idle = StateGraph.capture(sgb.captureState())
      val firstPacket = IntArray(16).also {
        it[0] = (0x05 shl 3) or 2 // first half of a valid two-packet ATTR_LIN
        it[1] = 15
        repeat(14) { index -> it[index + 2] = (1 shl 5) or index }
      }
      sgbBus.post(SuperGameboy.PacketReceivedEvent(firstPacket))
      val partial = StateGraph.capture(sgb.captureState())
      assertNotEquals(idle, partial)

      sgbBus.post(SuperGameboy.PacketReceivedEvent(IntArray(16)))
      val expected = StateGraph.capture(sgb.captureState())
      val restored = StateGraph.restore(partial)
      StateSemantics.validate(restored)
      @Suppress("UNCHECKED_CAST")
      sgb.restoreState(restored as ComponentState<SuperGameboy>)
      sgbBus.post(SuperGameboy.PacketReceivedEvent(IntArray(16)))
      assertEquals(expected, StateGraph.capture(sgb.captureState()))
    }

    EventBusImpl().use { irBus ->
      val infrared = InfraredPort(true, SpeedMode(true))
      infrared.init(irBus)
      val idle = StateGraph.capture(infrared.captureState())
      infrared.setByte(0xff56, 0xc0)
      irBus.post(FullChanger.TransformEvent(5))
      infrared.getByte(0xff56) // arm the first pulse at the emulated polling edge
      repeat(30) { infrared.tick() }
      val active = StateGraph.capture(infrared.captureState())
      assertNotEquals(idle, active)

      val expectedTrace = continueInfrared(infrared, 500)
      val expected = StateGraph.capture(infrared.captureState())
      infrared.setByte(0xff56, 0xc0)
      irBus.post(FullChanger.TransformEvent(9))
      infrared.getByte(0xff56)
      repeat(41) { infrared.tick() }
      val restored = StateGraph.restore(active)
      StateSemantics.validate(restored)
      @Suppress("UNCHECKED_CAST")
      infrared.restoreState(restored as ComponentState<InfraredPort>)
      val actualTrace = continueInfrared(infrared, 500)
      assertEquals(expectedTrace, actualTrace)
      assertEquals(expected, StateGraph.capture(infrared.captureState()))

      irBus.post(FullChanger.TransformEvent(12))
      val armed = StateGraph.capture(infrared.captureState())
      val armedChanger = armed.record(FULL_CHANGER_MEMENTO)
      assertTrue(armedChanger.bool("armed"))
      assertTrue(!armedChanger.bool("running"))
      StateSemantics.validate(StateGraph.restore(armed))

      infrared.getByte(0xff56)
      repeat(30) { infrared.tick() }
      val running = StateGraph.capture(infrared.captureState())
      val runningChanger = running.record(FULL_CHANGER_MEMENTO)
      assertTrue(!runningChanger.bool("armed"))
      assertTrue(runningChanger.bool("running"))
      StateSemantics.validate(StateGraph.restore(running))

      repeat(100_000) { infrared.tick() }
      val completed = StateGraph.capture(infrared.captureState())
      val completedChanger = completed.record(FULL_CHANGER_MEMENTO)
      assertTrue(!completedChanger.bool("armed"))
      assertTrue(!completedChanger.bool("running"))
      assertEquals(
          completedChanger.intArray("schedule").size,
          completedChanger.int("index"),
      )
      StateSemantics.validate(StateGraph.restore(completed))

      irBus.post(FullChanger.TransformEvent(17))
      infrared.getByte(0xff56)
      val completedContinuation = continueInfrared(infrared, 600)
      val completedExpected = StateGraph.capture(infrared.captureState())
      @Suppress("UNCHECKED_CAST")
      infrared.restoreState(
          StateGraph.restore(completed) as ComponentState<InfraredPort>)
      irBus.post(FullChanger.TransformEvent(17))
      infrared.getByte(0xff56)
      assertEquals(completedContinuation, continueInfrared(infrared, 600))
      assertEquals(completedExpected, StateGraph.capture(infrared.captureState()))
    }
  }

  @Test
  fun mbc6FlashUnlockIdAndProgramPhasesContinueDeterministically() {
    val mbc6 = Mbc6(mutableRom(), Battery.NULL_BATTERY)
    EventBusImpl().use(mbc6::init)
    enableMbc6Flash(mbc6)

    // Capture after AA; the continuation completes the unlock and enters software-ID mode.
    mbc6.setByte(0x5555, 0xaa)
    val unlock = StateGraph.capture(mbc6.captureState())
    assertEquals(1, unlock.record(MBC6_MEMENTO).int("flashCommandState"))
    assertMapperContinuation(
        mbc6,
        unlock,
        disturb = { resetMbc6Flash(it) },
    ) {
      it.setByte(0x4aaa, 0x55)
      it.setByte(0x5555, 0x90)
      listOf(it.getByte(0x4000), it.getByte(0x4001))
    }
    assertTrue(StateGraph.capture(mbc6.captureState()).record(MBC6_MEMENTO).bool("flashIdMode"))

    resetMbc6Flash(mbc6)
    mbc6.setByte(0x5555, 0xaa)
    mbc6.setByte(0x4aaa, 0x55)
    mbc6.setByte(0x5555, 0xa0)
    val program = StateGraph.capture(mbc6.captureState())
    assertTrue(program.record(MBC6_MEMENTO).bool("flashProgramMode"))
    assertMapperContinuation(
        mbc6,
        program,
        disturb = { resetMbc6Flash(it) },
    ) {
      it.setByte(0x4321, 0x12)
      listOf(it.getByte(0x4321))
    }
  }

  @Test
  fun mbc7EepromMidWriteContinuesDeterministically() {
    val mbc7 = Mbc7(mutableRom(), Battery.NULL_BATTERY)
    EventBusImpl().use(mbc7::init)
    mbc7.setByte(0x0000, 0x0a)
    mbc7.setByte(0x4000, 0x40)
    mbc7Command(mbc7, 0b00, 0b11000000) // EWEN
    mbc7.setByte(0xa080, 0)
    mbc7Command(mbc7, 0b01, 0x12) // WRITE
    mbc7SendBits(mbc7, 0xbe, 8)

    val captured = StateGraph.capture(mbc7.captureState())
    val eeprom = captured.record(MBC7_EEPROM_MEMENTO)
    assertEquals("WRITING", eeprom.enumName("state"))
    assertEquals(8, eeprom.int("bitsRead"))
    assertEquals(0xbe, eeprom.int("writeValue"))

    assertMapperContinuation(
        mbc7,
        captured,
        disturb = {
          it.setByte(0xa080, 0)
          mbc7Command(it, 0b01, 0x12)
          mbc7SendBits(it, 0, 16)
          it.setByte(0xa080, 0)
        },
    ) {
      mbc7SendBits(it, 0xef, 8)
      it.setByte(0xa080, 0)
      mbc7Command(it, 0b10, 0x12)
      val word = mbc7ReadWord(it)
      it.setByte(0xa080, 0)
      listOf(word)
    }
  }

  @Test
  fun mbc5RumbleLatchContinuesDeterministically() {
    val log = mutableListOf<Boolean>()
    val mbc5 = Mbc5(mutableRom(0x1e), Battery.NULL_BATTERY)
    EventBusImpl().use { bus ->
      bus.register({ event -> log += event.on() }, RumbleEvent::class.java)
      mbc5.init(bus)
      mbc5.setByte(0x0000, 0x0a)
      mbc5.setByte(0x4000, 0x0b) // RAM bank 3 + motor on
      val captured = StateGraph.capture(mbc5.captureState())
      assertTrue(captured.record(MBC5_MEMENTO).bool("motorOn"))
      assertEquals(3, captured.record(MBC5_MEMENTO).int("selectedRamBank"))

      log.clear()
      val expectedTrace = run {
        mbc5.setByte(0x4000, 0x03)
        log.toList()
      }
      val expectedState = StateGraph.capture(mbc5.captureState())
      mbc5.setByte(0x4000, 0x08)
      @Suppress("UNCHECKED_CAST")
      mbc5.restoreState(StateGraph.restore(captured) as ComponentState<MemoryController>)
      log.clear()
      mbc5.setByte(0x4000, 0x03)
      assertEquals(listOf(false), expectedTrace)
      assertEquals(expectedTrace, log)
      assertEquals(expectedState, StateGraph.capture(mbc5.captureState()))
    }
  }

  @Test
  fun datelOuterFlashProgramEraseAndIdPhasesContinueDeterministically() {
    val datel = datelWithRtcSlot(mutableFlashRom())
    EventBusImpl().use(datel::init)

    datel.setByte(0x5555, 0xaa)
    datel.setByte(0x2aaa, 0x55)
    datel.setByte(0x5555, 0xa0)
    val program = StateGraph.capture(datel.captureState())
    assertEquals(3, program.record(DATEL_MEMENTO).int("flashCycle"))
    assertTrue(program.record(DATEL_MEMENTO).field("slotMemento") is RecordState)
    assertMapperContinuation(datel, program, disturb = { resetDatelFlash(it) }) {
      it.setByte(0x3000, 0x12)
      listOf(it.getByte(0x3000))
    }

    datel.setByte(0x5555, 0xaa)
    datel.setByte(0x2aaa, 0x55)
    datel.setByte(0x5555, 0x80)
    datel.setByte(0x5555, 0xaa)
    val erase = StateGraph.capture(datel.captureState())
    val eraseRecord = erase.record(DATEL_MEMENTO)
    assertTrue(eraseRecord.bool("flashErasePending"))
    assertEquals(1, eraseRecord.int("flashCycle"))
    assertMapperContinuation(datel, erase, disturb = { resetDatelFlash(it) }) {
      it.setByte(0x2aaa, 0x55)
      it.setByte(0x3000, 0x30)
      listOf(it.getByte(0x3000))
    }

    datel.setByte(0x5555, 0xaa)
    datel.setByte(0x2aaa, 0x55)
    datel.setByte(0x5555, 0x90)
    val id = StateGraph.capture(datel.captureState())
    assertTrue(id.record(DATEL_MEMENTO).bool("flashIdMode"))
    assertMapperContinuation(datel, id, disturb = { resetDatelFlash(it) }) {
      listOf(it.getByte(0), it.getByte(1))
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
    if (controller is Vf001Zook) controller.setByte(0x7081, 0x5f)
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
    if (controller is XploderGb) {
      controller.setByte(0x0006, 0x7d)
      controller.setByte(0x0007, 0x0f)
    }
    if (controller is Gowin) controller.setByte(0x6080, 0x20)
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

  private fun assertMapperContinuation(
      controller: MemoryController,
      captured: StateValue,
      disturb: (MemoryController) -> Unit,
      continuation: (MemoryController) -> List<Int>,
  ) {
    val expectedTrace = continuation(controller)
    val expectedState = StateGraph.capture(controller.captureState())
    disturb(controller)
    val restored = StateGraph.restore(captured)
    StateSemantics.validate(restored)
    @Suppress("UNCHECKED_CAST")
    controller.restoreState(restored as ComponentState<MemoryController>)
    val actualTrace = continuation(controller)
    assertEquals(expectedTrace, actualTrace)
    assertEquals(expectedState, StateGraph.capture(controller.captureState()))
  }

  private fun enableMbc6Flash(controller: MemoryController) {
    controller.setByte(0x1000, 1)
    controller.setByte(0x0c00, 1)
    controller.setByte(0x2800, 0x08)
  }

  private fun resetMbc6Flash(controller: MemoryController) {
    controller.setByte(0x5555, 0xaa)
    controller.setByte(0x4aaa, 0x55)
    controller.setByte(0x5555, 0xf0)
  }

  private fun mbc7SendBit(controller: MemoryController, bit: Boolean) {
    val data = if (bit) 0x02 else 0
    controller.setByte(0xa080, 0x80 or data)
    controller.setByte(0xa080, 0xc0 or data)
  }

  private fun mbc7SendBits(controller: MemoryController, value: Int, count: Int) {
    for (bit in count - 1 downTo 0) mbc7SendBit(controller, ((value shr bit) and 1) != 0)
  }

  private fun mbc7Command(controller: MemoryController, op: Int, address: Int) {
    mbc7SendBit(controller, true)
    mbc7SendBits(controller, op, 2)
    mbc7SendBits(controller, address, 8)
  }

  private fun mbc7ReadWord(controller: MemoryController): Int {
    var result = 0
    repeat(17) { index ->
      controller.setByte(0xa080, 0x80)
      controller.setByte(0xa080, 0xc0)
      if (index > 0) result = (result shl 1) or (controller.getByte(0xa080) and 1)
    }
    return result
  }

  private fun resetDatelFlash(controller: MemoryController) {
    controller.setByte(0x5555, 0xaa)
    controller.setByte(0x2aaa, 0x55)
    controller.setByte(0x5555, 0xf0)
  }

  private fun StateValue.record(className: String): RecordState {
    fun find(value: StateValue): RecordState? =
        when (value) {
          is RecordState ->
              if (StateTypeRegistry.recordClassNames[value.typeId - 1] == className) value
              else value.fields.firstNotNullOfOrNull { find(it.value) }
          is ObjectArrayState -> value.values.firstNotNullOfOrNull(::find)
          is ListState -> value.values.firstNotNullOfOrNull(::find)
          is Int32MapState -> value.entries.firstNotNullOfOrNull { find(it.value) }
          else -> null
        }
    return requireNotNull(find(this)) { "Missing $className" }
  }

  private fun RecordState.field(name: String): StateValue = fields.single { it.name == name }.value
  private fun RecordState.int(name: String): Int = (field(name) as Int32State).value
  private fun RecordState.bool(name: String): Boolean = (field(name) as BooleanState).value
  private fun RecordState.intArray(name: String): IntArray = (field(name) as Int32ArrayState).copyValue()
  private fun RecordState.enumName(name: String): String {
    val enum = field(name) as EnumState
    return (StateTypeRegistry.enumClasses[enum.typeId - 1].enumConstants[enum.ordinal] as Enum<*>).name
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

  private fun mutableRom(type: Int = 0x1b): Rom {
    val bytes = ByteArray(0x200000)
    bytes[0x147] = type.toByte() // constructors are selected explicitly except MBC5 rumble wiring.
    bytes[0x148] = 0x06
    bytes[0x149] = 0x03
    return Rom(bytes)
  }

  private fun mutableFlashRom(): Rom {
    val bytes = ByteArray(0x200000) { 0xff.toByte() }
    bytes[0x147] = 0x1b
    bytes[0x148] = 0x06
    bytes[0x149] = 0x03
    return Rom(bytes)
  }

  private fun multicartFixture(): Rom {
    val bytes = ByteArray(128 * 0x4000)
    val selected = 0x40 * 0x4000
    bytes[selected + 0x143] = 0x80.toByte()
    bytes[selected + 0x147] = 0x10
    bytes[selected + 0x148] = 0x05
    return Rom(bytes)
  }

  private companion object {
    const val MBC5_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Mbc5\$Mbc5State"
    const val MBC6_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Mbc6\$Mbc6State"
    const val MBC7_EEPROM_MEMENTO =
        "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom\$EepromState"
    const val DATEL_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Datel\$DatelState"
    const val FULL_CHANGER_MEMENTO = "eu.rekawek.coffeegb.core.ir.FullChanger\$FullChangerState"
    val DEFAULT_MAPPER_PROBES = listOf(0x0100, 0x4000, 0x7000, 0x7fe1, 0xa000, 0xa001)

    val EXPECTED_MAPPER_FAMILIES =
        setOf(
            "BasicRom",
            "Mbc1",
            "Gowin",
            "Mbc2",
            "Mbc3",
            "Mbc5",
            "Mbc5Multicart",
            "LiCheng",
            "XploderGb",
            "Vf001Zook",
            "Vf001General",
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
            "Hitek",
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
            "MobileAdapter",
        )
  }
}
