package eu.rekawek.coffeegb.controller.headless

import eu.rekawek.coffeegb.controller.replay.ReplayCompatibility
import eu.rekawek.coffeegb.controller.replay.ReplayFile
import eu.rekawek.coffeegb.controller.replay.ReplayInputSource
import eu.rekawek.coffeegb.controller.replay.ReplayPlaybackStatus
import eu.rekawek.coffeegb.controller.replay.ReplayPlayer
import eu.rekawek.coffeegb.controller.replay.ReplayRuntime
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.DebugInstrumentation
import eu.rekawek.coffeegb.core.memory.cart.RomHeaderInspector
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

/** Synchronous public facade over a fresh, isolated, one-owner-thread emulation session. */
object HeadlessBatchRunner {
  const val MAXIMUM_TICKS = 1_000_000_000L

  @JvmStatic
  fun run(
      configuration: Gameboy.GameboyConfiguration,
      request: HeadlessRunRequest,
  ): HeadlessBatchResult =
      HeadlessExecutionOwner.execute {
        executeRun(configuration, request)
      }

  @JvmStatic
  fun replay(
      replay: ReplayFile,
      configuration: Gameboy.GameboyConfiguration,
      request: HeadlessReplayRequest,
  ): HeadlessBatchResult =
      HeadlessExecutionOwner.execute {
        executeReplay(replay, configuration, request)
      }

  private fun executeRun(
      configuration: Gameboy.GameboyConfiguration,
      request: HeadlessRunRequest,
  ): HeadlessBatchResult {
    val maximumTicks = requestedTicks(request.limit, configuration)
    validateInputTimeline(request.inputs, maximumTicks)
    ReplayCompatibility.validatePlayback(configuration)

    val inputSource = ReplayInputSource()
    val isolated =
        ReplayRuntime.configuration(
            configuration,
            VirtualTimeSource(request.rtcEpochMillis),
            inputSource,
        )
    val machine = HeadlessMachineSession(ReplayRuntime.session(isolated, false))
    machine.use {
      machine.enableTerminalInspection()
      machine.enableMedia(request.capture, maximumTicks)
      machine.installBreakpoint(request.breakpoint)

      var inputIndex = 0
      var breakpointMatch: DebugInstrumentation.BreakpointMatch? = null
      while (machine.completedTickCount < maximumTicks && breakpointMatch == null) {
        checkInterrupted()
        val tick = machine.completedTickCount
        while (inputIndex < request.inputs.size && request.inputs[inputIndex].tick == tick) {
          val transition = request.inputs[inputIndex++]
          inputSource.apply(transition.player, transition.absoluteMask)
        }
        machine.tick()
        breakpointMatch = machine.pollBreakpointMatch()
      }

      val reason =
          if (breakpointMatch != null) HeadlessTerminationReason.BREAKPOINT
          else
              when (request.limit) {
                is HeadlessExecutionLimit.Ticks -> HeadlessTerminationReason.TICK_LIMIT
                is HeadlessExecutionLimit.Frames -> HeadlessTerminationReason.FRAME_LIMIT
              }
      return terminalResult(
          machine,
          isolated,
          reason,
          request.inspection,
          breakpointMatch,
          request.rtcEpochMillis,
          replayStatus = null,
      )
    }
  }

  private fun executeReplay(
      replay: ReplayFile,
      configuration: Gameboy.GameboyConfiguration,
      request: HeadlessReplayRequest,
  ): HeadlessBatchResult {
    require(request.maximumTicks <= MAXIMUM_TICKS) {
      "Headless replay budget exceeds the $MAXIMUM_TICKS-tick safety limit"
    }
    ReplayPlayer.open(replay, configuration).use { player ->
      val machine = player.machine
      machine.enableTerminalInspection()
      val finalReplayTick = replay.checkpoints.last().tick
      val captureTicks =
          if (finalReplayTick >= request.maximumTicks - 1L) request.maximumTicks
          else finalReplayTick + 1L
      machine.enableMedia(request.capture, captureTicks)
      machine.installBreakpoint(request.breakpoint)

      var executed = 0L
      var lastStatus: ReplayPlaybackStatus? = null
      var breakpointMatch: DebugInstrumentation.BreakpointMatch? = null
      var reason: HeadlessTerminationReason? = null
      while (executed < request.maximumTicks && reason == null) {
        checkInterrupted()
        val before = machine.completedTickCount
        val status = player.step()
        executed = Math.addExact(executed, machine.completedTickCount - before)
        lastStatus = status
        if (status is ReplayPlaybackStatus.Diverged) {
          // Checkpoint comparison deliberately precedes breakpoint polling.
          reason = HeadlessTerminationReason.REPLAY_DIVERGED
          machine.pollBreakpointMatch()
          continue
        }
        breakpointMatch = machine.pollBreakpointMatch()
        if (breakpointMatch != null) {
          reason = HeadlessTerminationReason.BREAKPOINT
        } else if (status is ReplayPlaybackStatus.Completed) {
          reason = HeadlessTerminationReason.REPLAY_COMPLETED
        }
      }
      if (reason == null) {
        reason = HeadlessTerminationReason.REPLAY_BUDGET_EXHAUSTED
      }
      return terminalResult(
          machine,
          configuration,
          reason,
          request.inspection,
          breakpointMatch,
          replay.initialConditions.rtcEpochMillis,
          lastStatus,
      )
    }
  }

