package eu.rekawek.coffeegb.controller.agent

import eu.rekawek.coffeegb.controller.debug.DebugResultDispatcher
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.DebugButton
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugCpuState
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.debug.DebugStepResult
import eu.rekawek.coffeegb.core.debug.DebugStepStopReason
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.sound.Sound
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single-owner machine used by [eu.rekawek.coffeegb.controller.Agent].
 *
 * Game Boy construction, ticking, input, inspection and teardown all run on [ownerThread]. Public
 * debug futures are completed asynchronously so a synchronous client continuation can never run on
 * the emulation owner.
 */
internal class HeadlessAgentSession(romFile: File) : AutoCloseable {

  private val commands = ArrayBlockingQueue<OwnerCommand>(COMMAND_CAPACITY)

  /** Serializes command admission with every terminal queue drain. */
  private val commandLock = ReentrantLock()

  private val frames = ArrayBlockingQueue<IntArray>(FRAME_CAPACITY)

  private val audio = ArrayBlockingQueue<IntArray>(AUDIO_CAPACITY)

  private val closed = AtomicBoolean()

  private val debugRequestSlots = Semaphore(COMMAND_CAPACITY)

  private val bootstrap = CompletableFuture<Bootstrap>()

  @Volatile private var ownerState: OwnerState? = null

  private val ownerThread =
      Thread({ runOwner(romFile) }, "$OWNER_THREAD_PREFIX${NEXT_OWNER_ID.incrementAndGet()}").apply {
        isDaemon = true
        start()
      }

  private val headlessDebugPort = HeadlessDebugPort()

  val defaultFrameWaitTicks: Int = awaitBootstrap().defaultFrameWaitTicks

  val debugPort: DebugPort
    get() = headlessDebugPort

  fun runTicks(ticks: Int) {
    require(ticks >= 0) { "Tick count must not be negative: $ticks" }
    executeSync { state ->
      repeat(ticks) {
        ensureOpenOnOwner()
        state.tick()
      }
    }
  }

  fun runUntilFrame(maxTicks: Int) {
    require(maxTicks >= 0) { "Maximum tick count must not be negative: $maxTicks" }
    executeSync { state ->
      repeat(maxTicks) {
        ensureOpenOnOwner()
        if (state.tick()) return@executeSync
      }
    }
  }

  fun pollFrame(): IntArray? = frames.poll()

  fun drainAudio(): List<IntArray> = buildList { audio.drainTo(this) }

  override fun close() {
    val dropped =
        commandLock.withLock {
          if (!closed.compareAndSet(false, true)) return
          mutableListOf<OwnerCommand>().also(commands::drainTo)
        }
    dropped.forEach(OwnerCommand::cancel)
    ownerThread.interrupt()
    if (Thread.currentThread() !== ownerThread) {
      ownerThread.join(CLOSE_TIMEOUT_MILLIS)
      check(!ownerThread.isAlive) {
        "Agent emulation owner did not stop within ${CLOSE_TIMEOUT_MILLIS}ms"
      }
    }
  }

