package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.SessionDebugPortEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StatePrepareCloseCompletedEvent
import eu.rekawek.coffeegb.controller.state.StatePrepareCloseRequestEvent
import eu.rekawek.coffeegb.controller.state.StateRef
import eu.rekawek.coffeegb.controller.state.StateLoadRefRequestEvent
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent
import eu.rekawek.coffeegb.controller.state.StateSaveRequestEvent
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugAnchoredMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugBreakpointHit
import eu.rekawek.coffeegb.core.debug.DebugButton
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionSection
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugMemoryWrite
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.debug.DebugStepStopReason
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugSerialCondition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.InputTimelineObserver
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource
import eu.rekawek.coffeegb.core.sound.Sound
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.EnumSet
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class BasicControllerDebugPortTest {

  @Test
  fun reverseFrameRestoresThePrecedingBoundaryWithoutGuestOrHostSideEffects() {
    withController { eventBus, port, _, _, _ ->
      val frameEvents = AtomicInteger()
      val soundEvents = AtomicInteger()
      eventBus.register<Display.DmgFrameReadyEvent> { frameEvents.incrementAndGet() }
      eventBus.register<Sound.SoundSampleEvent> { soundEvents.incrementAndGet() }

      val paused = await(port.pause()).value()
      assertTrue(port.capabilities().history().checkpointHistory())
      assertTrue(port.capabilities().history().reverseFrame())
      assertTrue(port.capabilities().history().reverseInstruction())
      assertError(DebugErrorCode.HISTORY_DISABLED, await(port.stepBackward(DebugStepKind.FRAME)))

      val configuration = DebugHistoryConfiguration.defaults()
      assertEquals(configuration, await(port.configureHistory(configuration)).value().configuration())
      val traceConfiguration = TraceConfiguration(512, EnumSet.of(TraceCategory.CPU))
      assertTrue(await(port.configureTrace(traceConfiguration)).isSuccess)

      val first = await(port.step(DebugStepKind.FRAME)).value().snapshot()
      val firstStatus = await(port.historyStatus()).value()
      val firstPoint = assertNotNull(firstStatus.newest())
      assertEquals(first.frame(), firstPoint.frame())

      val second = await(port.step(DebugStepKind.FRAME)).value().snapshot()
      val secondStatus = await(port.historyStatus()).value()
      val secondPoint = assertNotNull(secondStatus.newest())
      val beforeTrace = await(port.readTrace(TraceReadRequest.initial(512))).value()
      assertTrue(beforeTrace.entries().isNotEmpty())
      val framesBeforeReverse = frameEvents.get()
      val samplesBeforeReverse = soundEvents.get()

      val reversed = await(port.stepBackward(DebugStepKind.FRAME))
      assertTrue(reversed.isSuccess, reversed.toString())
      val result = reversed.value()
      assertEquals(DebugStepKind.FRAME, result.kind())
      assertEquals(firstPoint, result.restoredPoint())
      assertEquals(second.masterTick(), result.snapshot().masterTick())
      assertEquals(second.frame(), result.snapshot().frame())
      assertTrue(result.snapshot().sequence() > second.sequence())
      assertEquals(0, result.snapshot().framePosition())
      assertTrue(result.snapshot().paused())
      assertMachineViewEquals(first, result.snapshot())
      assertEquals(
          second.execution().retiredInstructions(),
          result.snapshot().execution().retiredInstructions(),
          "debug retirement accounting remains monotonic across restored machine state",
      )
      assertEquals(result.restoredPosition(), result.history().cursor())
      assertEquals(secondPoint, result.history().newest())
      assertEquals(secondStatus.checkpointCount(), result.history().checkpointCount())
      assertEquals(1, result.history().futureCheckpointCount())
      assertEquals(
          DebugHistoryTruncationReason.NONE,
          result.history().lastTruncationReason(),
      )
      assertEquals(framesBeforeReverse, frameEvents.get())
      assertEquals(samplesBeforeReverse, soundEvents.get())

      val afterTrace = await(port.readTrace(TraceReadRequest.initial(512))).value()
      assertTrue(afterTrace.entries().isEmpty())
      assertEquals(beforeTrace.nextSequence(), afterTrace.nextSequence())
      assertTrue(
          afterTrace.droppedEventCount() >=
              beforeTrace.droppedEventCount() + beforeTrace.entries().size)
      assertTrue(paused.masterTick() <= first.masterTick())
    }
  }

  @Test
  fun reverseFrameFromAPartialFrameRestoresItsStartBoundary() {
    withController { _, port, _, _, _ ->
      assertTrue(await(port.pause()).isSuccess)
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)

      val boundary = await(port.step(DebugStepKind.FRAME)).value().snapshot()
      val boundaryStatus = await(port.historyStatus()).value()
      val boundaryPoint = assertNotNull(boundaryStatus.newest())
      val partial = await(port.step(DebugStepKind.INSTRUCTION)).value().snapshot()
      assertTrue(partial.framePosition() > 0)

      val reversed = await(port.stepBackward(DebugStepKind.FRAME)).value()
      assertEquals(boundaryPoint, reversed.restoredPoint())
      assertEquals(boundaryStatus.checkpointCount(), reversed.history().checkpointCount())
      assertEquals(partial.masterTick(), reversed.snapshot().masterTick())
      assertEquals(partial.frame(), reversed.snapshot().frame())
      assertEquals(0, reversed.snapshot().framePosition())
      assertMachineViewEquals(boundary, reversed.snapshot())
    }
  }

  @Test
  fun reverseSteppingRequiresAPauseAndRetainedHistory() {
    withController { _, port, _, _, _ ->
      assertTrue(await(port.pause()).isSuccess)
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
      assertError(
          DebugErrorCode.HISTORY_EXHAUSTED,
          await(port.stepBackward(DebugStepKind.INSTRUCTION)),
      )
      assertError(
          DebugErrorCode.UNSUPPORTED_STEP,
          await(port.stepBackward(DebugStepKind.MACHINE_CYCLE)),
      )
      assertTrue(await(port.resume()).isSuccess)
      assertError(DebugErrorCode.NOT_PAUSED, await(port.stepBackward(DebugStepKind.FRAME)))
    }
  }

  @Test
  fun inputTimelineContentionReturnsSessionBusyWithoutChangingHistoryConfiguration() {
    withController { _, port, _, controller, _ ->
      assertTrue(await(port.pause()).isSuccess)
      val currentSession = controllerSession(controller)
      val blocker = InputTimelineObserver { _, _, _, _ -> }
      assertTrue(currentSession.gameboy.attachInputTimelineObserver(blocker))

      try {
        val before = await(port.historyStatus()).value()
        assertError(
            DebugErrorCode.SESSION_BUSY,
            await(port.configureHistory(DebugHistoryConfiguration.defaults())),
        )
        assertEquals(before, await(port.historyStatus()).value())
      } finally {
        assertTrue(currentSession.gameboy.detachInputTimelineObserver(blocker))
      }

      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
    }
  }

  @Test
  fun reverseInstructionRestoresTheExactPriorRetirementWithoutHostSideEffects() {
    withController(
        programRom("DEBUG_REVERSE_INSTRUCTION", 0x3c, 0x18, 0xfc),
    ) { eventBus, port, _, _, _ ->
      val frameEvents = AtomicInteger()
      val soundEvents = AtomicInteger()
      eventBus.register<Display.DmgFrameReadyEvent> { frameEvents.incrementAndGet() }
      eventBus.register<Sound.SoundSampleEvent> { soundEvents.incrementAndGet() }

      assertTrue(port.capabilities().history().reverseInstruction())
      assertTrue(await(port.pause()).isSuccess)
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
      // A debugger pause may land inside a frame; establish the first retained replay anchor.
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)

      val first = await(port.step(DebugStepKind.INSTRUCTION)).value().snapshot()
      val second = await(port.step(DebugStepKind.INSTRUCTION)).value().snapshot()
      val framesBeforeReverse = frameEvents.get()
      val samplesBeforeReverse = soundEvents.get()

      val reversed = await(port.stepBackward(DebugStepKind.INSTRUCTION))
      assertTrue(reversed.isSuccess, reversed.toString())
      val result = reversed.value()
      assertEquals(DebugStepKind.INSTRUCTION, result.kind())
      assertEquals(first.masterTick(), result.restoredPosition().masterTick())
      assertEquals(first.frame(), result.restoredPosition().frame())
      assertEquals(first.framePosition(), result.restoredPosition().framePosition())
      assertEquals(result.restoredPosition(), result.history().cursor())
      assertEquals(second.masterTick(), result.snapshot().masterTick())
      assertEquals(second.frame(), result.snapshot().frame())
      assertEquals(first.framePosition(), result.snapshot().framePosition())
      assertTrue(result.snapshot().sequence() > second.sequence())
      assertMachineViewEquals(first, result.snapshot())
      assertEquals(
          second.execution().retiredInstructions(),
          result.snapshot().execution().retiredInstructions(),
          "debug retirement accounting remains monotonic across isolated replay",
      )
      assertEquals(framesBeforeReverse, frameEvents.get())
      assertEquals(samplesBeforeReverse, soundEvents.get())
    }
  }

  @Test
  fun forwardExecutionAfterReverseDiscardsTheRetainedFutureBranch() {
    withController { _, port, _, _, _ ->
      assertTrue(await(port.pause()).isSuccess)
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
      assertTrue(
          await(port.configureTrace(TraceConfiguration(32, EnumSet.of(TraceCategory.CPU)))).isSuccess)
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)
      assertTrue(await(port.readTrace(TraceReadRequest.initial(32))).value().entries().isNotEmpty())
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)
      val beforeReverse = await(port.historyStatus()).value()

      val reversed = await(port.stepBackward(DebugStepKind.FRAME)).value()
      assertEquals(beforeReverse.checkpointCount(), reversed.history().checkpointCount())
      assertEquals(1, reversed.history().futureCheckpointCount())
      assertEquals(beforeReverse.newest(), reversed.history().newest())

      assertTrue(await(port.step(DebugStepKind.INSTRUCTION)).isSuccess)
      val branched = await(port.historyStatus()).value()
      assertEquals(0, branched.futureCheckpointCount())
      assertEquals(beforeReverse.checkpointCount() - 1, branched.checkpointCount())
      assertEquals(
          DebugHistoryTruncationReason.BRANCH_INVALIDATED,
          branched.lastTruncationReason(),
      )
      assertEquals(reversed.replayAnchor(), branched.newest())
      assertTrue(branched.cursor().masterTick() > reversed.restoredPosition().masterTick())
    }
  }

  @Test
  fun historyStopsAtAHostDependentPeripheralTopology() {
    withController { eventBus, port, _, _, _ ->
      val selections =
          LinkedBlockingQueue<Controller.SerialPeripheralSelectionChangedEvent>()
      eventBus.register<Controller.SerialPeripheralSelectionChangedEvent>(selections::add)
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
      awaitCondition { await(port.historyStatus()).value().checkpointCount() > 0 }

      eventBus.post(
          Controller.SetSerialPeripheralEvent(Controller.SerialPeripheralSelection.PRINTER))
      val selected = awaitSerialSelection(selections, Controller.SerialPeripheralSelection.PRINTER)
      assertEquals(Controller.SerialPeripheralSelection.PRINTER, selected.selection)

      val cleared = await(port.historyStatus()).value()
      assertEquals(0, cleared.checkpointCount())
      assertEquals(
          DebugHistoryTruncationReason.TOPOLOGY_CHANGED,
          cleared.lastTruncationReason(),
      )
      assertError(
          DebugErrorCode.UNSUPPORTED_TOPOLOGY,
          await(port.configureHistory(DebugHistoryConfiguration.defaults())),
      )
      assertTrue(await(port.pause()).isSuccess)
      assertError(
          DebugErrorCode.UNSUPPORTED_TOPOLOGY,
          await(port.stepBackward(DebugStepKind.FRAME)),
      )
    }
  }

  @Test
  fun reverseInstructionCapabilitySurvivesATemporarySerialTopology() {
    withController { eventBus, _, ports, _, _ ->
      val selections =
          LinkedBlockingQueue<Controller.SerialPeripheralSelectionChangedEvent>()
      eventBus.register<Controller.SerialPeripheralSelectionChangedEvent>(selections::add)
      eventBus.post(
          Controller.SetSerialPeripheralEvent(Controller.SerialPeripheralSelection.PRINTER))
      awaitSerialSelection(selections, Controller.SerialPeripheralSelection.PRINTER)

      val replacement = programRom("DEBUG_REVERSE_AFTER_PRINTER", 0x3c, 0x18, 0xfc)
      try {
        eventBus.post(LoadRomEvent(replacement))
        val port = assertNotNull(ports.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS).debugPort)

        // Capabilities describe the session/ROM, while commands remain fail-closed under the
        // currently attached host-I/O topology.
        assertTrue(port.capabilities().history().reverseInstruction())
        assertError(
            DebugErrorCode.UNSUPPORTED_TOPOLOGY,
            await(port.configureHistory(DebugHistoryConfiguration.defaults())),
        )

        eventBus.post(
            Controller.SetSerialPeripheralEvent(
                Controller.SerialPeripheralSelection.PEER_TO_PEER))
        awaitSerialSelection(selections, Controller.SerialPeripheralSelection.PEER_TO_PEER)

        assertTrue(await(port.pause()).isSuccess)
        assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
        assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)
        assertTrue(await(port.step(DebugStepKind.INSTRUCTION)).isSuccess)
        assertTrue(await(port.step(DebugStepKind.INSTRUCTION)).isSuccess)
        assertTrue(await(port.stepBackward(DebugStepKind.INSTRUCTION)).isSuccess)
      } finally {
        replacement.delete()
      }
    }
  }

  @Test
  fun unsupportedInstructionReplayDoesNotDiscardValidFrameHistory() {
    withController(huc3Rom("DEBUG_FRAME_ONLY")) { _, port, _, _, _ ->
      assertTrue(port.capabilities().history().reverseFrame())
      assertFalse(port.capabilities().history().reverseInstruction())
      assertTrue(await(port.pause()).isSuccess)
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)
      val before = await(port.historyStatus()).value()

      assertError(
          DebugErrorCode.UNSUPPORTED_STEP,
          await(port.stepBackward(DebugStepKind.INSTRUCTION)),
      )
      assertEquals(before, await(port.historyStatus()).value())
      assertTrue(await(port.stepBackward(DebugStepKind.FRAME)).isSuccess)
    }
  }

  @Test
  fun userRewindReportsItsOwnHistoryTruncationReason() {
    val rewind = RewindManager()
    withController(rewind) { eventBus, port, _, _, _ ->
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
      awaitCondition { await(port.historyStatus()).value().checkpointCount() > 0 }

      eventBus.post(Controller.RewindEvent(true))
      eventBus.post(Controller.RewindEvent(false))

      awaitCondition {
        val status = await(port.historyStatus()).value()
        status.checkpointCount() == 0 &&
            status.lastTruncationReason() == DebugHistoryTruncationReason.USER_REWIND
      }
    }
  }

  @Test
  fun userRewindMakesTheBreakpointOwnedPauseHistorical() {
    val rewind = RewindManager()
    withController(rewind) { eventBus, port, _, _, _ ->
      awaitCondition { rewind.historySize > 0 }
      val paused = await(port.pause()).value()
      val breakpoint =
          DebugBreakpoint(
              DebugBreakpointId(66),
              true,
              DebugCounterCondition.atFrame(paused.frame() + 1),
          )
      assertTrue(await(port.setBreakpoint(breakpoint)).isSuccess)

      val step = await(port.step(DebugStepKind.FRAME))
      assertTrue(step.isSuccess, step.toString())
      assertEquals(DebugStepStopReason.BREAKPOINT, step.value().stopReason())
      val hit = await(port.lastBreakpointHit()).value()
      assertEquals(0, hit.snapshot().framePosition())
      assertTrue(hit.activePause())

      val retainedBeforeRewind = rewind.historySize
      eventBus.post(Controller.RewindEvent(true))
      awaitCondition { rewind.historySize < retainedBeforeRewind }
      eventBus.post(Controller.RewindEvent(false))
      awaitCondition {
        val result = await(port.lastBreakpointHit())
        result.isSuccess && !result.value().activePause()
      }

      val historical = await(port.lastBreakpointHit()).value()
      assertEquals(breakpoint, historical.breakpoint().orElseThrow())
      assertEquals(hit.snapshot(), historical.snapshot())
    }
  }

  @Test
  fun historyRejectsLiveSensorCartridgesInThePrimaryAndDatelSlot() {
    for ((name, type) in listOf("MBC7" to 0x22, "POCKET_CAMERA" to 0xfc)) {
      withController(
          rom = sensorRom("DEBUG_$name", type),
          rewindManager = null,
          rtcTimeSource = null,
          configureSession = { it.setSupportBatterySave(false) },
      ) { _, port, _, _, _ ->
        assertError(
            DebugErrorCode.UNSUPPORTED_TOPOLOGY,
            await(port.configureHistory(DebugHistoryConfiguration.defaults())),
        )
      }

      val sensorBytes = sensorRomBytes("SLOT_$name", type)
      withController(
          rom = romFile("DEBUG_DATEL_$name", StateCodecTestSupport.datelRom()),
          rewindManager = null,
          rtcTimeSource = null,
          configureSession = {
            it.setSupportBatterySave(false)
            it.setSlotRom(Rom(sensorBytes))
          },
      ) { _, port, _, _, _ ->
        assertError(
            DebugErrorCode.UNSUPPORTED_TOPOLOGY,
            await(port.configureHistory(DebugHistoryConfiguration.defaults())),
        )
      }
    }
  }

  @Test
  fun successfulDebugReverseClearsTheIndependentUserRewindTimeline() {
    val rewind = RewindManager()
    withController(rewind) { _, port, _, _, _ ->
      awaitCondition { rewind.historySize > 0 }
      assertTrue(await(port.pause()).isSuccess)
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)
      assertTrue(rewind.historySize > 0)

      assertTrue(await(port.stepBackward(DebugStepKind.FRAME)).isSuccess)
      assertEquals(0, rewind.historySize)
    }
  }

  @Test
  fun failedReverseRestoreRollsBackAndCompletesWithoutKillingTheOwner() {
    val time = FailingTimeSource(360_000)
    withController(mbc3Rom("DEBUG_RTC_FAILURE"), time) { _, port, _, _, _ ->
      assertTrue(await(port.pause()).isSuccess)
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
      assertTrue(
          await(
                  port.configureTrace(
                      TraceConfiguration(256, EnumSet.of(TraceCategory.CPU))))
              .isSuccess)
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)
      val before = await(port.snapshot()).value()
      val traceBefore = await(port.readTrace(TraceReadRequest.initial(256))).value()
      assertTrue(traceBefore.entries().isNotEmpty())

      // Two paused-clock reads capture rollback state; fail only at the post-mutation re-anchor.
      time.failAfterSuccessfulCalls(2)
      assertError(
          DebugErrorCode.INTERNAL_ERROR,
          await(port.stepBackward(DebugStepKind.FRAME)),
      )
      assertEquals(1, time.failureCount)
      time.resume()

      val after = await(port.snapshot()).value()
      assertEquals(before.masterTick(), after.masterTick())
      assertEquals(before.frame(), after.frame())
      assertMachineViewEquals(before, after)
      val traceAfter = await(port.readTrace(TraceReadRequest.initial(256))).value()
      assertTrue(traceAfter.entries().isEmpty())
      assertEquals(traceBefore.nextSequence(), traceAfter.nextSequence())
      assertTrue(
          traceAfter.droppedEventCount() >=
              traceBefore.droppedEventCount() + traceBefore.entries().size)
      assertTrue(await(port.stepBackward(DebugStepKind.FRAME)).isSuccess)
    }
  }

  @Test
  fun breakpointAutomaticallyPausesAtTheExactTickWithoutAdvancingAgain() {
    withController { _, port, _, _, _ ->
      val paused = await(port.pause()).value()
      val traceConfiguration =
          TraceConfiguration(256, EnumSet.of(TraceCategory.CPU, TraceCategory.MEMORY))
      assertEquals(traceConfiguration, await(port.configureTrace(traceConfiguration)).value())

      val targetTick = paused.masterTick() + 64
      val breakpoint =
          DebugBreakpoint(
              DebugBreakpointId(61),
              true,
              DebugCounterCondition.atMasterTick(targetTick),
          )
      assertEquals(breakpoint, await(port.setBreakpoint(breakpoint)).value())
      assertTrue(await(port.resume()).isSuccess)

      val hit = awaitBreakpointHit(port)
      assertEquals(breakpoint.id(), hit.breakpointId())
      assertEquals(targetTick, hit.matchMasterTick())
      assertEquals(targetTick, hit.snapshot().masterTick())
      assertTrue(hit.snapshot().paused())
      assertEquals(breakpoint, hit.breakpoint().orElseThrow())
      assertTrue(hit.activePause())

      val stopped = await(port.snapshot()).value()
      assertEquals(targetTick, stopped.masterTick())
      assertEquals(
          hit.snapshot().execution().retiredInstructions(),
          stopped.execution().retiredInstructions(),
      )
      Thread.sleep(25)
      val stillStopped = await(port.snapshot()).value()
      assertEquals(stopped.masterTick(), stillStopped.masterTick())
      assertEquals(
          stopped.execution().retiredInstructions(),
          stillStopped.execution().retiredInstructions(),
      )
      assertTrue(await(port.lastBreakpointHit()).value().activePause())

      val trace = await(port.readTrace(TraceReadRequest.initial(256))).value()
      assertTrue(trace.entries().isNotEmpty())
      assertTrue(
          trace.entries().all {
            it.category() == TraceCategory.CPU || it.category() == TraceCategory.MEMORY
          })

      val replacement =
          DebugBreakpoint(
              breakpoint.id(),
              true,
              DebugCounterCondition.atMasterTick(targetTick - 1),
          )
      assertEquals(replacement, await(port.setBreakpoint(replacement)).value())
      val retained = await(port.lastBreakpointHit()).value()
      assertEquals(breakpoint, retained.breakpoint().orElseThrow())
      assertTrue(retained.activePause())

      assertTrue(await(port.resume()).isSuccess)
      val historical = await(port.lastBreakpointHit()).value()
      assertEquals(breakpoint, historical.breakpoint().orElseThrow())
      assertFalse(historical.activePause())
      assertEquals(hit.snapshot(), historical.snapshot())
    }
  }

  @Test
  fun forcedLifecycleBoundaryMovementMakesAMidFrameHitHistorical() {
    withController { eventBus, port, _, controller, _ ->
      val paused = await(port.pause()).value()
      val frameTicks = controllerSession(controller).gameboy.clockSpec.controllerTicksPerFrame()
      val targetPosition = 64
      val ticksToTarget =
          if (paused.framePosition() < targetPosition) {
            targetPosition - paused.framePosition()
          } else {
            frameTicks - paused.framePosition() + targetPosition
          }
      val breakpoint =
          DebugBreakpoint(
              DebugBreakpointId(65),
              true,
              DebugCounterCondition.atMasterTick(paused.masterTick() + ticksToTarget),
          )
      assertTrue(await(port.setBreakpoint(breakpoint)).isSuccess)
      assertTrue(await(port.resume()).isSuccess)

      val hit = awaitBreakpointHit(port)
      assertEquals(targetPosition, hit.snapshot().framePosition())
      assertTrue(hit.activePause())

      // This stale cancellation is intentionally a no-op after the controller reaches its normal
      // frame boundary, but reaching that boundary still advances beyond the breakpoint stop.
      eventBus.post(Controller.CancelRomOpenEvent(Long.MIN_VALUE))
      awaitCondition { !await(port.lastBreakpointHit()).value().activePause() }

      val historical = await(port.lastBreakpointHit()).value()
      assertEquals(breakpoint, historical.breakpoint().orElseThrow())
      assertEquals(hit.snapshot(), historical.snapshot())
    }
  }

  @Test
  fun ppuBreakpointPausesOnTheObservedLineTransitionWithoutAdvancingAgain() {
    withController { _, port, _, _, _ ->
      val paused = await(port.pause()).value()
      assertTrue(port.capabilities().supports(DebugBreakpointKind.PPU_STATE))
      val targetLine = (paused.ppu().line() + 1) % 154
      val breakpoint =
          DebugBreakpoint(
              DebugBreakpointId(63),
              true,
              DebugPpuCondition.atLy(targetLine),
          )
      assertEquals(breakpoint, await(port.setBreakpoint(breakpoint)).value())
      assertTrue(await(port.resume()).isSuccess)

      val hit = awaitBreakpointHit(port)
      assertEquals(breakpoint.id(), hit.breakpointId())
      assertEquals(hit.matchMasterTick(), hit.snapshot().masterTick())
      assertEquals(targetLine, hit.snapshot().ppu().line())
      assertTrue(hit.snapshot().paused())

      Thread.sleep(25)
      assertEquals(hit.matchMasterTick(), await(port.snapshot()).value().masterTick())
    }
  }

  @Test
  fun serialBreakpointPausesOnTheTransferStartTickWithoutAdvancingAgain() {
    withController(
        programRom(
            "SERIAL_BREAK",
            0x3e,
            0xa5, // LD A,$A5
            0xea,
            0x01,
            0xff, // LD ($FF01),A
            0x3e,
            0x81, // LD A,$81
            0xea,
            0x02,
            0xff, // LD ($FF02),A
            0x18,
            0xf4, // JR $0100
        )) { _, port, _, _, _ ->
          assertTrue(await(port.pause()).isSuccess)
          assertTrue(port.capabilities().supports(DebugBreakpointKind.SERIAL))
          val breakpoint =
              DebugBreakpoint(
                  DebugBreakpointId(64),
                  true,
                  DebugSerialCondition(
                      DebugSerialCondition.Event.TRANSFER_STARTED,
                      0xa5,
                  ),
              )
          assertEquals(breakpoint, await(port.setBreakpoint(breakpoint)).value())
          assertTrue(await(port.resume()).isSuccess)

          val hit = awaitBreakpointHit(port)
          assertEquals(breakpoint.id(), hit.breakpointId())
          assertEquals(hit.matchMasterTick(), hit.snapshot().masterTick())
          assertTrue(hit.snapshot().paused())

          Thread.sleep(25)
          assertEquals(hit.matchMasterTick(), await(port.snapshot()).value().masterTick())
        }
  }

  @Test
  fun instructionStepReportsBreakpointWhenTheNextInstructionMatches() {
    withController { _, port, _, _, _ ->
      val paused = await(port.pause()).value()
      val breakpoint =
          DebugBreakpoint(
              DebugBreakpointId(62),
              true,
              DebugPcCondition.at(paused.registers().pc()),
          )
      assertTrue(await(port.setBreakpoint(breakpoint)).isSuccess)

      val step = await(port.step(DebugStepKind.INSTRUCTION))
      assertTrue(step.isSuccess, step.toString())
      assertEquals(DebugStepStopReason.BREAKPOINT, step.value().stopReason())
      assertEquals(1, step.value().instructionsRetired())
      assertTrue(step.value().snapshot().paused())

      val hit = await(port.lastBreakpointHit()).value()
      assertEquals(breakpoint.id(), hit.breakpointId())
      assertEquals(step.value().snapshot().masterTick(), hit.snapshot().masterTick())
      assertEquals(
          step.value().snapshot().execution().retiredInstructions(),
          hit.snapshot().execution().retiredInstructions(),
      )
    }
  }

  @Test
  fun successfulStateLoadClearsTraceWithoutResettingItsSequenceSpace() {
    withController { eventBus, port, _, _, stateSession ->
      val completed = LinkedBlockingQueue<StateOperationCompletedEvent>()
      eventBus.register<StateOperationCompletedEvent>(completed::add)
      assertTrue(await(port.pause()).isSuccess)
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
      assertTrue(
          await(
                  port.configureTrace(
                      TraceConfiguration(
                          256,
                          EnumSet.of(TraceCategory.CPU, TraceCategory.MEMORY),
                      )))
              .isSuccess)
      assertTrue(await(port.step(DebugStepKind.INSTRUCTION)).isSuccess)
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)

      val slot = StateRef.Slot(7)
      eventBus.post(StateSaveRequestEvent(910, stateSession.sessionId, slot, null, null))
      awaitStateCompletion(completed, 910)

      val beforeStop = await(port.snapshot()).value()
      val breakpoint =
          DebugBreakpoint(
              DebugBreakpointId(67),
              true,
              DebugCounterCondition.atFrame(beforeStop.frame() + 1),
          )
      assertTrue(await(port.setBreakpoint(breakpoint)).isSuccess)
      val stoppingStep = await(port.step(DebugStepKind.FRAME))
      assertTrue(stoppingStep.isSuccess, stoppingStep.toString())
      assertEquals(DebugStepStopReason.BREAKPOINT, stoppingStep.value().stopReason())
      val breakpointHit = await(port.lastBreakpointHit()).value()
      assertTrue(breakpointHit.activePause())

      val before = await(port.readTrace(TraceReadRequest.initial(256))).value()
      assertTrue(before.entries().isNotEmpty())

      eventBus.post(StateLoadRefRequestEvent(911, stateSession.sessionId, slot))
      awaitStateCompletion(completed, 911)

      val historicalHit = await(port.lastBreakpointHit()).value()
      assertFalse(historicalHit.activePause())
      assertEquals(breakpoint, historicalHit.breakpoint().orElseThrow())
      assertEquals(breakpointHit.snapshot(), historicalHit.snapshot())

      val after = await(port.readTrace(TraceReadRequest.initial(256))).value()
      assertTrue(after.entries().isEmpty())
      assertEquals(before.nextSequence(), after.nextSequence())
      assertEquals(after.nextSequence(), after.oldestAvailableSequence())
      assertTrue(after.droppedEventCount() >= before.droppedEventCount() + before.entries().size)
      val history = await(port.historyStatus()).value()
      assertEquals(0, history.checkpointCount())
      assertEquals(
          DebugHistoryTruncationReason.SESSION_BOUNDARY,
          history.lastTruncationReason(),
      )
    }
  }

  @Test
  fun pauseAndStepsStayOnCoherentInstructionAndFrameSafePoints() {
    withController { eventBus, port, _, _, _ ->
      val paused = await(port.pause())
      assertTrue(paused.isSuccess)
      assertTrue(paused.value().paused())

      val instruction = await(port.step(DebugStepKind.INSTRUCTION))
      assertTrue(instruction.isSuccess)
      assertEquals(DebugStepStopReason.INSTRUCTION_RETIRED, instruction.value().stopReason())
      assertEquals(1, instruction.value().instructionsRetired())
      assertTrue(instruction.value().ticksExecuted() > 0)
      assertTrue(instruction.value().snapshot().paused())

      val beforeFrame = instruction.value().snapshot()
      val frame = await(port.step(DebugStepKind.FRAME))
      assertTrue(frame.isSuccess)
      assertEquals(DebugStepStopReason.FRAME_BOUNDARY, frame.value().stopReason())
      assertEquals(0, frame.value().snapshot().framePosition())
      assertEquals(beforeFrame.frame() + 1, frame.value().snapshot().frame())
      assertTrue(frame.value().snapshot().paused())

      val resumed = await(port.resume())
      assertTrue(resumed.isSuccess)
      assertFalse(resumed.value().paused())

      // The public port is the only object delivered to the producer/UI thread.
      assertEquals(port.sessionGeneration(), resumed.value().sessionGeneration())
      assertNotNull(eventBus)
    }
  }

  @Test
  fun memoryInspectionAcceptsOnlyExplicitSideEffectFreeRanges() {
    withController { _, port, _, _, _ ->
      val paused = await(port.pause()).value()
      assertTrue(port.capabilities().coherentInspection())
      assertEquals(16, port.capabilities().maxInspectionBlocks())
      assertEquals(4096, port.capabilities().maxInspectionBytes())
      assertTrue(port.capabilities().supportsInspection(DebugInspectionSection.GRAPHICS))
      assertTrue(port.capabilities().supportsInspection(DebugInspectionSection.AUDIO))
      assertEquals(1024, port.capabilities().maxInspectionTraceEntries())

      val coherentRequest =
          DebugInspectionRequest(
              listOf(
                  DebugAnchoredMemoryRequest(
                      DebugInspectionAnchor.PROGRAM_COUNTER,
                      0,
                      3,
                  ),
                  DebugAnchoredMemoryRequest(
                      DebugInspectionAnchor.STACK_POINTER,
                      -2,
                      2,
                  ),
              ),
              listOf(DebugMemoryRequest(DebugAddressSpace.HIGH_RAM, 0xff80, 4)),
          )
      val inspected = await(port.inspect(coherentRequest))
      assertTrue(inspected.isSuccess)
      val inspection = inspected.value()
      assertEquals(coherentRequest, inspection.request())
      assertEquals(paused.masterTick(), inspection.snapshot().masterTick())
      assertEquals(paused.frame(), inspection.snapshot().frame())
      assertEquals(paused.framePosition(), inspection.snapshot().framePosition())
      assertEquals(paused.registers(), inspection.snapshot().registers())
      assertEquals(
          inspection.snapshot().registers().pc(),
          inspection.anchoredBlocks()[0].startAddress(),
      )
      assertEquals(DebugAddressSpace.ROM, inspection.anchoredBlocks()[0].addressSpace())
      assertEquals(
          inspection.snapshot().registers().sp() - 2,
          inspection.anchoredBlocks()[1].startAddress(),
      )
      assertEquals(4, inspection.memoryBlocks().single().length())

      assertTrue(
          await(
                  port.configureTrace(
                      TraceConfiguration(8, EnumSet.of(TraceCategory.CPU))))
              .isSuccess)
      val peripheralRequest =
          DebugInspectionRequest(
              emptyList(),
              emptyList(),
              EnumSet.allOf(DebugInspectionSection::class.java),
              Optional.of(TraceReadRequest.initial(8)),
          )
      val peripheralInspection = await(port.inspect(peripheralRequest)).value()
      assertTrue(peripheralInspection.graphics().isPresent)
      assertTrue(peripheralInspection.audio().isPresent)
      assertTrue(peripheralInspection.trace().isPresent)
      assertEquals(0x2000, peripheralInspection.graphics().orElseThrow().vramBank0().length())
      assertEquals(4, peripheralInspection.audio().orElseThrow().channels().size)

      val unsafePcOffset = 0x8000 - inspection.snapshot().registers().pc()
      val unsafeInspection =
          await(
              port.inspect(
                  DebugInspectionRequest(
                      listOf(
                          DebugAnchoredMemoryRequest(
                              DebugInspectionAnchor.PROGRAM_COUNTER,
                              unsafePcOffset,
                              1,
                          )),
                      listOf(DebugMemoryRequest(DebugAddressSpace.HIGH_RAM, 0xff80, 1)),
                  )))
      assertTrue(unsafeInspection.isFailure)
      assertEquals(DebugErrorCode.SIDE_EFFECTFUL_ADDRESS, unsafeInspection.error().code())

      val hram =
          await(
              port.readMemory(
                  DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0xff80, 16)))
      assertTrue(hram.isSuccess)
      assertEquals(16, hram.value().length())

      val unsafe =
          await(
              port.readMemory(
                  DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0xff0f, 1)))
      assertTrue(unsafe.isFailure)
      assertEquals(DebugErrorCode.SIDE_EFFECTFUL_ADDRESS, unsafe.error().code())

      val unsupported =
          await(
              port.readMemory(
                  DebugMemoryRequest(DebugAddressSpace.IO_REGISTERS, 0xff0f, 1)))
      assertTrue(unsupported.isFailure)
      assertEquals(DebugErrorCode.UNSUPPORTED_ADDRESS_SPACE, unsupported.error().code())
    }
  }

  @Test
  fun debuggerMemoryWritesRequirePauseAndInvalidateRetainedHistory() {
    withController { _, port, _, _, _ ->
      assertTrue(port.capabilities().memoryWrite())
      assertError(
          DebugErrorCode.NOT_PAUSED,
          await(port.writeMemory(DebugMemoryWrite(DebugAddressSpace.WORK_RAM, 0xc000, 0x7f))),
      )

      assertTrue(await(port.pause()).isSuccess)
      assertTrue(await(port.configureHistory(DebugHistoryConfiguration.defaults())).isSuccess)
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)

      val written =
          await(port.writeMemory(DebugMemoryWrite(DebugAddressSpace.WORK_RAM, 0xc000, 0x7f)))
      assertTrue(written.isSuccess)
      assertTrue(written.value().paused())
      val workRam =
          await(port.readMemory(DebugMemoryRequest(DebugAddressSpace.WORK_RAM, 0xc000, 1)))
      assertEquals(0x7f, workRam.value().unsignedByteAt(0))
      val echo =
          await(port.readMemory(DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0xe000, 1)))
      assertEquals(0x7f, echo.value().unsignedByteAt(0))

      assertError(
          DebugErrorCode.UNSUPPORTED_ADDRESS_SPACE,
          await(port.writeMemory(DebugMemoryWrite(DebugAddressSpace.ROM, 0x0100, 0x00))),
      )
      val history = await(port.historyStatus()).value()
      assertEquals(0, history.checkpointCount())
      assertEquals(DebugHistoryTruncationReason.BRANCH_INVALIDATED, history.lastTruncationReason())
      assertTrue(await(port.readTrace(TraceReadRequest.initial(32))).value().entries().isEmpty())
    }
  }

  @Test
  fun debuggerAudioMixerChannelsCanBeChangedWhileTheSessionIsRunning() {
    withController { _, port, _, _, _ ->
      assertTrue(port.capabilities().supportsInspection(DebugInspectionSection.AUDIO))

      val muted = await(port.setAudioChannelEnabled(2, false))
      assertTrue(muted.isSuccess)
      assertFalse(muted.value().paused())

      val enabled = await(port.setAudioChannelEnabled(2, true))
      assertTrue(enabled.isSuccess)
      assertFalse(enabled.value().paused())

      assertError(DebugErrorCode.INVALID_ARGUMENT, await(port.setAudioChannelEnabled(5, false)))
    }
  }

  @Test
  fun replacingSessionRevokesOldGenerationAndPublishesANewPort() {
    withController { eventBus, oldPort, ports, _, _ ->
      val paused = await(oldPort.pause())
      assertTrue(paused.isSuccess)
      assertTrue(paused.value().framePosition() > 0)

      val revocationEntered = CountDownLatch(1)
      val releaseRevocation = CountDownLatch(1)
      eventBus.register<SessionDebugPortEvent> {
        if (it.debugPort == null) {
          revocationEntered.countDown()
          releaseRevocation.await()
        }
      }

      val replacement = namedRom("DEBUG_REPLACEMENT")
      try {
        eventBus.post(LoadRomEvent(replacement))
        assertTrue(revocationEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        // Revocation is terminal before arbitrary lifecycle subscribers are invoked.
        val stale = await(oldPort.snapshot())
        assertTrue(stale.isFailure)
        assertEquals(DebugErrorCode.SESSION_REPLACED, stale.error().code())

        releaseRevocation.countDown()
        val next = assertNotNull(ports.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS).debugPort)
        assertTrue(next.sessionGeneration() > oldPort.sessionGeneration())
        assertTrue(await(next.snapshot()).isSuccess)
      } finally {
        releaseRevocation.countDown()
        replacement.delete()
      }
    }
  }

  @Test
  fun pauseOwnershipAndExpectedStateErrorsAreExplicit() {
    withController { eventBus, port, _, _, _ ->
      assertError(DebugErrorCode.ALREADY_RUNNING, await(port.resume()))
      assertTrue(await(port.pause()).isSuccess)
      assertError(DebugErrorCode.ALREADY_PAUSED, await(port.pause()))

      val instruction = await(port.step(DebugStepKind.INSTRUCTION))
      assertTrue(instruction.isSuccess)
      assertEquals(1, instruction.value().instructionsRetired())

      val resumed = await(port.resume())
      assertTrue(resumed.isSuccess)
      assertFalse(resumed.value().paused())
      assertError(DebugErrorCode.NOT_PAUSED, await(port.step(DebugStepKind.INSTRUCTION)))

      // A pause requested from the desktop is resumable from the debugger too, so every playback
      // surface controls the same effective emulation state.
      eventBus.post(Controller.PauseEmulationEvent())
      assertTrue(await(port.snapshot()).value().paused())
      assertFalse(await(port.resume()).value().paused())

      // Debugger and desktop pause requests can overlap, but either Resume control releases both
      // interactive pause owners and returns the emulator to the same running state.
      eventBus.post(Controller.PauseEmulationEvent())
      assertTrue(await(port.step(DebugStepKind.INSTRUCTION)).isSuccess)
      val debugResume = await(port.resume())
      assertTrue(debugResume.isSuccess)
      assertFalse(debugResume.value().paused())

      assertTrue(await(port.pause()).isSuccess)
      eventBus.post(Controller.ResumeEmulationEvent())
      awaitCondition { !await(port.snapshot()).value().paused() }
    }
  }

  @Test
  fun effectivePlaybackEventsFollowCombinedDesktopDebuggerAndBreakpointOwnership() {
    withController { eventBus, port, _, _, _ ->
      val playback = LinkedBlockingQueue<Controller.SessionPlaybackStateEvent>()
      eventBus.register<Controller.SessionPlaybackStateEvent>(playback::add)

      assertTrue(await(port.pause()).value().paused())
      val debugPause = assertNotNull(playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertTrue(debugPause.paused)

      val step = await(port.step(DebugStepKind.INSTRUCTION)).value().snapshot()
      assertTrue(step.paused())
      val breakpoint =
          DebugBreakpoint(
              DebugBreakpointId(68),
              true,
              DebugCounterCondition.atMasterTick(step.masterTick() + 64),
          )
      assertTrue(await(port.setBreakpoint(breakpoint)).isSuccess)

      eventBus.post(Controller.PauseEmulationEvent())
      val bothOwners = assertNotNull(playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(debugPause.sessionGeneration, bothOwners.sessionGeneration)
      assertTrue(bothOwners.paused)

      eventBus.post(Controller.ResumeEmulationEvent())
      val resumed = assertNotNull(playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(debugPause.sessionGeneration, resumed.sessionGeneration)
      assertFalse(resumed.paused)

      val hit = awaitBreakpointHit(port)
      assertEquals(breakpoint.id(), hit.breakpointId())
      val breakpointPause = assertNotNull(playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(debugPause.sessionGeneration, breakpointPause.sessionGeneration)
      assertTrue(breakpointPause.paused)

      assertFalse(await(port.resume()).value().paused())
      val breakpointResumed = assertNotNull(playback.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(debugPause.sessionGeneration, breakpointResumed.sessionGeneration)
      assertFalse(breakpointResumed.paused)
    }
  }

  @Test
  fun pausedFrameBoundaryStillServicesApplicationStop() {
    withController { eventBus, port, _, _, _ ->
      val stopped = LinkedBlockingQueue<EmulationStoppedEvent>()
      eventBus.register<EmulationStoppedEvent>(stopped::add)

      assertTrue(await(port.pause()).isSuccess)
      val frame = await(port.step(DebugStepKind.FRAME))
      assertEquals(0, frame.value().snapshot().framePosition())

      eventBus.post(Controller.StopEmulationEvent())
      assertNotNull(stopped.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      awaitCondition { port.isClosed }
      assertError(DebugErrorCode.PORT_CLOSED, await(port.snapshot()))
    }
  }

  @Test
  fun instructionSafePauseStillServicesApplicationStopMidFrame() {
    withController { eventBus, port, _, _, _ ->
      val stopped = LinkedBlockingQueue<EmulationStoppedEvent>()
      eventBus.register<EmulationStoppedEvent>(stopped::add)

      val paused = await(port.pause())
      assertTrue(paused.isSuccess)
      assertTrue(paused.value().framePosition() > 0)

      eventBus.post(Controller.StopEmulationEvent())
      assertNotNull(stopped.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      awaitCondition { port.isClosed }
      assertError(DebugErrorCode.PORT_CLOSED, await(port.snapshot()))
    }
  }

  @Test
  fun lifecycleIntentDoesNotOvertakeEarlierStateWork() {
    withController { eventBus, port, _, _, stateSession ->
      val paused = await(port.pause())
      assertTrue(paused.isSuccess)
      assertTrue(paused.value().framePosition() > 0)

      val slot = StateRef.Slot(1)
      eventBus.post(StateSaveRequestEvent(901, stateSession.sessionId, slot, null, null))
      eventBus.post(Controller.StopEmulationEvent())

      val statePath =
          assertNotNull(stateSession.gameDirectory)
              .resolve("states")
              .resolve("slots")
              .resolve("1")
              .resolve("state.cgbstate")
      awaitCondition { Files.isRegularFile(statePath) }
      awaitCondition { port.isClosed }
    }
  }

  @Test
  fun closingDebugPortFinishesPartialFrameBeforeReturningToDesktopPause() {
    withController { eventBus, port, _, _, stateSession ->
      eventBus.post(Controller.PauseEmulationEvent())
      assertTrue(await(port.snapshot()).value().paused())

      val instruction = await(port.step(DebugStepKind.INSTRUCTION))
      assertTrue(instruction.isSuccess)
      assertTrue(instruction.value().snapshot().framePosition() > 0)

      port.close()
      val slot = StateRef.Slot(2)
      eventBus.post(StateSaveRequestEvent(903, stateSession.sessionId, slot, null, null))

      val statePath =
          assertNotNull(stateSession.gameDirectory)
              .resolve("states")
              .resolve("slots")
              .resolve("2")
              .resolve("state.cgbstate")
      awaitCondition { Files.isRegularFile(statePath) }
      assertError(DebugErrorCode.PORT_CLOSED, await(port.snapshot()))
    }
  }

  @Test
  fun closePreparationIsNotStarvedByAnInstructionSafePause() {
    withController { eventBus, port, _, _, stateSession ->
      val completed = LinkedBlockingQueue<StatePrepareCloseCompletedEvent>()
      eventBus.register<StatePrepareCloseCompletedEvent>(completed::add)
      val paused = await(port.pause())
      assertTrue(paused.isSuccess)
      assertTrue(paused.value().framePosition() > 0)

      eventBus.post(StatePrepareCloseRequestEvent(902))

      val result = assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(902, result.requestId)
      assertEquals(stateSession.sessionId, result.sessionId)
      assertTrue(result.autosaved)
      assertEquals(null, result.error)
    }
  }

  @Test
  fun desktopResumeReleasesADebuggerPause() {
    withController { eventBus, port, _, _, _ ->
      val paused = await(port.pause()).value()
      assertTrue(paused.framePosition() > 0)

      eventBus.post(Controller.ResumeEmulationEvent())
      awaitCondition { !await(port.snapshot()).value().paused() }
    }
  }

  @Test
  fun debugInputMutationExecutesOnTheControllerOwner() {
    withController { eventBus, port, _, _, _ ->
      val delivered = CountDownLatch(1)
      val deliveryThread = AtomicReference<Thread>()
      eventBus.register<ButtonPressEvent> {
        deliveryThread.set(Thread.currentThread())
        delivered.countDown()
      }

      assertTrue(await(port.setButton(DebugButton.A, true)).isSuccess)
      assertTrue(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals("coffee-gb-controller", deliveryThread.get().name)
      assertFalse(deliveryThread.get() === Thread.currentThread())
    }
  }

  @Test
  fun closingControllerMakesThePortTerminalWithoutAnOwnerlessFuture() {
    withController { _, port, _, controller, _ ->
      val inFlight = port.pause()
      controller.close()

      val outcome = await(inFlight)
      assertTrue(
          outcome.isSuccess || outcome.error().code() == DebugErrorCode.PORT_CLOSED,
          outcome.toString(),
      )
      assertTrue(port.isClosed)
      assertError(DebugErrorCode.PORT_CLOSED, await(port.snapshot()))
    }
  }

  private fun withController(
      test:
          (
              EventBusImpl,
              DebugPort,
              LinkedBlockingQueue<SessionDebugPortEvent>,
              BasicController,
              StateUxSessionEvent,
          ) -> Unit
  ) = withController(namedRom("DEBUG_PORT"), test)

  private fun withController(
      rewindManager: RewindManager,
      test:
          (
              EventBusImpl,
              DebugPort,
              LinkedBlockingQueue<SessionDebugPortEvent>,
              BasicController,
              StateUxSessionEvent,
          ) -> Unit
  ) = withController(namedRom("DEBUG_PORT"), rewindManager, test)

  private fun withController(
      rom: File,
      test:
          (
              EventBusImpl,
              DebugPort,
              LinkedBlockingQueue<SessionDebugPortEvent>,
              BasicController,
              StateUxSessionEvent,
          ) -> Unit
  ) = withController(rom, null, test)

  private fun withController(
      rom: File,
      rtcTimeSource: TimeSource,
      test:
          (
              EventBusImpl,
              DebugPort,
              LinkedBlockingQueue<SessionDebugPortEvent>,
              BasicController,
              StateUxSessionEvent,
          ) -> Unit
  ) = withController(rom, null, rtcTimeSource, test)

  private fun withController(
      rom: File,
      rewindManager: RewindManager?,
      test:
          (
              EventBusImpl,
              DebugPort,
              LinkedBlockingQueue<SessionDebugPortEvent>,
              BasicController,
              StateUxSessionEvent,
          ) -> Unit
  ) = withController(rom, rewindManager, null, test)

  private fun withController(
      rom: File,
      rewindManager: RewindManager?,
      rtcTimeSource: TimeSource?,
      test:
          (
              EventBusImpl,
              DebugPort,
              LinkedBlockingQueue<SessionDebugPortEvent>,
              BasicController,
              StateUxSessionEvent,
          ) -> Unit
  ) = withController(rom, rewindManager, rtcTimeSource, {}, test)

  private fun withController(
      rom: File,
      rewindManager: RewindManager?,
      rtcTimeSource: TimeSource?,
      configureSession: (Gameboy.GameboyConfiguration) -> Unit,
      test:
          (
              EventBusImpl,
              DebugPort,
              LinkedBlockingQueue<SessionDebugPortEvent>,
              BasicController,
              StateUxSessionEvent,
          ) -> Unit
  ) {
    val eventBus = EventBusImpl()
    val ports = LinkedBlockingQueue<SessionDebugPortEvent>()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val stateSessions = LinkedBlockingQueue<StateUxSessionEvent>()
    eventBus.register<SessionDebugPortEvent> { if (it.debugPort != null) ports.add(it) }
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<StateUxSessionEvent> { if (it.available) stateSessions.add(it) }
    val settingsDirectory = Files.createTempDirectory("coffee-gb-debug-port-settings")
    val properties =
        EmulatorProperties(settingsDirectory.resolve("settings.properties"), debounceMillis = 0)
            .also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    settings.saves.copy(
                        directory = settingsDirectory.resolve("saves"),
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          rtcTimeSource?.let(config::setRtcTimeSource)
          configureSession(config)
          PreparedSession.Ready(config, config.build())
        }
    val controller =
        if (rewindManager == null) {
          BasicController(eventBus, properties, null, preparer)
        } else {
          BasicController(
              eventBus,
              properties,
              null,
              preparer,
              SnapshotManagerFactory.DEFAULT,
              rewindManager,
          )
        }
    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val port = assertNotNull(ports.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS).debugPort)
      val stateSession = assertNotNull(stateSessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      test(eventBus, port, ports, controller, stateSession)
    } finally {
      runCatching { controller.close() }
      runCatching { eventBus.close() }
      runCatching { properties.close() }
      rom.delete()
      deleteTree(settingsDirectory)
    }
  }

  private fun deleteTree(path: java.nio.file.Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private fun controllerSession(controller: BasicController): Session {
    val field = BasicController::class.java.getDeclaredField("session")
    field.isAccessible = true
    return assertNotNull(field.get(controller) as Session?)
  }

  private fun namedRom(title: String): File {
    val bytes = ROM.readBytes()
    for (address in 0x0134 until 0x0143) {
      bytes[address] = 0
    }
    title
        .toByteArray(Charsets.US_ASCII)
        .copyInto(bytes, 0x0134, endIndex = title.length.coerceAtMost(15))
    return Files.createTempFile("coffee-gb-$title", ".gbc").toFile().also {
      it.writeBytes(bytes)
    }
  }

  private fun programRom(title: String, vararg program: Int): File {
    val rom = namedRom(title)
    val bytes = rom.readBytes()
    for (index in program.indices) {
      bytes[0x100 + index] = program[index].toByte()
    }
    rom.writeBytes(bytes)
    return rom
  }

  private fun mbc3Rom(title: String): File {
    val rom = namedRom(title)
    val bytes = rom.readBytes()
    bytes[0x147] = 0x10
    bytes[0x149] = 0x03
    rom.writeBytes(bytes)
    return rom
  }

  private fun huc3Rom(title: String): File {
    val rom = namedRom(title)
    val bytes = rom.readBytes()
    bytes[0x147] = 0xfe.toByte()
    bytes[0x149] = 0x03
    rom.writeBytes(bytes)
    return rom
  }

  private fun sensorRom(title: String, type: Int): File =
      romFile(title, sensorRomBytes(title, type))

  private fun sensorRomBytes(title: String, type: Int): ByteArray =
      StateCodecTestSupport.rom().also { bytes ->
        for (address in 0x0134 until 0x0143) bytes[address] = 0
        title
            .toByteArray(Charsets.US_ASCII)
            .copyInto(bytes, 0x0134, endIndex = title.length.coerceAtMost(15))
        bytes[0x0147] = type.toByte()
      }

  private fun romFile(title: String, bytes: ByteArray): File =
      Files.createTempFile("coffee-gb-$title", ".gbc").toFile().also { it.writeBytes(bytes) }

  private fun <T> await(stage: java.util.concurrent.CompletionStage<T>): T =
      stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

  private fun assertMachineViewEquals(
      expected: eu.rekawek.coffeegb.core.debug.DebugSnapshot,
      actual: eu.rekawek.coffeegb.core.debug.DebugSnapshot,
  ) {
    assertEquals(expected.registers(), actual.registers())
    assertEquals(expected.interrupts(), actual.interrupts())
    assertEquals(expected.timer(), actual.timer())
    assertEquals(expected.ppu(), actual.ppu())
    assertEquals(expected.apu(), actual.apu())
    assertEquals(expected.mapper(), actual.mapper())
    assertEquals(expected.execution().cpuState(), actual.execution().cpuState())
    assertEquals(expected.execution().opcode(), actual.execution().opcode())
    assertEquals(expected.execution().extendedOpcode(), actual.execution().extendedOpcode())
    assertEquals(expected.execution().machineCycle(), actual.execution().machineCycle())
    assertEquals(expected.execution().doubleSpeed(), actual.execution().doubleSpeed())
    assertEquals(expected.execution().haltBug(), actual.execution().haltBug())
  }

  private fun assertError(expected: DebugErrorCode, result: eu.rekawek.coffeegb.core.debug.DebugResult<*>) {
    assertTrue(result.isFailure, result.toString())
    assertEquals(expected, result.error().code())
  }

  private fun awaitBreakpointHit(port: DebugPort): DebugBreakpointHit {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val result = await(port.lastBreakpointHit())
      if (result.isSuccess) return result.value()
      assertEquals(DebugErrorCode.NO_BREAKPOINT_HIT, result.error().code())
      Thread.yield()
    }
    val result = await(port.lastBreakpointHit())
    assertTrue(result.isSuccess, "breakpoint did not stop the desktop controller: $result")
    return result.value()
  }

  private fun awaitStateCompletion(
      completed: LinkedBlockingQueue<StateOperationCompletedEvent>,
      requestId: Long,
  ): StateOperationCompletedEvent {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val event = completed.poll(50, TimeUnit.MILLISECONDS) ?: continue
      if (event.requestId == requestId) return event
    }
    throw AssertionError("state operation $requestId did not complete")
  }

  private fun awaitSerialSelection(
      selections: LinkedBlockingQueue<Controller.SerialPeripheralSelectionChangedEvent>,
      expected: Controller.SerialPeripheralSelection,
  ): Controller.SerialPeripheralSelectionChangedEvent {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val event = selections.poll(50, TimeUnit.MILLISECONDS) ?: continue
      if (event.selection == expected) return event
    }
    throw AssertionError("serial selection $expected did not commit")
  }

  private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      if (condition()) return
      Thread.yield()
    }
    assertTrue(condition(), "condition did not become true")
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    const val TIMEOUT_SECONDS = 10L
  }

  private class FailingTimeSource(
      private val current: Long,
  ) : TimeSource {
    @Volatile private var successfulCallsUntilFailure: Int? = null

    @Volatile var failureCount = 0
      private set

    fun failAfterSuccessfulCalls(count: Int) {
      require(count >= 0)
      successfulCallsUntilFailure = count
    }

    fun resume() {
      successfulCallsUntilFailure = null
    }

    override fun currentTimeMillis(): Long {
      val remaining = successfulCallsUntilFailure
      if (remaining != null) {
        if (remaining == 0) {
          failureCount++
          error("Injected time-source failure")
        }
        successfulCallsUntilFailure = remaining - 1
      }
      return current
    }
  }
}