  private fun terminalResult(
      machine: HeadlessMachineSession,
      configuration: Gameboy.GameboyConfiguration,
      reason: HeadlessTerminationReason,
      inspectionRequest: eu.rekawek.coffeegb.core.debug.DebugInspectionRequest,
      breakpointMatch: DebugInstrumentation.BreakpointMatch?,
      rtcEpochMillis: Long,
      replayStatus: ReplayPlaybackStatus?,
  ): HeadlessBatchResult {
    val position = machine.position
    val inspection = machine.inspect(inspectionRequest)
    val breakpointHit = breakpointMatch?.let { machine.breakpointHit(it, inspection) }
    return HeadlessBatchResult(
        reason,
        position,
        romMetadata(configuration),
        rtcEpochMillis,
        machine.hashes(),
        inspection,
        breakpointHit,
        replayStatus,
        machine.latestFrame(),
        machine.pcm(),
    )
  }

  private fun requestedTicks(
      limit: HeadlessExecutionLimit,
      configuration: Gameboy.GameboyConfiguration,
  ): Long {
    val ticks =
        when (limit) {
          is HeadlessExecutionLimit.Ticks -> limit.count
          is HeadlessExecutionLimit.Frames ->
              Math.multiplyExact(
                  limit.count,
                  configuration.clockSpec.controllerTicksPerFrame().toLong(),
              )
        }
    require(ticks <= MAXIMUM_TICKS) {
      "Headless execution exceeds the $MAXIMUM_TICKS-tick safety limit"
    }
    return ticks
  }

  private fun validateInputTimeline(
      inputs: List<HeadlessInputTransition>,
      maximumTicks: Long,
  ) {
    var previousTick = -1L
    var previousPlayer = -1
    val masks = IntArray(4)
    inputs.forEach { input ->
      require(input.tick < maximumTicks) {
        "Headless input at tick ${input.tick} is outside the execution bound"
      }
      require(
          input.tick > previousTick ||
              input.tick == previousTick && input.player > previousPlayer,
      ) {
        "Headless inputs must be strictly ordered by tick and player without duplicates"
      }
      require(masks[input.player] != input.absoluteMask) {
        "Headless input at tick ${input.tick} does not change player ${input.player}"
      }
      masks[input.player] = input.absoluteMask
      previousTick = input.tick
      previousPlayer = input.player
    }
  }

  private fun romMetadata(configuration: Gameboy.GameboyConfiguration): HeadlessRomMetadata {
    val image = configuration.rom.image
    val header = RomHeaderInspector.inspect(image)
    val digest = MessageDigest.getInstance("SHA-256").digest(image.bytes())
    return HeadlessRomMetadata(
        header.title(),
        image.size(),
        digest,
        configuration.hardwareProfile.id(),
        header.cgbFlag(),
        header.sgbFlag(),
        header.cartridgeType(),
        header.romSizeCode(),
        header.ramSizeCode(),
        header.nintendoLogoValid(),
        header.headerChecksumValid(),
    )
  }

  private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) {
      throw InterruptedException("Headless batch owner thread was interrupted")
    }
  }
}

/** Small testable boundary that prevents any live Session from escaping its owner thread. */
internal object HeadlessExecutionOwner {
  private val sequence = AtomicLong()

  fun <T> execute(action: () -> T): T {
    val task = FutureTask(action)
    val thread = Thread(task, "coffee-gb-headless-${sequence.incrementAndGet()}")
    thread.isDaemon = true
    thread.start()
    try {
      return task.get()
    } catch (failure: InterruptedException) {
      task.cancel(true)
      var interruptedAgain = false
      while (thread.isAlive) {
        try {
          thread.join()
        } catch (_: InterruptedException) {
          interruptedAgain = true
        }
      }
      Thread.currentThread().interrupt()
      if (interruptedAgain) {
        failure.addSuppressed(
            InterruptedException("Interrupted again while awaiting headless owner cleanup"),
        )
      }
      throw failure
    } catch (failure: ExecutionException) {
      throw failure.cause ?: failure
    }
  }
}