  private fun runOwner(romFile: File) {
    var state: OwnerState? = null
    try {
      val eventBus = EventBusImpl(null, "headless-agent", false)
      registerMediaSubscribers(eventBus)
      val configuration = Gameboy.GameboyConfiguration(Rom(romFile)).setSupportBatterySave(false)
      val machine = configuration.build()
      machine.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null)
      machine.enableDebugRetirementTracking()
      state = OwnerState(machine, eventBus)
      ownerState = state
      bootstrap.complete(
          Bootstrap(
              Math.toIntExact(
                  configuration.clockSpec.ticksForSeconds(
                      1, eu.rekawek.coffeegb.core.hardware.ClockSpec.Rounding.CEILING))))

      while (!closed.get()) {
        val command = if (state.paused) commands.take() else commands.poll()
        if (command != null) {
          command.execute()
        } else {
          state.tick()
        }
      }
    } catch (interrupted: InterruptedException) {
      if (!closed.get()) {
        bootstrap.completeExceptionally(interrupted)
        Thread.currentThread().interrupt()
      }
    } catch (failure: Throwable) {
      bootstrap.completeExceptionally(failure)
      closeAfterOwnerFailure(failure)
    } finally {
      val dropped =
          commandLock.withLock {
            closed.set(true)
            mutableListOf<OwnerCommand>().also(commands::drainTo)
          }
      ownerState = null
      state?.close()
      dropped.forEach(OwnerCommand::cancel)
      if (!bootstrap.isDone) {
        bootstrap.completeExceptionally(IllegalStateException("Agent owner stopped during startup"))
      }
    }
  }

  private fun registerMediaSubscribers(eventBus: EventBusImpl) {
    eventBus.register(
        { event ->
          val pixels = IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)
          event.toRgb(pixels, false)
          frames.offerNewest(pixels)
        },
        Display.DmgFrameReadyEvent::class.java,
    )
    eventBus.register(
        { event ->
          val pixels = IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)
          event.toRgb(pixels)
          frames.offerNewest(pixels)
        },
        Display.GbcFrameReadyEvent::class.java,
    )
    eventBus.register(
        { event -> audio.offerNewest(event.buffer.clone()) }, Sound.SoundSampleEvent::class.java)
  }

  private fun <T> ArrayBlockingQueue<T>.offerNewest(value: T) {
    if (!offer(value)) {
      poll()
      check(offer(value)) { "Bounded agent media queue did not accept after eviction" }
    }
  }

  private fun awaitBootstrap(): Bootstrap {
    try {
      return bootstrap.get()
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      close()
      throw IllegalStateException("Interrupted while starting headless Agent", failure)
    } catch (failure: ExecutionException) {
      close()
      throw IllegalStateException("Unable to start headless Agent", failure.cause)
    }
  }

  private fun <T> executeSync(action: (OwnerState) -> T): T {
    check(Thread.currentThread() !== ownerThread) {
      "Agent callers must not synchronously re-enter the emulation owner"
    }
    val command = SyncCommand(action)
    commandLock.withLock {
      check(!closed.get()) { "Agent is closed" }
      check(commands.offer(command)) { "Agent command queue is full" }
    }
    try {
      return command.result.get()
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      throw IllegalStateException("Interrupted while awaiting Agent command", failure)
    } catch (failure: ExecutionException) {
      val cause = failure.cause
      if (cause is RuntimeException) throw cause
      if (cause is Error) throw cause
      throw IllegalStateException("Agent command failed", cause)
    }
  }

  private fun <T> enqueueDebug(
      action: (OwnerState) -> DebugResult<T>
  ): CompletionStage<DebugResult<T>> {
    if (!debugRequestSlots.tryAcquire()) {
      return rejectedDebugStage(
          DebugResult.failure(DebugErrorCode.QUEUE_FULL, "Agent debug command queue is full"))
    }
    if (!DebugResultDispatcher.tryAcquire()) {
      debugRequestSlots.release()
      return rejectedDebugStage(
          DebugResult.failure(DebugErrorCode.QUEUE_FULL, "The debug result service is full"))
    }
    val command = DebugCommand(action)
    commandLock.withLock {
      if (closed.get()) {
        command.cancel()
      } else if (!commands.offer(command)) {
        command.complete(
            DebugResult.failure(DebugErrorCode.QUEUE_FULL, "Agent debug command queue is full"))
      }
    }
    return command.result.minimalCompletionStage()
  }

  private fun <T> rejectedDebugStage(
      outcome: DebugResult<T>
  ): CompletionStage<DebugResult<T>> =
      CompletableFuture.completedFuture(outcome).minimalCompletionStage()

  private fun requireOwnerState(): OwnerState {
    check(Thread.currentThread() === ownerThread) { "Game Boy state is owned by $ownerThread" }
    return checkNotNull(ownerState) { "Agent owner is not active" }
  }

  private fun ensureOpenOnOwner() {
    if (closed.get()) throw AgentClosedException()
  }

  private fun closeAfterOwnerFailure(failure: Throwable) {
    val dropped =
        commandLock.withLock {
          closed.set(true)
          mutableListOf<OwnerCommand>().also(commands::drainTo)
        }
    dropped.forEach { it.fail(failure) }
  }

  private inner class OwnerState(
      private val machine: Gameboy,
      private val eventBus: EventBusImpl,
  ) {
    var paused = true

    init {
      // The pull-driven Agent starts at a logical debug pause. Establish the same wall-clock RTC
      // bookkeeping as a desktop debug pause before publishing the session to callers.
      machine.setCartridgeClockPaused(true)
    }

    private var sequence = 0L

    private var masterTick = 0L

    private var frame = 0L

    private var framePosition = 0

    fun tick(): Boolean {
      val frameReady = machine.tick()
      masterTick++
      framePosition++
      if (frameReady) {
        frame++
        framePosition = 0
      }
      return frameReady
    }

    fun snapshot(): DebugSnapshot =
        machine.captureDebugSnapshot(
            SESSION_GENERATION, ++sequence, masterTick, frame, framePosition, paused)

    fun retirementSequence(): Long = machine.debugRetirementSequence

    fun readMemory(request: DebugMemoryRequest): DebugMemoryBlock =
        machine.readDebugMemory(request)

    fun setButton(button: DebugButton, pressed: Boolean) {
      val coreButton = Button.valueOf(button.name)
      eventBus.post(if (pressed) ButtonPressEvent(coreButton) else ButtonReleaseEvent(coreButton))
    }

    fun updatePaused(value: Boolean) {
      machine.setCartridgeClockPaused(value)
      paused = value
    }

    fun close() {
      runCatching { machine.disableDebugRetirementTracking() }
      runCatching { eventBus.close() }
      runCatching { machine.closeSilently() }
    }
  }

  private interface OwnerCommand {
    fun execute()

    fun cancel()

    fun fail(failure: Throwable)
  }

  private inner class SyncCommand<T>(private val action: (OwnerState) -> T) : OwnerCommand {
    val result = CompletableFuture<T>()

    override fun execute() {
      try {
        result.complete(action(requireOwnerState()))
      } catch (failure: Throwable) {
        result.completeExceptionally(failure)
      }
    }

    override fun cancel() {
      result.completeExceptionally(AgentClosedException())
    }

    override fun fail(failure: Throwable) {
      result.completeExceptionally(failure)
    }
  }

  private inner class DebugCommand<T>(private val action: (OwnerState) -> DebugResult<T>) :
      OwnerCommand {
    val result = CompletableFuture<DebugResult<T>>()

    private val completionScheduled = AtomicBoolean()

    override fun execute() {
      val outcome =
          try {
            action(requireOwnerState())
          } catch (_: AgentClosedException) {
            DebugResult.failure(DebugErrorCode.PORT_CLOSED, "Agent debug port is closed")
          } catch (failure: Throwable) {
            DebugResult.failure(
                DebugErrorCode.INTERNAL_ERROR,
                failure.message?.takeIf(String::isNotBlank) ?: "Agent debug command failed",
            )
          }
      complete(outcome)
    }

    override fun cancel() {
      complete(DebugResult.failure(DebugErrorCode.PORT_CLOSED, "Agent debug port is closed"))
    }

    override fun fail(failure: Throwable) {
      complete(
          DebugResult.failure(
              DebugErrorCode.INTERNAL_ERROR,
              failure.message?.takeIf(String::isNotBlank) ?: "Agent owner failed",
          ))
    }

    fun complete(outcome: DebugResult<T>) {
      if (!completionScheduled.compareAndSet(false, true)) return
      DebugResultDispatcher.dispatch {
        try {
          result.complete(outcome)
        } finally {
          // Synchronous client continuations have returned when complete() returns. Keep the
          // request admitted until then so blocked callbacks cannot create an unbounded backlog.
          debugRequestSlots.release()
          DebugResultDispatcher.release()
        }
      }
    }
  }

  private inner class HeadlessDebugPort : DebugPort {
    private val capabilities =
        DebugCapabilities(
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            MAX_MEMORY_READ_LENGTH,
        )

    override fun sessionGeneration(): Long = SESSION_GENERATION

    override fun capabilities(): DebugCapabilities = capabilities

    override fun pause(): CompletionStage<DebugResult<DebugSnapshot>> =
        enqueueDebug { state ->
          if (state.paused) {
            DebugResult.failure(DebugErrorCode.ALREADY_PAUSED, "Agent session is already paused")
          } else {
            state.updatePaused(true)
            DebugResult.success(state.snapshot())
          }
        }

    override fun resume(): CompletionStage<DebugResult<DebugSnapshot>> =
        enqueueDebug { state ->
          if (!state.paused) {
            DebugResult.failure(DebugErrorCode.ALREADY_RUNNING, "Agent session is already running")
          } else {
            state.updatePaused(false)
            DebugResult.success(state.snapshot())
          }
        }

    override fun snapshot(): CompletionStage<DebugResult<DebugSnapshot>> =
        enqueueDebug { state -> DebugResult.success(state.snapshot()) }

    override fun step(kind: DebugStepKind?): CompletionStage<DebugResult<DebugStepResult>> =
        if (kind == null) {
          rejectedDebugStage(
              DebugResult.failure(DebugErrorCode.INVALID_ARGUMENT, "Step kind is required"))
        } else {
          enqueueDebug { state ->
            if (!state.paused) {
              DebugResult.failure(
                  DebugErrorCode.NOT_PAUSED,
                  "Pause the Agent session before stepping",
              )
            } else {
              when (kind) {
                DebugStepKind.INSTRUCTION -> stepInstruction(state)
                DebugStepKind.MACHINE_CYCLE -> stepMachineCycle(state)
                DebugStepKind.FRAME -> stepFrame(state)
              }
            }
          }
        }

    override fun readMemory(
        request: DebugMemoryRequest?
    ): CompletionStage<DebugResult<DebugMemoryBlock>> =
        if (request == null) {
          rejectedDebugStage(
              DebugResult.failure(DebugErrorCode.INVALID_ARGUMENT, "Memory request is required"))
        } else {
          enqueueDebug { state ->
            if (request.length() > MAX_MEMORY_READ_LENGTH) {
              DebugResult.failure(
                  DebugErrorCode.INVALID_ARGUMENT,
                  "Memory read exceeds $MAX_MEMORY_READ_LENGTH bytes",
              )
            } else {
              try {
                DebugResult.success(state.readMemory(request))
              } catch (failure: UnsupportedOperationException) {
                DebugResult.failure(
                    DebugErrorCode.UNSUPPORTED_ADDRESS_SPACE,
                    failure.message ?: "Unsupported debug address space",
                )
              } catch (failure: IllegalArgumentException) {
                DebugResult.failure(
                    DebugErrorCode.SIDE_EFFECTFUL_ADDRESS,
                    failure.message ?: "Invalid debug memory request",
                )
              }
            }
          }
        }

    override fun setButton(
        button: DebugButton?,
        pressed: Boolean,
    ): CompletionStage<DebugResult<Void>> =
        if (button == null) {
          rejectedDebugStage(
              DebugResult.failure(DebugErrorCode.INVALID_ARGUMENT, "Button is required"))
        } else {
          enqueueDebug { state ->
            state.setButton(button, pressed)
            DebugResult.success()
          }
        }

    override fun isClosed(): Boolean = closed.get()

    override fun close() {
      this@HeadlessAgentSession.close()
    }

    private fun stepInstruction(state: OwnerState): DebugResult<DebugStepResult> {
      val before = state.snapshot()
      when (before.execution().cpuState()) {
        DebugCpuState.LOCKED ->
            return DebugResult.failure(DebugErrorCode.CPU_LOCKED, "CPU is locked by an illegal opcode")
        DebugCpuState.HALTED,
        DebugCpuState.STOPPED,
        DebugCpuState.SPEED_SWITCH ->
            return DebugResult.failure(DebugErrorCode.CPU_IDLE, "CPU is not retiring instructions")
        else -> Unit
      }

      val initialRetirement = state.retirementSequence()
      var ticks = 0L
      while (state.retirementSequence() == initialRetirement && ticks < INSTRUCTION_STEP_LIMIT) {
        ensureOpenOnOwner()
        state.tick()
        ticks++
      }
      if (state.retirementSequence() == initialRetirement) {
        return DebugResult.failure(DebugErrorCode.STEP_LIMIT, "Instruction step exceeded its tick limit")
      }
      val snapshot = state.snapshot()
      val reason =
          when (snapshot.execution().cpuState()) {
            DebugCpuState.LOCKED -> DebugStepStopReason.CPU_LOCKED
            DebugCpuState.HALTED,
            DebugCpuState.STOPPED,
            DebugCpuState.SPEED_SWITCH -> DebugStepStopReason.CPU_IDLE
            else -> DebugStepStopReason.INSTRUCTION_RETIRED
          }
      return DebugResult.success(
          DebugStepResult(
              DebugStepKind.INSTRUCTION,
              reason,
              ticks,
              state.retirementSequence() - initialRetirement,
              snapshot,
          ))
    }

    private fun stepMachineCycle(state: OwnerState): DebugResult<DebugStepResult> {
      val initialRetirement = state.retirementSequence()
      var ticks = 0L
      var snapshot: DebugSnapshot
      do {
        ensureOpenOnOwner()
        state.tick()
        ticks++
        snapshot = state.snapshot()
      } while (snapshot.execution().machineCycle() != 0 && ticks < MACHINE_CYCLE_STEP_LIMIT)
      if (snapshot.execution().machineCycle() != 0) {
        return DebugResult.failure(DebugErrorCode.STEP_LIMIT, "Machine-cycle step exceeded its tick limit")
      }
      return DebugResult.success(
          DebugStepResult(
              DebugStepKind.MACHINE_CYCLE,
              DebugStepStopReason.MACHINE_CYCLE_COMPLETED,
              ticks,
              state.retirementSequence() - initialRetirement,
              snapshot,
          ))
    }

    private fun stepFrame(state: OwnerState): DebugResult<DebugStepResult> {
      val initialRetirement = state.retirementSequence()
      var ticks = 0L
      var completed = false
      while (ticks < defaultFrameWaitTicks) {
        ensureOpenOnOwner()
        completed = state.tick()
        ticks++
        if (completed) break
      }
      if (!completed) {
        return DebugResult.failure(DebugErrorCode.STEP_LIMIT, "Frame step exceeded its tick limit")
      }
      return DebugResult.success(
          DebugStepResult(
              DebugStepKind.FRAME,
              DebugStepStopReason.FRAME_BOUNDARY,
              ticks,
              state.retirementSequence() - initialRetirement,
              state.snapshot(),
          ))
    }
  }

  private data class Bootstrap(val defaultFrameWaitTicks: Int)

  private class AgentClosedException : IllegalStateException("Agent is closed")

  private companion object {
    const val OWNER_THREAD_PREFIX = "coffee-gb-agent-emulation-"
    const val COMMAND_CAPACITY = 64
    const val FRAME_CAPACITY = 10
    const val AUDIO_CAPACITY = 100
    const val MAX_MEMORY_READ_LENGTH = 4096
    const val CLOSE_TIMEOUT_MILLIS = 5_000L
    const val SESSION_GENERATION = 1L
    const val INSTRUCTION_STEP_LIMIT = 1_000_000L
    const val MACHINE_CYCLE_STEP_LIMIT = 8L
    val NEXT_OWNER_ID = AtomicLong()
  }
}
