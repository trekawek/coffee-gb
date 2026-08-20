package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.genie.AddPatches
import eu.rekawek.coffeegb.core.genie.GameGenieCheat
import eu.rekawek.coffeegb.core.genie.GameSharkCheat
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint
import eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.sgb.Background
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sgb.SuperGameboy
import eu.rekawek.coffeegb.core.state.ComponentState
import java.nio.file.Files
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
  fun fileBatteryCheckpointTargetsMemoryBatteryAndRejectsOverflowBeforeMutation() {
    val romBytes = slotRom(0x1b, 0x03)
    val path = Files.createTempFile("coffee-gb-file-memory-battery-", ".gbc")
    Files.write(path, romBytes)
    try {
      val sourceConfig =
          Gameboy.GameboyConfiguration(Rom(path.toFile()))
              .setBootstrapMode(BootstrapMode.SKIP)
      val targetConfig =
          Gameboy.GameboyConfiguration(Rom(romBytes))
              .setBootstrapMode(BootstrapMode.SKIP)
              .setBatteryData(byteArrayOf())
              .setSupportBatterySave(false)
      session(sourceConfig).use { source ->
        session(targetConfig).use { target ->
          val state = source.captureDetachedState().machine
          assertEquals(2, state.recordCount(FILE_BATTERY_STATE))
          assertEquals(
              2,
              target.captureDetachedState().machine.recordCount(MEMORY_BATTERY_STATE),
          )
          DetachedStateAdapter.validateTarget(target.gameboy, state)

          val malformed =
              MachineState(
                  state.root.replaceRecordField(
                      FILE_BATTERY_STATE,
                      "ramBuffer",
                      BytesState(ByteArray(0x8001)),
                  ),
                  state.rtcRuntime,
                  state.hardware,
                  state.dmgFifoRuntime,
              )
          val before = target.captureDetachedState().machine
          val stages = mutableListOf<ApplyStage>()
          val failure =
              assertFailsWith<StateApplyException> {
                DetachedStateAdapter.apply(target.gameboy, malformed, stages::add)
              }
          assertTrue(failure.message.orEmpty().contains("target capacity"))
          assertTrue(stages.isEmpty())
          assertEquals(before, target.captureDetachedState().machine)
        }
      }
    } finally {
      Files.deleteIfExists(path)
      Files.deleteIfExists(path.resolveSibling(path.fileName.toString().substringBeforeLast('.') + ".sav"))
    }
  }

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
  fun dmgFirstPixelLatchAndWindowRewindBookkeepingRestoreObservableContinuation() {
    session(configuration().setGameboyType(GameboyType.DMG)).use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0xff47, 0xe4)
      var pending = session.captureDetachedState()
      var found = false
      repeat(1_000) {
        if (!found) {
          session.gameboy.tick()
          pending = session.captureDetachedState()
          found = pending.machine.dmgFifoRuntime!!.output.firstEntry >= 0
        }
      }
      assertTrue(found, "pixel-producing FIFO never entered its pending first-pixel phase")
      val fifoRuntime = requireNotNull(pending.machine.dmgFifoRuntime)
      val timingRuntime = fifoRuntime.timing
      val pendingRuntime = fifoRuntime.output
      assertTrue(timingRuntime.linePixels > 0)
      assertTrue(timingRuntime.outCount > 0)
      assertTrue(pendingRuntime.linePixels > 0)
      assertEquals(1, pendingRuntime.outCount)
      assertTrue(pendingRuntime.firstEntry in 0..0x3f)
      assertTrue(pendingRuntime.firstBgp in 0..0xff)
      assertTrue(pendingRuntime.firstObp0 in 0..0xff)
      assertTrue(pendingRuntime.firstObp1 in 0..0xff)

      val changedBgp = pendingRuntime.firstBgp xor 0xff
      bus.setByte(0xff47, changedBgp)
      session.gameboy.tick()
      val expected = session.captureDetachedState()
      val expectedDisplay = expected.machine.record(DISPLAY_MEMENTO)
      assertTrue(
          expectedDisplay.int("i") >
              pending.machine.record(DISPLAY_MEMENTO).int("i"),
          "pending first pixel did not reach the visible display",
      )

      advanceToFreshDmgOutputLine(session)
      session.restoreDetachedState(pending)
      bus.setByte(0xff47, changedBgp)
      session.gameboy.tick()
      val actual = session.captureDetachedState()
      assertEquals(expectedDisplay, actual.machine.record(DISPLAY_MEMENTO))
      assertEquals(expected, actual)
    }

    session(configuration().setGameboyType(GameboyType.DMG)).use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0xff47, 0xe4)
      bus.setByte(0xff4a, 0)
      bus.setByte(0xff4b, 40)
      bus.setByte(0xff40, 0xb1)
      var pending = session.captureDetachedState()
      var found = false
      repeat(1_000) {
        if (!found) {
          session.gameboy.tick()
          pending = session.captureDetachedState()
          val pixelMachine = pending.machine.records(PIXEL_TRANSFER_MEMENTO)[1]
          found =
              pixelMachine.int("windowPendingTicks") == 1 &&
                  !pixelMachine.bool("windowActivatedThisLine") &&
                  pending.machine.dmgFifoRuntime!!.output.linePixels > 0
        }
      }
      assertTrue(found, "pixel-producing FIFO never reached a pending window rewind")
      val pendingRuntime = pending.machine.dmgFifoRuntime!!.output
      assertTrue(pendingRuntime.linePixels > 0)

      session.gameboy.tick()
      val expected = session.captureDetachedState()
      assertTrue(expected.machine.records(PIXEL_TRANSFER_MEMENTO)[1].bool("windowActivatedThisLine"))
      val expectedDisplay = expected.machine.record(DISPLAY_MEMENTO)

      advanceToFreshDmgOutputLine(session)
      session.restoreDetachedState(pending)
      session.gameboy.tick()
      val actual = session.captureDetachedState()
      assertEquals(expectedDisplay, actual.machine.record(DISPLAY_MEMENTO))
      assertEquals(expected, actual)
    }
  }

  @Test
  fun invalidDmgFifoRuntimeIsRejectedBeforeAnyLiveMutation() {
    session(configuration().setGameboyType(GameboyType.DMG)).use { session ->
      repeat(400) { session.gameboy.tick() }
      val before = session.captureDetachedState()
      val capturedRuntime = requireNotNull(before.machine.dmgFifoRuntime)
      val neutral = DmgPixelFifoRuntimeState(0, 0, -1, 0, 0, 0)
      val invalidStates =
          listOf<Pair<String, DmgPixelFifoRuntimeState>>(
              "linePixels below zero" to neutral.copy(linePixels = -1),
              "linePixels above the visible line" to neutral.copy(linePixels = 161),
              "negative outCount" to neutral.copy(outCount = -1),
              "firstEntry below its sentinel" to neutral.copy(firstEntry = -2),
              "firstEntry above its packed range" to neutral.copy(firstEntry = 0x40),
              "pending firstEntry without its first output" to
                  neutral.copy(outCount = 0, firstEntry = 0),
              "pending firstEntry after the first output" to
                  neutral.copy(outCount = 2, firstEntry = 0),
              "BGP below byte range" to neutral.copy(firstBgp = -1),
              "BGP above byte range" to neutral.copy(firstBgp = 0x100),
              "OBP0 below byte range" to neutral.copy(firstObp0 = -1),
              "OBP0 above byte range" to neutral.copy(firstObp0 = 0x100),
              "OBP1 below byte range" to neutral.copy(firstObp1 = -1),
              "OBP1 above byte range" to neutral.copy(firstObp1 = 0x100),
          )

      invalidStates.forEach { (case, invalidRuntime) ->
        val timingCandidate =
            before.withDmgFifoRuntime(capturedRuntime.copy(timing = invalidRuntime))
        assertRejectedBeforeMutation(session, before, timingCandidate, "timing FIFO: $case")

        val outputCandidate =
            before.withDmgFifoRuntime(capturedRuntime.copy(output = invalidRuntime))
        assertRejectedBeforeMutation(session, before, outputCandidate, "output FIFO: $case")
      }
    }
  }

  @Test
  fun scalarFifoRecordsCannotCrossTimingAndVisibleGpuRoles() {
    session(configuration().setGameboyType(GameboyType.DMG)).use { session ->
      repeat(400) { session.gameboy.tick() }
      val before = session.captureDetachedState()
      val gpu = before.machine.record(GPU_MEMENTO)
      val timing = gpu.field("pixelTransferPhaseMemento") as RecordState
      val output = gpu.field("pixelMachineMemento") as RecordState
      val timingFifo = timing.field("fifoMemento") as RecordState
      val outputFifo = output.field("fifoMemento") as RecordState

      val outputScalar =
          before.withMachineRoot(
              before.machine.root.replaceRecordField(
                  GPU_MEMENTO,
                  "pixelMachineMemento",
                  output.replaceField("fifoMemento", timingFifo),
              ))
      assertRejectedBeforeMutation(session, before, outputScalar, "scalar output injection")

      val swappedRoles =
          before.withMachineRoot(
              before.machine.root.replaceRecordField(
                  GPU_MEMENTO,
                  "pixelTransferPhaseMemento",
                  timing.replaceField("fifoMemento", outputFifo),
              ).replaceRecordField(
                  GPU_MEMENTO,
                  "pixelMachineMemento",
                  output.replaceField("fifoMemento", timingFifo),
              ))
      assertRejectedBeforeMutation(session, before, swappedRoles, "swapped timing/output FIFO records")

      val nullOutput =
          before.withMachineRoot(
              before.machine.root.replaceRecordField(
                  GPU_MEMENTO,
                  "pixelMachineMemento",
                  NullState,
              ))
      assertRejectedBeforeMutation(session, before, nullOutput, "scalar timing with null output fallback")

      val malformedFull =
          outputFifo.replaceRecordField(
              INT_QUEUE_MEMENTO,
              "array",
              Int32ArrayState(IntArray(15)),
          )
      val malformedTiming =
          before.withMachineRoot(
              before.machine.root.replaceRecordField(
                  GPU_MEMENTO,
                  "pixelTransferPhaseMemento",
                  timing.replaceField("fifoMemento", malformedFull),
              ))
      assertRejectedBeforeMutation(session, before, malformedTiming, "malformed full FIFO shape")
    }
  }

  @Test
  fun scalarTimingAndDmgFifoSupplementMustAgreeBeforeLiveMutation() {
    session(configuration().setGameboyType(GameboyType.DMG)).use { session ->
      repeat(400) { session.gameboy.tick() }
      val before = session.captureDetachedState()
      val gpu = before.machine.record(GPU_MEMENTO)
      val timing = gpu.field("pixelTransferPhaseMemento") as RecordState
      val timingFifo = timing.field("fifoMemento") as RecordState
      val runtime = requireNotNull(before.machine.dmgFifoRuntime)
      val mismatchedScalar = timingFifo.replaceField(
          "linePixels", Int32State((runtime.timing.linePixels + 1) % 161))
      val candidate = before.withMachineRoot(
          before.machine.root.replaceRecordField(
              GPU_MEMENTO,
              "pixelTransferPhaseMemento",
              timing.replaceField("fifoMemento", mismatchedScalar),
          ))

      // Both records are individually well-formed.  The cross-plane preflight must reject the
      // contradiction before restoreStateSilently or the later runtime supplement can mutate the
      // live machine.
      assertRejectedBeforeMutation(
          session, before, candidate, "scalar timing/runtime supplement disagreement")
    }
  }

  @Test
  fun dmgFifoRuntimePresenceMatchesHardwareBeforeAnyLiveMutation() {
    listOf(GameboyType.DMG, GameboyType.SGB).forEach { hardware ->
      session(configuration().setGameboyType(hardware)).use { session ->
        val before = session.captureDetachedState()
        assertRejectedBeforeMutation(
            session,
            before,
            before.withDmgFifoRuntime(null),
            "$hardware state without its FIFO supplement",
        )
      }
    }

    val dmgRuntime =
        session(configuration().setGameboyType(GameboyType.DMG)).use {
          requireNotNull(it.captureDetachedState().machine.dmgFifoRuntime)
        }
    session(configuration(cgbIdleRom()).setGameboyType(GameboyType.CGB)).use { session ->
      val before = session.captureDetachedState()
      assertRejectedBeforeMutation(
          session,
          before,
          before.withDmgFifoRuntime(dmgRuntime),
          "native CGB state with a DMG FIFO supplement",
      )
    }
  }

  @Test
  fun reachableDmgAndSgbFifoRuntimeBoundariesApplyAtTheAtomicBoundary() {
    listOf(GameboyType.DMG, GameboyType.SGB).forEach { hardware ->
      session(configuration().setGameboyType(hardware)).use { session ->
        val initial = session.captureDetachedState()
        val initialRuntime = requireNotNull(initial.machine.dmgFifoRuntime)
        listOf(initialRuntime.timing, initialRuntime.output).forEach {
          assertEquals(0, it.linePixels, "$hardware FIFO did not begin at line position zero")
          assertEquals(0, it.outCount, "$hardware FIFO did not begin before output")
          assertEquals(-1, it.firstEntry, "$hardware FIFO began with a pending first pixel")
        }
        assertAcceptedAtMutationBoundary(session, initial, "$hardware initial FIFO boundary")

        val sawLineEnd = BooleanArray(2)
        var sawPendingFirst = false
        var sawActiveProgress = false
        repeat(5_000) {
          session.gameboy.tick()
          val coreRuntime = requireNotNull(session.gameboy.captureDmgFifoRuntimeState())
          val runtime = listOf(coreRuntime.timing(), coreRuntime.output())
          var sample = false
          runtime.forEachIndexed { index, fifo ->
            if (!sawLineEnd[index] && fifo.linePixels() == 160) {
              sawLineEnd[index] = true
              sample = true
            }
            if (!sawPendingFirst && fifo.firstEntry() >= 0) {
              assertEquals(1, fifo.outCount())
              sawPendingFirst = true
              sample = true
            }
            if (!sawActiveProgress && fifo.linePixels() in 1..159) {
              sawActiveProgress = true
              sample = true
            }
          }
          if (sample) {
            assertAcceptedAtMutationBoundary(
                session,
                session.captureDetachedState(),
                "$hardware reachable FIFO sample",
            )
          }
        }

        assertTrue(sawLineEnd.all { it }, "$hardware FIFOs never reached line position 160")
        assertTrue(sawPendingFirst, "$hardware FIFO never held the first output entry")
        assertTrue(sawActiveProgress, "$hardware FIFO never showed active line progress")
      }
    }
  }

  @Test
  fun completeGraphIsValidatedBeforeMutationAndFailedApplyIsAtomic() {
    session().use { session ->
      repeat(2_000) { session.gameboy.tick() }
      val before = session.captureDetachedState()
      val invalidRoot = RecordState(before.machine.root.typeId, before.machine.root.fields.dropLast(1))
      val invalid = SessionState(
          MachineState(
              invalidRoot,
              before.machine.rtcRuntime,
              before.machine.hardware,
              before.machine.dmgFifoRuntime,
          ),
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
      val palettes = sgbDisplay.field("palettes") as ObjectArrayState
      val attributeFiles = sgbDisplay.field("attributeFiles") as ObjectArrayState
      val firstAttributeFile = attributeFiles.values.first() as Int32ArrayState
      val invalidAttributeFile =
          Int32ArrayState(firstAttributeFile.copyValue().also { it[0] = 4 })
      val paletteMap = sgbDisplay.intArray("paletteMap").also { it[0] = 4 }
      val malformedTransfer =
          transferCommand(0x14).let { transfer ->
            val packet = transfer.intArray("packet").also { it[1] = 1 }
            transfer.replaceField("packet", Int32ArrayState(packet))
          }

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
                  "palettes",
                  ObjectArrayState(
                      palettes.values.mapIndexed { index, value ->
                        if (index == 0) NullState else value
                      }),
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
                  SGB_DISPLAY_MEMENTO,
                  "borderFade",
                  Int32State(0x200),
              ),
              before.machine.root
                  .replaceRecordField(
                      SUPER_GAMEBOY_MEMENTO,
                      "waitingTransferCommandMemento",
                      malformedTransfer,
                  )
                  .replaceRecordField(
                      SUPER_GAMEBOY_MEMENTO,
                      "transferCountdown",
                      Int32State(3),
                  ),
              before.machine.root
                  .replaceRecordField(
                      SUPER_GAMEBOY_MEMENTO,
                      "multipacketLength",
                      Int32State(2),
                  )
                  .replaceRecordField(
                      SUPER_GAMEBOY_MEMENTO,
                      "multipacketIndex",
                      Int32State(0),
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
              before.machine.root.replaceRecordField(
                  GENIE_MEMENTO,
                  "patches",
                  Int32MapState(
                      listOf(Int32MapEntry(0x1234, ListState(listOf(queue))))),
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
  fun restoredSgbTransferAndPictureInvariantsRejectBeforeMutation() {
    session(configuration().setGameboyType(GameboyType.SGB)).use { session ->
      val before = session.captureDetachedState()
      val validPayload = Int32ArrayState(IntArray(0x1000))
      val invalidPicturePayload =
          Int32ArrayState(IntArray(0x1000).also { it[1] = 0x20 }) // PCT priority bit 13.
      val waitingWithPayload = transferCommand(0x14).replaceField("dataTransfer", validPayload)
      val pictureWithoutPayload = transferCommand(0x14)
      val invalidPicture =
          transferCommand(0x14).replaceField("dataTransfer", invalidPicturePayload)

      val cases =
          listOf(
              "SOU_TRN delayed capture" to waitingTransfer(before, transferCommand(0x09)),
              "DATA_TRN delayed capture" to waitingTransfer(before, transferCommand(0x10)),
              "committed delayed capture" to waitingTransfer(before, waitingWithPayload),
              "picture without payload" to pendingPicture(before, pictureWithoutPayload, 105),
              "picture with unsupported priority" to pendingPicture(before, invalidPicture, 105),
              "animation without picture" to
                  before.withMachineRoot(
                      before.machine.root.replaceRecordField(
                          BACKGROUND_MEMENTO,
                          "borderAnimation",
                          Int32State(1),
                      )),
          )

      cases.forEach { (label, candidate) ->
        assertRejectedBeforeMutation(session, before, candidate, label)
        assertStateFileRejectedBeforeMutation(session, before, candidate, label)
      }

      // Direct ComponentState restore has the same allowlist even without the adapter preflight.
      listOf(0x09, 0x10).forEach { code ->
        val invalidRecord =
            before.machine
                .record(SUPER_GAMEBOY_MEMENTO)
                .replaceField("waitingTransferCommandMemento", transferCommand(code))
                .replaceField("transferCountdown", Int32State(3))
        @Suppress("UNCHECKED_CAST")
        val invalidState = StateGraph.restore(invalidRecord) as ComponentState<SuperGameboy>
        EventBusImpl(null, null, false).use { bus ->
          val component = SuperGameboy(bus)
          val componentBefore = StateGraph.capture(component.captureState())
          assertFailsWith<IllegalArgumentException>("direct transfer 0x${code.toString(16)}") {
            component.restoreState(invalidState)
          }
          assertEquals(componentBefore, StateGraph.capture(component.captureState()))
        }
      }

      // Background validates its complete picture before copying even the tile payload.
      listOf(pictureWithoutPayload, invalidPicture).forEachIndexed { index, picture ->
        val tiles = before.machine.record(BACKGROUND_MEMENTO).intArray("tiles").also { it[0] = 0x55 }
        val invalidRecord =
            before.machine
                .record(BACKGROUND_MEMENTO)
                .replaceField("tiles", Int32ArrayState(tiles))
                .replaceField("pendingPictureMemento", picture)
                .replaceField("borderAnimation", Int32State(105))
        @Suppress("UNCHECKED_CAST")
        val invalidState = StateGraph.restore(invalidRecord) as ComponentState<Background>
        EventBusImpl(null, null, false).use { bus ->
          val component = Background(bus)
          val componentBefore = StateGraph.capture(component.captureState())
          assertFailsWith<IllegalArgumentException>("direct picture case $index") {
            component.restoreState(invalidState)
          }
          assertEquals(componentBefore, StateGraph.capture(component.captureState()))
        }
      }
    }
  }

  @Test
  fun validPendingPictureStateFileResumesAtTheBorderSwap() {
    val eventBus = EventBusImpl(null, null, false)
    val borders = mutableListOf<Pair<IntArray, IntArray>>()
    eventBus.register(
        { event -> borders += event.buffer().clone() to event.mask().clone() },
        Background.SgbBackgroundReadyEvent::class.java,
    )
    val configuration =
        configuration().setGameboyType(GameboyType.SGB).setDisplaySgbBorder(true)
    Session(configuration, eventBus, null).use { session ->
      val original = session.captureDetachedState()
      val pictureData = IntArray(0x1000)
      pictureData[0x782] = 0xff
      pictureData[0x783] = 0x7f
      val picture =
          transferCommand(0x14).replaceField("dataTransfer", Int32ArrayState(pictureData))
      val tiles = original.machine.record(BACKGROUND_MEMENTO).intArray("tiles")
      tiles[0] = 0x80
      val pending = pendingPicture(original, picture, 33)
      val candidate =
          pending.withMachineRoot(
              pending.machine.root.replaceRecordField(
                  BACKGROUND_MEMENTO,
                  "tiles",
                  Int32ArrayState(tiles),
              ))
      val file = StateCodec.capture(session)
      val bytes = StateCodec.encode(StateFile(file.identities, SessionStateRoot(candidate)))

      val stages = mutableListOf<ApplyStage>()
      StateCodec.decodeAndApply(bytes, session) { stages += it }
      assertEquals(
          listOf(ApplyStage.BEFORE_LIVE_MUTATION, ApplyStage.AFTER_MACHINE_MUTATION),
          stages,
      )
      eventBus.post(Display.DmgFrameReadyEvent(IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)))
      val expectedBorder = borders.single().let { it.first.clone() to it.second.clone() }
      val expectedState = session.captureDetachedState()
      assertEquals(0x7fff, expectedBorder.first[0])
      assertEquals(1, expectedBorder.second[0])

      repeat(5) {
        eventBus.post(Display.DmgFrameReadyEvent(IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)))
      }
      borders.clear()
      StateCodec.decodeAndApply(bytes, session)
      eventBus.post(Display.DmgFrameReadyEvent(IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)))

      assertContentEquals(expectedBorder.first, borders.single().first)
      assertContentEquals(expectedBorder.second, borders.single().second)
      assertEquals(expectedState, session.captureDetachedState())
    }
  }

  @Test
  fun legacyNullSystemPaletteRowNormalizesBeforePalSetAndRender() {
    val eventBus = EventBusImpl(null, null, false)
    val frames = mutableListOf<IntArray>()
    eventBus.register(
        { event -> frames += event.buffer().clone() },
        SgbDisplay.SgbFrameReadyEvent::class.java,
    )
    val configuration =
        configuration().setGameboyType(GameboyType.SGB).setDisplaySgbBorder(false)
    Session(configuration, eventBus, null).use { session ->
      val before = session.captureDetachedState()
      val display = before.machine.record(SGB_DISPLAY_MEMENTO)
      val systemPalettes = display.field("systemPalettes") as ObjectArrayState
      val nullRow =
          ObjectArrayState(
              systemPalettes.values.mapIndexed { index, value ->
                if (index == 0) NullState else value
              })
      val candidate =
          before.withMachineRoot(
              before.machine.root.replaceRecordField(
                  SGB_DISPLAY_MEMENTO,
                  "systemPalettes",
                  nullRow,
              ))
      val file = StateCodec.capture(session)
      val bytes = StateCodec.encode(StateFile(file.identities, SessionStateRoot(candidate)))
      val stages = mutableListOf<ApplyStage>()

      StateCodec.decodeAndApply(bytes, session) { stages += it }
      assertEquals(
          listOf(ApplyStage.BEFORE_LIVE_MUTATION, ApplyStage.AFTER_MACHINE_MUTATION),
          stages,
      )
      val normalized =
          session.captureDetachedState().machine.record(SGB_DISPLAY_MEMENTO)
              .field("systemPalettes") as ObjectArrayState
      assertContentEquals(IntArray(4), (normalized.values[0] as Int32ArrayState).copyValue())

      val dmg = IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT) { 1 }
      sendSgbCommand(session, 0x0a)
      eventBus.post(Display.DmgFrameReadyEvent(dmg))
      val zeroPaletteFrame = frames.last().clone()

      sendSgbCommand(session, 0x00, 0, 0, 0x34, 0x12)
      eventBus.post(Display.DmgFrameReadyEvent(dmg))
      val commandPaletteFrame = frames.last().clone()
      assertNotEquals(zeroPaletteFrame[0], commandPaletteFrame[0])

      // PAL_SET must read the owned normalized system row, not an active-row alias.
      sendSgbCommand(session, 0x0a)
      eventBus.post(Display.DmgFrameReadyEvent(dmg))
      assertContentEquals(zeroPaletteFrame, frames.last())
      val recaptured =
          session.captureDetachedState().machine.record(SGB_DISPLAY_MEMENTO)
              .field("systemPalettes") as ObjectArrayState
      assertContentEquals(IntArray(4), (recaptured.values[0] as Int32ArrayState).copyValue())
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
    val withWaiting =
        superGameboy
            .replaceField("waitingTransferCommandMemento", transfer)
            .replaceField("transferCountdown", Int32State(3))
    StateGraph.validateCompatible(withWaiting, superGameboy, "optional-waiting-transfer")
    StateSemantics.validate(StateGraph.restore(withWaiting))

    val background = session().use { it.captureDetachedState().machine.record(BACKGROUND_MEMENTO) }
    val withPicture = background.replaceField("pendingPictureMemento", transferWithPayload)
    StateGraph.validateCompatible(withPicture, background, "optional-pending-picture")
    StateSemantics.validate(StateGraph.restore(withPicture))

    val barcode = BarcodeBoySerialEndpoint()
    val idleBarcode = StateGraph.capture(barcode.captureState())
    repeat(4) {
      barcode.startSending()
      repeat(8) { barcode.sendBit() }
    }
    barcode.scan("4901234567894")
    barcode.setExternalTransfer(true)
    barcode.recvBit()
    val sendingBarcode = StateGraph.capture(barcode.captureState())
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
    session(basicWithoutBattery).use { session ->
      val captured = session.captureDetachedState()
      assertEquals(NullState, captured.machine.record(BASIC_ROM_MEMENTO).field("batteryMemento"))
      assertContinuation(session, captured, 96)
    }

    val time = VirtualTimeSource(120_000)
    val datelWithoutSlot =
        configuration(datelRom())
            .setGameboyType(GameboyType.CGB)
            .setRtcTimeSource(time)
            .setSupportBatterySave(false)
    val datelWithSlot = datelConfiguration(mbc3Rom(), time)
    assertCrossPresenceRejected(datelWithoutSlot, datelWithSlot)
    assertCrossPresenceRejected(datelWithSlot, datelWithoutSlot)
    session(datelWithoutSlot).use { session ->
      val captured = session.captureDetachedState()
      assertEquals(NullState, captured.machine.record(DATEL_MEMENTO).field("slotMemento"))
      assertContinuation(session, captured, 96)
    }
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
      val idleState = StateGraph.capture(BarcodeBoySerialEndpoint().captureState()) as RecordState
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
              BarcodeBoyRuntimeState(true, IntArray(31)),
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
  fun signedWrappedHdmaSourceProgressAppliesAndContinuesDeterministically() {
    session(configuration(cgbIdleRom()).setGameboyType(GameboyType.CGB)).use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0xff51, 0xc0)
      bus.setByte(0xff52, 0x00)
      bus.setByte(0xff53, 0x00)
      bus.setByte(0xff54, 0x00)
      val before = session.captureDetachedState()
      val wrapped =
          before.withMachineRoot(
              before.machine.root.replaceRecordField(
                  HDMA_MEMENTO,
                  "sourceBytesTransferred",
                  Int32State(Int.MIN_VALUE),
              ))

      session.restoreDetachedState(wrapped)
      assertEquals(
          Int.MIN_VALUE,
          session.captureDetachedState().machine.record(HDMA_MEMENTO)
              .int("sourceBytesTransferred"),
      )
      bus.setByte(0xff55, 0)
      val started = session.captureDetachedState()
      assertTrue(started.machine.record(HDMA_MEMENTO).bool("transferInProgress"))
      assertEquals(
          Int.MIN_VALUE,
          started.machine.record(HDMA_MEMENTO).int("sourceBytesTransferred"),
      )
      assertContinuation(session, started, 48)
    }
  }

  @Test
  fun registeredCheatPatchListsApplyAndContinue() {
    session().use { session ->
      session.eventBus.post(
          AddPatches(
              listOf(
                  GameGenieCheat(0x42, 0x1234, -1),
                  GameSharkCheat(8, 2, 0xa123, 0x99),
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
  fun failedSessionTransactionDoesNotPublishSpeculativeRumble() {
    session(configuration().setCodeBreakerRumble(true)).use { session ->
      val rumble = mutableListOf<Boolean>()
      session.eventBus.register({ event -> rumble += event.on() }, RumbleEvent::class.java)
      session.gameboy.addressSpace.setByte(0xfffe, 0x80)
      val target = session.captureDetachedState()

      session.gameboy.addressSpace.setByte(0xfffe, 0x00)
      session.gameboy.addressSpace.setByte(0xc123, 0x77)
      val beforeFailure = session.captureDetachedState()
      rumble.clear()

      assertFailsWith<StateApplyException> {
        DetachedStateAdapter.apply(session, target) { stage ->
          if (stage == ApplyStage.AFTER_MACHINE_MUTATION) {
            throw InjectedApplyFailure()
          }
        }
      }

      assertEquals(beforeFailure, session.captureDetachedState())
      assertEquals(emptyList(), rumble)

      DetachedStateAdapter.apply(session, target)
      assertEquals(listOf(true), rumble)
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
      override fun captureState() = null
      override fun restoreState(memento: eu.rekawek.coffeegb.core.state.ComponentState<SerialEndpoint>?) = Unit
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

  private fun advanceToFreshDmgOutputLine(session: Session) {
    var reached = false
    repeat(1_000) {
      if (!reached) {
        session.gameboy.tick()
        val output = session.captureDetachedState().machine.dmgFifoRuntime!!.output
        reached =
            output.linePixels == 0 &&
                output.outCount == 0 &&
                output.firstEntry == -1
      }
    }
    assertTrue(reached, "DMG FIFO did not reach a fresh output line")
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

  private fun assertStateFileRejectedBeforeMutation(
      session: Session,
      before: SessionState,
      invalid: SessionState,
      label: String,
  ) {
    val identities = StateCodec.capture(session).identities
    val bytes = StateCodec.encode(StateFile(identities, SessionStateRoot(invalid)))
    val stages = mutableListOf<ApplyStage>()
    val failure =
        assertFailsWith<StateDecodeException>("$label StateFile") {
          StateCodec.decodeAndApply(bytes, session) { stages += it }
        }
    assertEquals(StateDecodeReason.TARGET_STATE_MISMATCH, failure.reason, label)
    assertTrue(stages.isEmpty(), "$label StateFile reached live mutation")
    assertEquals(before, session.captureDetachedState(), "$label StateFile changed the session")
  }

  private fun assertAcceptedAtMutationBoundary(
      session: Session,
      state: SessionState,
      label: String,
  ) {
    val stages = mutableListOf<ApplyStage>()
    DetachedStateAdapter.apply(session, state) { stages += it }
    assertEquals(
        listOf(ApplyStage.BEFORE_LIVE_MUTATION, ApplyStage.AFTER_MACHINE_MUTATION),
        stages,
        "$label did not cross the expected apply stages",
    )
    assertEquals(state, session.captureDetachedState(), "$label did not restore exactly")
  }

  private fun MachineState.record(className: String): RecordState {
    return requireNotNull(records(className).firstOrNull()) { "Missing $className" }
  }

  private fun MachineState.records(className: String): List<RecordState> {
    val matches = mutableListOf<RecordState>()
    fun find(value: StateValue): RecordState? =
        when (value) {
          is RecordState -> {
            if (MementoClassNames.record(value.typeId) == className) {
              matches += value
            }
            value.fields.forEach { find(it.value) }
            null
          }
          is ObjectArrayState -> {
            value.values.forEach { find(it) }
            null
          }
          is ListState -> {
            value.values.forEach { find(it) }
            null
          }
          is Int32MapState -> {
            value.entries.forEach { find(it.value) }
            null
          }
          else -> null
        }
    find(root)
    return matches
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
          eu.rekawek.coffeegb.controller.StateTypeRegistry.recordClassNames.indexOf(TRANSFER_MEMENTO) + 1,
          listOf(
              StateField("packet", Int32ArrayState(IntArray(16).also { it[0] = (code shl 3) or 1 })),
              StateField("dataTransfer", NullState),
          ),
      )

  private fun waitingTransfer(state: SessionState, transfer: RecordState): SessionState =
      state.withMachineRoot(
          state.machine.root
              .replaceRecordField(
                  SUPER_GAMEBOY_MEMENTO,
                  "waitingTransferCommandMemento",
                  transfer,
              )
              .replaceRecordField(
                  SUPER_GAMEBOY_MEMENTO,
                  "transferCountdown",
                  Int32State(3),
              ))

  private fun pendingPicture(
      state: SessionState,
      transfer: RecordState,
      animation: Int,
  ): SessionState =
      state.withMachineRoot(
          state.machine.root
              .replaceRecordField(
                  BACKGROUND_MEMENTO,
                  "pendingPictureMemento",
                  transfer,
              )
              .replaceRecordField(
                  BACKGROUND_MEMENTO,
                  "borderAnimation",
                  Int32State(animation),
              ))

  private fun sendSgbCommand(session: Session, code: Int, vararg payload: Int) {
    require(code in 0..0x1f && payload.size <= 15 && payload.all { it in 0..0xff })
    val packet = IntArray(16)
    packet[0] = code shl 3 or 1
    payload.copyInto(packet, destinationOffset = 1)
    val joyp = session.gameboy.addressSpace
    joyp.setByte(0xff00, 0x30)
    joyp.setByte(0xff00, 0x00)
    joyp.setByte(0xff00, 0x30)
    repeat(128) { bit ->
      val value = packet[bit / 8] ushr (bit and 7) and 1
      joyp.setByte(0xff00, if (value == 0) 0x20 else 0x10)
      joyp.setByte(0xff00, 0x30)
    }
    joyp.setByte(0xff00, 0x20)
    joyp.setByte(0xff00, 0x30)
  }

  private fun RecordState.intArray(name: String): IntArray = arrayState(name).copyValue()

  private fun RecordState.int(name: String): Int =
      (fields.single { it.name == name }.value as Int32State).value

  private fun RecordState.bool(name: String): Boolean =
      (fields.single { it.name == name }.value as BooleanState).value

  private fun RecordState.long(name: String): Long =
      (fields.single { it.name == name }.value as Int64State).value

  private fun SessionState.withMachineRoot(root: RecordState): SessionState =
      SessionState(
          MachineState(root, machine.rtcRuntime, machine.hardware, machine.dmgFifoRuntime),
          serialPeripheral,
          serialState,
          serialRuntime,
          heldButtons,
      )

  private fun SessionState.withDmgFifoRuntime(runtime: DmgFifoRuntimeState?): SessionState =
      SessionState(
          MachineState(machine.root, machine.rtcRuntime, machine.hardware, runtime),
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
        eu.rekawek.coffeegb.controller.StateTypeRegistry.recordClasses[typeId - 1].name
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
        it[0x102] = 0x50
        it[0x103] = 0x01
        it[0x104] = 0x44
        "Action Replay V4".forEachIndexed { index, character ->
          it[0x134 + index] = character.code.toByte()
        }
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
    const val DISPLAY_MEMENTO = "eu.rekawek.coffeegb.core.gpu.Display\$DisplayState"
    const val GAMEBOY_MEMENTO = "eu.rekawek.coffeegb.core.Gameboy\$GameboyState"
    const val GPU_MEMENTO = "eu.rekawek.coffeegb.core.gpu.Gpu\$GpuState"
    const val DMA_MEMENTO = "eu.rekawek.coffeegb.core.memory.Dma\$DmaState"
    const val HDMA_MEMENTO = "eu.rekawek.coffeegb.core.memory.Hdma\$HdmaState"
    const val SPEED_MEMENTO = "eu.rekawek.coffeegb.core.cpu.SpeedMode\$SpeedModeState"
    const val SOUND_MODE_MEMENTO = "eu.rekawek.coffeegb.core.sound.AbstractSoundMode\$AbstractSoundModeState"
    const val SOUND_MODE3_MEMENTO = "eu.rekawek.coffeegb.core.sound.SoundMode3\$SoundMode3State"
    const val POLYNOMIAL_COUNTER_MEMENTO =
        "eu.rekawek.coffeegb.core.sound.PolynomialCounter\$PolynomialCounterState"
    const val SERIAL_PORT_MEMENTO = "eu.rekawek.coffeegb.core.serial.SerialPort\$SerialPortState"
    const val CARTRIDGE_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.Cartridge\$CartridgeState"
    const val MBC3_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Mbc3\$Mbc3State"
    const val INT_QUEUE_MEMENTO = "eu.rekawek.coffeegb.core.gpu.IntQueue\$IntQueueState"
    const val DMG_FIFO_MEMENTO = "eu.rekawek.coffeegb.core.gpu.DmgPixelFifo\$DmgPixelFifoState"
    const val COLOR_FIFO_MEMENTO =
        "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState"
    const val PIXEL_TRANSFER_MEMENTO =
        "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$PixelTransferState"
    const val SGB_DISPLAY_MEMENTO = "eu.rekawek.coffeegb.core.sgb.SgbDisplay\$SgbDisplayState"
    const val SUPER_GAMEBOY_MEMENTO = "eu.rekawek.coffeegb.core.sgb.SuperGameboy\$SuperGameboyState"
    const val BACKGROUND_MEMENTO = "eu.rekawek.coffeegb.core.sgb.Background\$BackgroundState"
    const val TRANSFER_MEMENTO =
        "eu.rekawek.coffeegb.core.sgb.Commands\$TransferCommand\$TransferCommandState"
    const val DATEL_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Datel\$DatelState"
    const val BASIC_ROM_MEMENTO =
        "eu.rekawek.coffeegb.core.memory.cart.type.BasicRom\$BasicRomState"
    const val FILE_BATTERY_STATE =
        "eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery\$FileBatteryState"
    const val MEMORY_BATTERY_STATE =
        "eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery\$MemoryBatteryState"
    const val GENIE_MEMENTO = "eu.rekawek.coffeegb.core.genie.Genie\$GenieState"
    const val RTC_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock\$RealTimeClockState"
    const val HUC3_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Huc3\$Huc3State"
    const val TAMA5_MEMENTO = "eu.rekawek.coffeegb.core.memory.cart.type.Tama5\$Tama5State"
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
  }
}
