package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.genie.AddPatches
import eu.rekawek.coffeegb.core.genie.GameGeniePatch
import eu.rekawek.coffeegb.core.genie.GameSharkPatch
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint
import eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class DetachedStateTest {

  @Test
  fun nontrivialPpuAndDisplayStateRoundTripsAndContinuesDeterministically() {
    session().use { session ->
      repeat(31_337) { session.gameboy.tick() }
      assertNotEquals(0, session.gameboy.gpu.ticksInLine)
      session.heldButtons = setOf(Button.A, Button.RIGHT)

      val captured = session.captureDetachedState()
      val display = captured.machine.record(DISPLAY_MEMENTO)
      val buffer = display.intArray("buffer")
      val lastFrame = display.intArray("lastFrame")
      assertEquals(160 * 144, buffer.size)
      assertEquals(160 * 144, lastFrame.size)

      repeat(4_321) { session.gameboy.tick() }
      val expectedContinuation = session.captureDetachedState().machine
      session.heldButtons = setOf(Button.B)

      session.restoreDetachedState(captured)
      assertEquals(setOf(Button.A, Button.RIGHT), session.heldButtons)
      val restoredDisplay = session.captureDetachedState().machine.record(DISPLAY_MEMENTO)
      assertContentEquals(buffer, restoredDisplay.intArray("buffer"))
      assertContentEquals(lastFrame, restoredDisplay.intArray("lastFrame"))

      repeat(4_321) { session.gameboy.tick() }
      val actualContinuation = session.captureDetachedState().machine
      assertEquals(expectedContinuation, actualContinuation, firstDifference(expectedContinuation.root, actualContinuation.root))
    }
  }

  @Test
  fun doubleSpeedActiveDmaHdmaSerialAndAudioContinueDeterministically() {
    session(configuration(cgbSpeedSwitchRom()).setGameboyType(GameboyType.CGB)).use { session ->
      repeat(140_000) {
        if (session.gameboy.speedMode.speedMode == 1 || session.gameboy.cpu.isSpeedSwitching) {
          session.gameboy.tick()
        }
      }
      assertEquals(2, session.gameboy.speedMode.speedMode)

      val bus = session.gameboy.addressSpace
      bus.setByte(0xff26, 0x80)
      bus.setByte(0xff11, 0x80)
      bus.setByte(0xff12, 0xf3)
      bus.setByte(0xff13, 0x40)
      bus.setByte(0xff14, 0x87)
      bus.setByte(0xff01, 0xa5)
      bus.setByte(0xff02, 0x81)
      bus.setByte(0xff51, 0xc0)
      bus.setByte(0xff52, 0x00)
      bus.setByte(0xff53, 0x00)
      bus.setByte(0xff54, 0x00)
      bus.setByte(0xff55, 0x80)
      bus.setByte(0xff46, 0xc0)

      val captured = session.captureDetachedState()
      assertTrue(captured.machine.record(DMA_MEMENTO).bool("transferInProgress"))
      assertTrue(captured.machine.record(HDMA_MEMENTO).bool("transferInProgress"))
      assertTrue(captured.machine.record(SPEED_MEMENTO).bool("currentSpeed"))
      assertTrue(captured.machine.record(SOUND_MODE_MEMENTO).bool("channelEnabled"))
      assertEquals(0x81, captured.machine.record(SERIAL_PORT_MEMENTO).int("sc"))

      repeat(96) { session.gameboy.tick() }
      val expected = session.captureDetachedState()
      session.restoreDetachedState(captured)
      repeat(96) { session.gameboy.tick() }
      assertEquals(expected, session.captureDetachedState())
    }
  }

  @Test
  fun rootOwnsExactlyOneDisplayAndArraysAreDetachedAcrossCaptures() {
    session().use { session ->
      repeat(8_765) { session.gameboy.tick() }
      val first = session.captureDetachedState()

      assertEquals(1, first.machine.recordCount(DISPLAY_MEMENTO))
      val owned = first.machine.record(DISPLAY_MEMENTO).arrayState("buffer")
      val original = owned.copyValue()
      val escapedCopy = owned.copyValue().also { it[0] = it[0] xor 0x00ffffff }
      assertNotEquals(escapedCopy[0], owned.copyValue()[0])

      repeat(1_111) { session.gameboy.tick() }
      val second = session.captureDetachedState()
      session.restoreDetachedState(first)
      assertContentEquals(original, first.machine.record(DISPLAY_MEMENTO).intArray("buffer"))

      session.restoreDetachedState(second)
      assertEquals(second, session.captureDetachedState())
      assertContentEquals(original, first.machine.record(DISPLAY_MEMENTO).intArray("buffer"))
    }
  }

  @Test
  fun completeGraphIsValidatedBeforeMutationAndFailedApplyIsAtomic() {
    session().use { session ->
      repeat(2_000) { session.gameboy.tick() }
      val before = session.captureDetachedState()
      val invalidRoot = RecordState(before.machine.root.typeId, before.machine.root.fields.dropLast(1))
      val invalid = SessionState(
          MachineState(invalidRoot, before.machine.rtcRuntime, before.machine.hardware),
          before.serialPeripheral,
          before.serialState,
          before.serialRuntime,
          before.heldButtons,
      )

      assertFailsWith<StateApplyException> { session.restoreDetachedState(invalid) }
      assertEquals(before, session.captureDetachedState())
    }
  }

  @Test
  fun mapperEndpointAndInvariantDimensionsAreRejectedBeforeLiveMutation() {
    session().use { target ->
      session(configuration(mbc3Rom())).use { differentMapper ->
        val before = target.captureDetachedState()
        val mbc3 = differentMapper.captureDetachedState().machine.record(MBC3_MEMENTO)
        val wrongRoot =
            before.machine.root.replaceRecordField(
                CARTRIDGE_MEMENTO, "memoryControllerMemento", mbc3)
        val wrongMapper = before.withMachineRoot(wrongRoot)
        val stages = mutableListOf<ApplyStage>()

        assertFailsWith<StateApplyException> {
          DetachedStateAdapter.apply(target, wrongMapper) { stages += it }
        }
        assertTrue(stages.isEmpty())
        assertEquals(before, target.captureDetachedState())

        val display = before.machine.record(DISPLAY_MEMENTO)
        val shortBuffer =
            Int32ArrayState(display.intArray("buffer").copyOf(display.intArray("buffer").size - 1))
        val wrongDimensions =
            before.withMachineRoot(
                before.machine.root.replaceRecordField(DISPLAY_MEMENTO, "buffer", shortBuffer))
        assertFailsWith<StateApplyException> {
          DetachedStateAdapter.apply(target, wrongDimensions) { stages += it }
        }
        assertTrue(stages.isEmpty())
        assertEquals(before, target.captureDetachedState())
      }
    }

    val receiver = ByteReceivingSerialEndpoint { _ -> }
    session(receiver).use { target ->
      session(GameboyPrinterSerialEndpoint { _, _, _, _, _, _ -> }).use { printer ->
        val before = target.captureDetachedState()
        val printerState = printer.captureDetachedState().serialState
        val wrongEndpoint =
            SessionState(
                before.machine,
                before.serialPeripheral,
                printerState,
                before.serialRuntime,
                before.heldButtons,
            )
        val stages = mutableListOf<ApplyStage>()
        assertFailsWith<StateApplyException> {
          DetachedStateAdapter.apply(target, wrongEndpoint) { stages += it }
        }
        assertTrue(stages.isEmpty())
        assertEquals(before, target.captureDetachedState())
      }
    }
  }

  @Test
  fun requiredArraysAndSemanticCursorsAreRejectedBeforeAnyLiveMutation() {
    session(configuration(mbc3Rom())).use { session ->
      repeat(2_000) { session.gameboy.tick() }
      val before = session.captureDetachedState()
      val display = before.machine.record(DISPLAY_MEMENTO)
      val queue = before.machine.record(INT_QUEUE_MEMENTO)
      val dmgFifo = before.machine.record(DMG_FIFO_MEMENTO)
      val sgbDisplay = before.machine.record(SGB_DISPLAY_MEMENTO)
      val attributeFiles = sgbDisplay.field("attributeFiles") as ObjectArrayState
      val firstAttributeFile = attributeFiles.values.first() as Int32ArrayState
      val invalidAttributeFile =
          Int32ArrayState(firstAttributeFile.copyValue().also { it[0] = 4 })
      val paletteMap = sgbDisplay.intArray("paletteMap").also { it[0] = 4 }

      val invalidRoots =
          listOf(
              before.machine.root.replaceRecordField(DISPLAY_MEMENTO, "buffer", NullState),
              before.machine.root.replaceRecordField(INT_QUEUE_MEMENTO, "array", NullState),
              before.machine.root.replaceRecordField(MBC3_MEMENTO, "ram", NullState),
              before.machine.root.replaceRecordField(SGB_DISPLAY_MEMENTO, "palettes", NullState),
              before.machine.root.replaceRecordField(
                  INT_QUEUE_MEMENTO,
                  "size",
                  Int32State(queue.intArray("array").size + 1),
              ),
              before.machine.root.replaceRecordField(
                  INT_QUEUE_MEMENTO,
                  "offset",
                  Int32State(queue.intArray("array").size),
              ),
              before.machine.root.replaceRecordField(
                  DISPLAY_MEMENTO,
                  "i",
                  Int32State(display.intArray("buffer").size + 1),
              ),
              before.machine.root.replaceRecordField(
                  DMG_FIFO_MEMENTO,
                  "delaySize",
                  Int32State(dmgFifo.intArray("delayEntry").size + 1),
              ),
              before.machine.root.replaceRecordField(
                  POLYNOMIAL_COUNTER_MEMENTO,
                  "alignment",
                  Int32State(4),
              ),
              before.machine.root.replaceRecordField(
                  SGB_DISPLAY_MEMENTO,
                  "paletteMap",
                  Int32ArrayState(paletteMap),
              ),
              before.machine.root.replaceRecordField(
                  SGB_DISPLAY_MEMENTO,
                  "attributeFiles",
                  ObjectArrayState(
                      attributeFiles.values.mapIndexed { index, value ->
                        if (index == 0) invalidAttributeFile else value
                      }),
              ),
              before.machine.root.replaceRecordField(
                  SGB_DISPLAY_MEMENTO,
                  "attributeFiles",
                  ObjectArrayState(
                      attributeFiles.values.mapIndexed { index, value ->
                        if (index == 0) NullState else value
                      }),
              ),
              before.machine.root.replaceRecordField(
                  GENIE_MEMENTO,
                  "patches",
                  Int32MapState(listOf(Int32MapEntry(0x1234, NullState))),
              ),
              before.machine.root.replaceRecordField(
                  GENIE_MEMENTO,
                  "patches",
                  Int32MapState(
                      listOf(Int32MapEntry(0x1234, ListState(listOf(NullState))))),
              ),
          )

      invalidRoots.forEachIndexed { index, root ->
        val stages = mutableListOf<ApplyStage>()
        assertFailsWith<StateApplyException>("invalid case $index") {
          DetachedStateAdapter.apply(session, before.withMachineRoot(root)) { stages += it }
        }
        assertTrue(stages.isEmpty(), "invalid case $index reached live mutation")
        assertEquals(before, session.captureDetachedState(), "invalid case $index changed the session")
      }

      // Inclusive display completion and a full circular queue at its last physical slot are
      // valid boundary states. Validate the reconstructed records without mutating the session.
      StateSemantics.validate(
          StateGraph.restore(
              display.replaceField("i", Int32State(display.intArray("buffer").size))))
      StateSemantics.validate(
          StateGraph.restore(
              queue
                  .replaceField("size", Int32State(queue.intArray("array").size))
                  .replaceField("offset", Int32State(queue.intArray("array").lastIndex))))
      StateSemantics.validate(
          StateGraph.restore(
              dmgFifo.replaceField(
                  "delaySize",
                  Int32State(dmgFifo.intArray("delayEntry").size),
              )))
    }

    session(configuration(cgbIdleRom()).setGameboyType(GameboyType.CGB)).use { session ->
      repeat(2_000) { session.gameboy.tick() }
      val before = session.captureDetachedState()
      val fifo = before.machine.record(COLOR_FIFO_MEMENTO)
      val invalid =
          before.withMachineRoot(
              before.machine.root.replaceRecordField(
                  COLOR_FIFO_MEMENTO,
                  "delaySize",
                  Int32State(fifo.intArray("delayEntry").size + 1),
              ))
      assertRejectedBeforeMutation(session, before, invalid, "CGB delay-line capacity")
      StateSemantics.validate(
          StateGraph.restore(
              fifo.replaceField(
                  "delaySize",
                  Int32State(fifo.intArray("delayEntry").size),
              )))
    }
  }

  @Test
  fun auditedNullableRecordArrayEnumCollectionPayloadAndRowCategoriesRemainCompatible() {
    session().use { session ->
      repeat(2_000) { session.gameboy.tick() }
      val state = session.captureDetachedState().machine

      // Legacy duplicate root display record (record), last-frame cache (primitive array),
      // unpublished HDMA mode (enum), and old pending-write inventory (list).
      val legacyDisplayRoot =
          state.root.replaceRecordField(GAMEBOY_MEMENTO, "displayMemento", state.record(DISPLAY_MEMENTO))
      StateGraph.validateCompatible(legacyDisplayRoot, state.root, "legacy-root-display")
      StateGraph.validateCompatible(
          state.root.replaceRecordField(DISPLAY_MEMENTO, "lastFrame", NullState),
          state.root,
          "legacy-last-frame",
      )
      StateGraph.validateCompatible(
          state.root.replaceRecordField(HDMA_MEMENTO, "gpuMode", NullState),
          state.root,
          "unpublished-hdma-mode",
      )
      StateGraph.validateCompatible(
          state.root.replaceRecordField(GPU_MEMENTO, "pendingPpuWrites", NullState),
          state.root,
          "legacy-pending-writes",
      )

      // Historical PAL_TRN state may have an unavailable system-palette row.
      val display = state.record(SGB_DISPLAY_MEMENTO)
      val systemPalettes = display.field("systemPalettes") as ObjectArrayState
      val withNullRow =
          display.replaceField(
              "systemPalettes",
              ObjectArrayState(
                  systemPalettes.values.mapIndexed { index, value ->
                    if (index == 0) NullState else value
                  }),
          )
      StateGraph.validateCompatible(withNullRow, display, "nullable-sgb-row")
    }

    val transfer = transferCommand(0x14)
    val transferWithPayload = transfer.replaceField("dataTransfer", Int32ArrayState(IntArray(0x1000)))
    StateGraph.validateCompatible(transferWithPayload, transfer, "optional-transfer-payload")
    StateSemantics.validate(StateGraph.restore(transferWithPayload))

    val superGameboy = session().use { it.captureDetachedState().machine.record(SUPER_GAMEBOY_MEMENTO) }
    val withWaiting = superGameboy.replaceField("waitingTransferCommandMemento", transfer)
    StateGraph.validateCompatible(withWaiting, superGameboy, "optional-waiting-transfer")
    StateSemantics.validate(StateGraph.restore(withWaiting))

    val background = session().use { it.captureDetachedState().machine.record(BACKGROUND_MEMENTO) }
    val withPicture = background.replaceField("pendingPictureMemento", transfer)
    StateGraph.validateCompatible(withPicture, background, "optional-pending-picture")
    StateSemantics.validate(StateGraph.restore(withPicture))

    val barcode = BarcodeBoySerialEndpoint()
    val idleBarcode = StateGraph.capture(barcode.saveToMemento())
    repeat(4) {
      barcode.startSending()
      repeat(8) { barcode.sendBit() }
    }
    barcode.scan("4901234567894")
    barcode.setExternalTransfer(true)
    barcode.recvBit()
    val sendingBarcode = StateGraph.capture(barcode.saveToMemento())
    StateGraph.validateCompatible(sendingBarcode, idleBarcode, "active-barcode-data")
    StateSemantics.validate(StateGraph.restore(sendingBarcode))
  }

  @Test
  fun batteryAndDatelSlotPresenceAreTargetIdentityAndRejectBeforeMutation() {
    val basicWithoutBattery = configuration(basicRom()).setSupportBatterySave(false)
    val basicWithBattery =
        configuration(basicRom())
            .setBatteryData(ByteArray(0x2000))
            .setSupportBatterySave(false)
    assertCrossPresenceRejected(basicWithoutBattery, basicWithBattery)
    assertCrossPresenceRejected(basicWithBattery, basicWithoutBattery)

    val time = VirtualTimeSource(120_000)
    val datelWithoutSlot =
        configuration(datelRom())
            .setGameboyType(GameboyType.CGB)
            .setRtcTimeSource(time)
            .setSupportBatterySave(false)
    val datelWithSlot = datelConfiguration(mbc3Rom(), time)
    assertCrossPresenceRejected(datelWithoutSlot, datelWithSlot)
    assertCrossPresenceRejected(datelWithSlot, datelWithoutSlot)
  }

  @Test
  fun malformedBarcodeMementoAndRuntimeRejectBeforeMutation() {
    val endpoint = BarcodeBoySerialEndpoint()
    session(endpoint).use { session ->
      repeat(4) {
        endpoint.startSending()
        repeat(8) { endpoint.sendBit() }
      }
      endpoint.scan("4901234567894")
      endpoint.setExternalTransfer(true)
      endpoint.recvBit()
      val before = session.captureDetachedState()
      val serial = before.serialState as RecordState
      val idleState = StateGraph.capture(BarcodeBoySerialEndpoint().saveToMemento()) as RecordState
      val activeData = serial.intArray("data")
      val malformedSerial =
          listOf(
              serial.replaceField("data", NullState),
              serial.replaceField("state", idleState.field("state")),
              serial.replaceField("data", Int32ArrayState(activeData.copyOf(29))),
              serial.replaceField(
                  "data",
                  Int32ArrayState(activeData.clone().also { it[0] = 0x100 }),
              ),
          )
      val malformedRuntime =
          listOf(
              BarcodeBoyRuntimeState(true, IntArray(29)),
              BarcodeBoyRuntimeState(true, IntArray(30).also { it[0] = 0x100 }),
          )

      malformedSerial.forEachIndexed { index, serialState ->
        val invalid =
            SessionState(
                before.machine,
                before.serialPeripheral,
                serialState,
                before.serialRuntime,
                before.heldButtons,
            )
        assertRejectedBeforeMutation(session, before, invalid, "serial case $index")
      }
      malformedRuntime.forEachIndexed { index, runtime ->
        val invalid =
            SessionState(
                before.machine,
                before.serialPeripheral,
                before.serialState,
                runtime,
                before.heldButtons,
            )
        assertRejectedBeforeMutation(session, before, invalid, "runtime case $index")
      }
    }
  }

  @Test
  fun reachableWaveRtcHdmaAndLcdEnableStatesApplyAndContinueDeterministically() {
    session().use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0xff26, 0x80)
      bus.setByte(0xff30, 0xab)
      bus.setByte(0xff1a, 0x80)
      bus.setByte(0xff1c, 0x20)
      bus.setByte(0xff1d, 0xff)
      bus.setByte(0xff1e, 0x87)
      repeat(24) { session.gameboy.tick() }
      val wave = session.captureDetachedState()
      assertTrue(wave.machine.record(SOUND_MODE3_MEMENTO).int("lastReadAddr") in 0xff30..0xff3f)
      assertContinuation(session, wave, 192)
    }

    session(configuration(mbc3Rom())).use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0x0000, 0x0a)
      listOf(0x08 to 63, 0x09 to 63, 0x0a to 31).forEach { (register, value) ->
        bus.setByte(0x4000, register)
        bus.setByte(0xa000, value)
      }
      bus.setByte(0x6000, 0)
      bus.setByte(0x6000, 1)
      val rtc = session.captureDetachedState()
      val clock = rtc.machine.record(RTC_MEMENTO)
      assertEquals(63, clock.int("seconds"))
      assertEquals(63, clock.int("minutes"))
      assertEquals(31, clock.int("hours"))
      assertEquals(63, clock.int("latchedSeconds"))
      assertEquals(63, clock.int("latchedMinutes"))
      assertEquals(31, clock.int("latchedHours"))
      assertContinuation(session, rtc, 4_096)
    }

    session(configuration(cgbIdleRom()).setGameboyType(GameboyType.CGB)).use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0xff51, 0xc0)
      bus.setByte(0xff52, 0x00)
      bus.setByte(0xff53, 0x00)
      bus.setByte(0xff54, 0x00)
      bus.setByte(0xff55, 0x01)
      var hdma = session.captureDetachedState()
      repeat(256) {
        if (hdma.machine.record(HDMA_MEMENTO).int("sourceBytesTransferred") <= 16) {
          session.gameboy.tick()
          hdma = session.captureDetachedState()
        }
      }
      assertTrue(hdma.machine.record(HDMA_MEMENTO).bool("transferInProgress"))
      assertTrue(hdma.machine.record(HDMA_MEMENTO).int("sourceBytesTransferred") > 16)
      assertContinuation(session, hdma, 64)
    }

    session().use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0xff40, 0x11)
      bus.setByte(0xff40, 0x91)
      val lcdEnable = session.captureDetachedState()
      assertEquals(-1, lcdEnable.machine.record(GPU_MEMENTO).int("ticksInLine"))
      assertContinuation(session, lcdEnable, 256)
    }
  }

  @Test
  fun registeredCheatPatchListsApplyAndContinue() {
    session().use { session ->
      session.eventBus.post(
          AddPatches(
              listOf(
                  GameGeniePatch(0x42, 0x1234, -1),
                  GameSharkPatch(8, 2, 0xa123, 0x99),
              )))
      val captured = session.captureDetachedState()
      StateSemantics.validate(StateGraph.restore(captured.machine.record(GENIE_MEMENTO)))
      assertContinuation(session, captured, 128)
    }
  }

  @Test
  fun unexpectedLiveFailureRollsBackMachineRtcEndpointRuntimeAndHeldInput() {
    val time = VirtualTimeSource(120_000)
    val endpoint = BarcodeBoySerialEndpoint()
    val config = configuration(mbc3Rom()).setRtcTimeSource(time)
    session(config, endpoint).use { session ->
      repeat(32) {
        endpoint.startSending()
        repeat(8) { endpoint.sendBit() }
      }
      endpoint.scan("4901234567894")
      endpoint.setExternalTransfer(true)
      session.heldButtons = setOf(Button.START)
      session.gameboy.setCartridgeClockPaused(true)
      val target = session.captureDetachedState()

      time.forward(2, TimeUnit.SECONDS)
      session.gameboy.setCartridgeClockPaused(false)
      repeat(2_000) { session.gameboy.tick() }
      repeat(64) { endpoint.recvBit() }
      endpoint.setExternalTransfer(false)
      session.heldButtons = setOf(Button.B)
      val beforeFailure = session.captureDetachedState()
      var reachedLiveFailure = false

      val failure = assertFailsWith<StateApplyException> {
        DetachedStateAdapter.apply(session, target) { stage ->
          if (stage == ApplyStage.AFTER_MACHINE_MUTATION) {
            reachedLiveFailure = true
            throw InjectedApplyFailure()
          }
        }
      }

      assertTrue(reachedLiveFailure, failure.toString())
      assertEquals(beforeFailure, session.captureDetachedState())
    }
  }

  @Test
  fun datelSlotRtcLocationsUseInjectedTimeAndRoundTripIndependently() {
    val time = VirtualTimeSource(120_000)
    val config = datelConfiguration(mbc3Rom(), time)
    session(config).use { session ->
      repeat(Gameboy.TICKS_PER_SEC / 4) { session.gameboy.tick() }
      session.gameboy.setCartridgeClockPaused(true)
      time.forward(1500, TimeUnit.MILLISECONDS)
      val captured = session.captureDetachedState()
      assertEquals(null, captured.machine.rtcRuntime.primary)
      assertTrue(captured.machine.rtcRuntime.slot?.emulationPaused == true)
      assertEquals(
          3L * Gameboy.TICKS_PER_SEC / 4,
          captured.machine.record(RTC_MEMENTO).long("subSecondTicks"),
      )

      val capturedTime = time.currentTimeMillis()
      time.forward(250, TimeUnit.MILLISECONDS)
      session.gameboy.setCartridgeClockPaused(false)
      repeat(128) { session.gameboy.tick() }
      val expected = session.captureDetachedState()

      time.forward(5, TimeUnit.SECONDS)
      repeat(1_000) { session.gameboy.tick() }
      session.restoreDetachedState(captured)
      time.setCurrentTimeMillis(capturedTime)
      time.forward(250, TimeUnit.MILLISECONDS)
      session.gameboy.setCartridgeClockPaused(false)
      repeat(128) { session.gameboy.tick() }
      assertEquals(expected, session.captureDetachedState())
    }

    listOf(HUC3_MEMENTO to slotRom(0xfe, 0x03), TAMA5_MEMENTO to slotRom(0xfd, 0)).forEach {
        (mementoName, slotRom) ->
      val familyTime = VirtualTimeSource(120_000)
      session(datelConfiguration(slotRom, familyTime)).use { session ->
        familyTime.forward(2, TimeUnit.MINUTES)
        val bus = session.gameboy.addressSpace
        bus.setByte(0x7fe5, 0x10)
        if (mementoName == HUC3_MEMENTO) {
          readHuc3Register(bus, 0)
        } else {
          readTama5Minutes(bus)
        }
        val reference = session.captureDetachedState().machine.record(mementoName).long("lastRtcSecond")
        assertEquals(240L, reference, mementoName)
      }
    }
  }

  @Test
  fun statefulEndpointAndHeldInputAreOwnedWhileCallbackIsExcluded() {
    val received = mutableListOf<Int>()
    val endpoint = ByteReceivingSerialEndpoint(received::add)
    session(endpoint).use { session ->
      endpoint.setSb(0xa5)
      endpoint.startSending()
      repeat(3) { endpoint.sendBit() }
      session.heldButtons = setOf(Button.START)
      val captured = session.captureDetachedState()

      repeat(5) { endpoint.sendBit() }
      session.heldButtons = emptySet()
      assertEquals(listOf(0xa5), received)

      session.restoreDetachedState(captured)
      assertEquals(setOf(Button.START), session.heldButtons)
      repeat(5) { endpoint.sendBit() }
      assertEquals(listOf(0xa5, 0xa5), received)
    }
  }

  @Test
  fun barcodePendingPayloadAndExternalTransferLatchAreDetachedAndRestored() {
    val endpoint = BarcodeBoySerialEndpoint()
    session(endpoint).use { session ->
      repeat(32) {
        endpoint.startSending()
        repeat(8) { endpoint.sendBit() }
      }
      endpoint.scan("4901234567894")
      endpoint.setExternalTransfer(true)
      val captured = session.captureDetachedState()

      repeat(512) { endpoint.recvBit() }
      endpoint.setExternalTransfer(false)
      assertTrue(endpoint.isScanPending)

      session.restoreDetachedState(captured)
      assertEquals(captured, session.captureDetachedState())
      assertTrue(endpoint.isScanPending)
      assertTrue((captured.serialRuntime as BarcodeBoyRuntimeState).transferArmed)
    }
  }

  @Test
  fun unknownStatefulPeripheralFailsExplicitlyInsteadOfBeingOmitted() {
    val endpoint = object : SerialEndpoint {
      override fun setSb(sb: Int) = Unit
      override fun recvBit(): Int = -1
      override fun startSending() = Unit
      override fun sendBit(): Int = 1
      override fun saveToMemento() = null
      override fun restoreFromMemento(memento: eu.rekawek.coffeegb.core.memento.Memento<SerialEndpoint>?) = Unit
    }
    session(endpoint).use { session ->
      val failure = assertFailsWith<StateCaptureException> { session.captureDetachedState() }
      assertTrue(failure.message.orEmpty().contains("Unsupported serial endpoint"))
    }
  }

  private fun session(endpoint: SerialEndpoint = SerialEndpoint.NULL_ENDPOINT): Session =
      session(configuration(), endpoint)

  private fun session(
      configuration: Gameboy.GameboyConfiguration,
      endpoint: SerialEndpoint = SerialEndpoint.NULL_ENDPOINT,
  ): Session = Session(configuration, EventBusImpl(), null, endpoint)

  private fun configuration(): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(ROM)).setBootstrapMode(BootstrapMode.SKIP)

  private fun configuration(bytes: ByteArray): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(bytes)).setBootstrapMode(BootstrapMode.SKIP)

  private fun assertContinuation(session: Session, captured: SessionState, ticks: Int) {
    repeat(ticks) { session.gameboy.tick() }
    val expected = session.captureDetachedState()
    repeat(31) { session.gameboy.tick() }
    session.restoreDetachedState(captured)
    repeat(ticks) { session.gameboy.tick() }
    assertEquals(expected, session.captureDetachedState())
  }

  private fun assertCrossPresenceRejected(
      targetConfiguration: Gameboy.GameboyConfiguration,
      candidateConfiguration: Gameboy.GameboyConfiguration,
  ) {
    session(targetConfiguration).use { target ->
      session(candidateConfiguration).use { candidate ->
        val before = target.captureDetachedState()
        assertRejectedBeforeMutation(
            target,
            before,
            candidate.captureDetachedState(),
            "configuration identity",
        )
      }
    }
  }

  private fun assertRejectedBeforeMutation(
      session: Session,
      before: SessionState,
      invalid: SessionState,
      label: String,
  ) {
    val stages = mutableListOf<ApplyStage>()
    assertFailsWith<StateApplyException>(label) {
      DetachedStateAdapter.apply(session, invalid) { stages += it }
    }
    assertTrue(stages.isEmpty(), "$label reached live mutation")
    assertEquals(before, session.captureDetachedState(), "$label changed the session")
  }

  private fun MachineState.record(className: String): RecordState {
    fun find(value: StateValue): RecordState? =
        when (value) {
          is RecordState ->
              if (MementoClassNames.record(value.typeId) == className) value
              else value.fields.firstNotNullOfOrNull { find(it.value) }
          is ObjectArrayState -> value.values.firstNotNullOfOrNull(::find)
          is ListState -> value.values.firstNotNullOfOrNull(::find)
          is Int32MapState -> value.entries.firstNotNullOfOrNull { find(it.value) }
          else -> null
        }
    return requireNotNull(find(root)) { "Missing $className" }
  }

  private fun RecordState.arrayState(name: String): Int32ArrayState =
      fields.single { it.name == name }.value as Int32ArrayState

  private fun RecordState.field(name: String): StateValue = fields.single { it.name == name }.value

  private fun RecordState.replaceField(name: String, replacement: StateValue): RecordState =
      RecordState(
          typeId,
          fields.map { field -> if (field.name == name) StateField(name, replacement) else field },
      )

  private fun transferCommand(code: Int): RecordState =
      RecordState(
          eu.rekawek.coffeegb.controller.MementoTypeRegistry.recordClassNames.indexOf(TRANSFER_MEMENTO) + 1,
          listOf(
              StateField("packet", Int32ArrayState(IntArray(16).also { it[0] = (code shl 3) or 1 })),
              StateField("dataTransfer", NullState),
          ),
      )

  private fun RecordState.intArray(name: String): IntArray = arrayState(name).copyValue()

  private fun RecordState.int(name: String): Int =
      (fields.single { it.name == name }.value as Int32State).value

  private fun RecordState.bool(name: String): Boolean =
      (fields.single { it.name == name }.value as BooleanState).value

  private fun RecordState.long(name: String): Long =
      (fields.single { it.name == name }.value as Int64State).value

  private fun SessionState.withMachineRoot(root: RecordState): SessionState =
      SessionState(
          MachineState(root, machine.rtcRuntime, machine.hardware),
          serialPeripheral,
          serialState,
          serialRuntime,
          heldButtons,
      )

  private fun RecordState.replaceRecordField(
      ownerClass: String,
      fieldName: String,
      replacement: StateValue,
  ): RecordState {
    fun replace(value: StateValue): StateValue =
        when (value) {
          is RecordState -> {
            val owner = MementoClassNames.record(value.typeId) == ownerClass
            RecordState(
                value.typeId,
                value.fields.map { field ->
                  StateField(
                      field.name,
                      if (owner && field.name == fieldName) replacement else replace(field.value),
                  )
                },
            )
          }
          is ObjectArrayState -> ObjectArrayState(value.values.map(::replace))
          is ListState -> ListState(value.values.map(::replace))
          is Int32MapState ->
              Int32MapState(value.entries.map { Int32MapEntry(it.key, replace(it.value)) })
          else -> value
        }
    return replace(this) as RecordState
  }

  private fun firstDifference(expected: StateValue, actual: StateValue, path: String = "root"): String {
    if (expected == actual) return "states are equal"
    if (expected::class != actual::class) return "$path: ${expected::class} != ${actual::class}"
    return when {
      expected is RecordState && actual is RecordState ->
          expected.fields.indices.firstNotNullOfOrNull { index ->
            val left = expected.fields[index]
            val right = actual.fields.getOrNull(index) ?: return@firstNotNullOfOrNull "$path: missing field"
            if (left.value == right.value) null else firstDifference(left.value, right.value, "$path.${left.name}")
          } ?: "$path: record metadata differs"
      expected is ObjectArrayState && actual is ObjectArrayState ->
          expected.values.indices.firstNotNullOfOrNull { index ->
            if (expected.values[index] == actual.values[index]) null
            else firstDifference(expected.values[index], actual.values[index], "$path[$index]")
          } ?: "$path: array metadata differs"
      expected is ListState && actual is ListState ->
          expected.values.indices.firstNotNullOfOrNull { index ->
            if (expected.values[index] == actual.values[index]) null
            else firstDifference(expected.values[index], actual.values[index], "$path[$index]")
          } ?: "$path: list metadata differs"
      else -> "$path: $expected != $actual"
    }
  }

  private object MementoClassNames {
    fun record(typeId: Int): String =
        eu.rekawek.coffeegb.controller.MementoTypeRegistry.recordClasses[typeId - 1].name
  }

  private fun cgbSpeedSwitchRom(): ByteArray {
    val rom = ByteArray(0x8000)
    rom[0x100] = 0x3e
    rom[0x101] = 0x01
    rom[0x102] = 0xe0.toByte()
    rom[0x103] = 0x4d
    rom[0x104] = 0x10
    rom[0x105] = 0x00
    rom[0x106] = 0x18
    rom[0x107] = 0xfe.toByte()
    rom[0x143] = 0x80.toByte()
    return rom
  }

  private fun datelConfiguration(slot: ByteArray, time: VirtualTimeSource) =
      configuration(datelRom())
          .setGameboyType(GameboyType.CGB)
          .setSlotRom(Rom(slot))
          .setRtcTimeSource(time)
          .setSupportBatterySave(false)

  private fun datelRom(): ByteArray =
      ByteArray(0x20000).also {
        it[0x100] = 0x00
        it[0x101] = 0xc3.toByte()
        it[0x104] = 0x44 // invalid Nintendo logo selects the Datel mapper
        it[0x147] = 0x00
        it[0x148] = 0x02
      }

  private fun mbc3Rom(): ByteArray = slotRom(0x10, 0x03)

  private fun basicRom(): ByteArray = slotRom(0x08, 0x02)

  private fun cgbIdleRom(): ByteArray =
      ByteArray(0x8000).also {
        it[0x100] = 0x18
        it[0x101] = 0xfe.toByte()
        it[0x143] = 0x80.toByte()
      }

  private fun slotRom(type: Int, ramSize: Int): ByteArray =
      ByteArray(0x8000).also {
        intArrayOf(0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d).forEachIndexed { index, value ->
          it[0x104 + index] = value.toByte()
        }
        it[0x100] = 0x18
        it[0x101] = 0xfe.toByte()
        it[0x147] = type.toByte()
        it[0x148] = 0x00
        it[0x149] = ramSize.toByte()
      }

  private fun readHuc3Register(bus: eu.rekawek.coffeegb.core.AddressSpace, register: Int): Int {
    bus.setByte(0x0000, 0x0b)
    bus.setByte(0xa000, 0x40 or (register and 0x0f))
    bus.setByte(0xa000, 0x50 or ((register shr 4) and 0x0f))
    bus.setByte(0xa000, 0x10)
    bus.setByte(0x0000, 0x0c)
    return bus.getByte(0xa000)
  }

  private fun readTama5Minutes(bus: eu.rekawek.coffeegb.core.AddressSpace): Int {
    bus.setByte(0xa001, 0x6)
    bus.setByte(0xa000, 0x4)
    bus.setByte(0xa001, 0x7)
    bus.setByte(0xa000, 0x6)
    bus.setByte(0xa001, 0xc)
    return bus.getByte(0xa000) and 0x0f
  }

  private class InjectedApplyFailure : RuntimeException()

  private companion object {
    const val DISPLAY_MEMENTO = "eu.rekawek.coffeegb.core.gpu.Display\$DisplayMemento"
    const val GAMEBOY_MEMENTO = "eu.rekawek.coffeegb.core.Gameboy\$GameboyMemento"
    const val GPU_MEMENTO = "eu.rekawek.coffeegb.core.gpu.Gpu\$GpuMemento"
    const val DMA_MEMENTO = "eu.rekawek.coffeegb.core.memory.Dma\$DmaMemento"
    const val HDMA_MEMENTO = "eu.rekawek.coffeegb.core.memory.Hdma\$HdmaMemento"
    const val SPEED_MEMENTO = "eu.rekawek.coffeegb.core.cpu.SpeedMode\$SpeedModeMomento"
    const val SOUND_MODE_MEMENTO = "eu.rekawek.coffeegb.core.sound.AbstractSoundMode\$AbstractSoundModeMemento"
    const val SOUND_MODE3_MEMENTO = "eu.rekawek.coffeegb.core.sound.SoundMode3\$SoundMode3Memento"
    const val POLYNOMIAL_COUNTER_MEMENTO =
        "eu.rekawek.coffeegb.core.sound.PolynomialCounter\$PolynomialCounterMemento"
    const val SERIAL_PORT_MEMENTO = "eu.rekawek.coffeegb.core.serial.SerialPort\$SerialPortMemento"
    const val CARTRIDGE_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.Cartridge\$CartridgeMemento"
    const val MBC3_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Mbc3\$Mbc3Memento"
    const val INT_QUEUE_MEMENTO = "eu.rekawek.coffeegb.core.gpu.IntQueue\$IntQueueMemento"
    const val DMG_FIFO_MEMENTO = "eu.rekawek.coffeegb.core.gpu.DmgPixelFifo\$DmgPixelFifoMemento"
    const val COLOR_FIFO_MEMENTO =
        "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoMemento"
    const val SGB_DISPLAY_MEMENTO = "eu.rekawek.coffeegb.core.sgb.SgbDisplay\$SgbDisplayMemento"
    const val SUPER_GAMEBOY_MEMENTO = "eu.rekawek.coffeegb.core.sgb.SuperGameboy\$SuperGameboyMemento"
    const val BACKGROUND_MEMENTO = "eu.rekawek.coffeegb.core.sgb.Background\$BackgroundMemento"
    const val TRANSFER_MEMENTO =
        "eu.rekawek.coffeegb.core.sgb.Commands\$TransferCommand\$TransferCommandMemento"
    const val DATEL_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Datel\$DatelMemento"
    const val GENIE_MEMENTO = "eu.rekawek.coffeegb.core.genie.Genie\$GenieMemento"
    const val RTC_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock\$RealTimeClockMemento"
    const val HUC3_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Huc3\$Huc3Memento"
    const val TAMA5_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Tama5\$Tama5Memento"
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
  }
}
