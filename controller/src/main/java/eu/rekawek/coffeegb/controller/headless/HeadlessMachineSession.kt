package eu.rekawek.coffeegb.controller.headless

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.replay.ReplayPosition
import eu.rekawek.coffeegb.controller.replay.ReplayStateHasher
import eu.rekawek.coffeegb.controller.replay.ReplayStateHashes
import eu.rekawek.coffeegb.core.debug.DebugBreakpointHit
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionResult
import eu.rekawek.coffeegb.core.debug.DebugInstrumentation
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory
import java.util.EnumSet

/** One mutable machine and all of its headless-only counters, confined to its creating thread. */
internal class HeadlessMachineSession(
    internal val session: Session,
    private val initialTick: Long = 0L,
    initialFrame: Long = 0L,
) : AutoCloseable {
  private val ownerThread = Thread.currentThread()
  private val frameTicks = session.gameboy.clockSpec.controllerTicksPerFrame()

  private var nextTick = initialTick
  private var currentFrame = initialFrame
  private var ticksIntoFrame = 0
  private var inspectionSequence = 0L
  private var media: HeadlessMediaCapture? = null
  private var instrumentation: DebugInstrumentation? = null
  private var retirementTracking = false
  private var closed = false

  val position: HeadlessPosition
    get() {
      checkOwnerAndOpen()
      return HeadlessPosition(nextTick - initialTick, currentFrame, ticksIntoFrame)
    }

  val completedTickCount: Long
    get() {
      checkOwnerAndOpen()
      return nextTick - initialTick
    }

  val replayPosition: ReplayPosition
    get() {
      checkOwnerAndOpen()
      return ReplayPosition(if (nextTick == initialTick) null else nextTick - 1L, currentFrame)
    }

  val nextReplayTick: Long
    get() {
      checkOwnerAndOpen()
      return nextTick
    }

  /** Executes exactly one controller tick and advances only controller-owned fixed counters. */
  fun tick(): Long {
    checkOwnerAndOpen()
    session.gameboy.tick()
    val executedTick = nextTick
    nextTick = Math.addExact(nextTick, 1L)
    ticksIntoFrame++
    if (ticksIntoFrame == frameTicks) {
      ticksIntoFrame = 0
      currentFrame = Math.addExact(currentFrame, 1L)
      instrumentation?.onFrameBoundary(currentFrame)
    }
    media?.onTickCompleted(nextTick - initialTick, currentFrame)
    return executedTick
  }

  fun enableMedia(options: HeadlessCaptureOptions, maximumTicks: Long) {
    checkOwnerAndOpen()
    check(media == null) { "Headless media capture is already configured" }
    val created = HeadlessMediaCapture.create(session, options, maximumTicks) ?: return
    try {
      created.attach()
      media = created
    } catch (failure: Throwable) {
      try {
        created.close()
      } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
      }
      throw failure
    }
  }

  /** Starts the detached terminal report's run-local retired-instruction counter. */
  fun enableTerminalInspection() {
    checkOwnerAndOpen()
    ensureRetirementTracking()
  }

  fun installBreakpoint(breakpoint: DebugBreakpoint?) {
    checkOwnerAndOpen()
    if (breakpoint == null) return
    check(instrumentation == null) { "Headless breakpoint is already configured" }
    ensureRetirementTracking()
    val created =
        DebugInstrumentation(
            1,
            1,
            1,
            EnumSet.allOf(DebugBreakpointKind::class.java),
            EnumSet.allOf(TraceCategory::class.java),
        )
    created.setBreakpoint(breakpoint)
    created.alignOwnerFrame(currentFrame)
    instrumentation = created
    session.gameboy.updateDebugInstrumentation(created, nextTick)
  }

  /** Polled only after replay checkpoint comparison so divergence wins on the same tick. */
  fun pollBreakpointMatch(): DebugInstrumentation.BreakpointMatch? {
    checkOwnerAndOpen()
    return instrumentation?.pollBreakpointMatch()
  }

  fun inspect(request: DebugInspectionRequest): DebugInspectionResult {
    checkOwnerAndOpen()
    require(request.traceRequest().isEmpty) {
      "Headless terminal inspection does not accept an unconfigured trace request"
    }
    ensureRetirementTracking()
    inspectionSequence = Math.addExact(inspectionSequence, 1L)
    val snapshot =
        session.gameboy.captureDebugSnapshot(
            SESSION_GENERATION,
            inspectionSequence,
            nextTick,
            currentFrame,
            ticksIntoFrame,
            true,
        )
    return session.gameboy.inspectDebugMemory(snapshot, request)
  }

  fun breakpointHit(
      match: DebugInstrumentation.BreakpointMatch,
      inspection: DebugInspectionResult,
  ): DebugBreakpointHit {
    checkOwnerAndOpen()
    return DebugBreakpointHit(
        match.breakpoint(), match.matchMasterTick(), inspection.snapshot(), true)
  }

  fun hashes(): ReplayStateHashes {
    checkOwnerAndOpen()
    return ReplayStateHasher.hash(session)
  }

  fun latestFrame(): HeadlessFrame? {
    checkOwnerAndOpen()
    return media?.latestFrame()
  }

  fun pcm(): HeadlessPcm16? {
    checkOwnerAndOpen()
    return media?.pcm(nextTick - initialTick)
  }

  override fun close() {
    if (closed) return
    checkOwner()
    var failure: Throwable? = null
    try {
      media?.close()
    } catch (caught: Throwable) {
      failure = caught
    }
    if (instrumentation != null) {
      try {
        session.gameboy.updateDebugInstrumentation(null, nextTick)
      } catch (caught: Throwable) {
        if (failure == null) failure = caught else failure.addSuppressed(caught)
      }
      instrumentation = null
    }
    if (retirementTracking) {
      try {
        session.gameboy.disableDebugRetirementTracking()
      } catch (caught: Throwable) {
        if (failure == null) failure = caught else failure.addSuppressed(caught)
      }
      retirementTracking = false
    }
    try {
      session.close()
    } catch (caught: Throwable) {
      if (failure == null) failure = caught else failure.addSuppressed(caught)
    }
    closed = true
    failure?.let { throw it }
  }

  private fun ensureRetirementTracking() {
    if (!retirementTracking) {
      session.gameboy.enableDebugRetirementTracking()
      retirementTracking = true
    }
  }

  private fun checkOwnerAndOpen() {
    checkOwner()
    check(!closed) { "Headless machine session is already closed" }
  }

  private fun checkOwner() {
    check(Thread.currentThread() === ownerThread) {
      "Headless machine session may only be used by its owner thread"
    }
  }

  private companion object {
    const val SESSION_GENERATION = 1L
  }
}
