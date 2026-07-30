package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.agent.AgentDisassembler
import eu.rekawek.coffeegb.controller.agent.HeadlessAgentSession
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugButton
import eu.rekawek.coffeegb.core.debug.DebugCpuState
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.debug.DebugRegisters
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

/**
 * Synchronous headless convenience adapter over a bounded, asynchronous [DebugPort].
 *
 * Each instance owns one named emulation thread. Call [close] when finished; Kotlin callers can use
 * `Agent(file).use { ... }`. The temporary [BufferedImage] adapter remains outside the debug API.
 */
class Agent(romFile: File) : AutoCloseable {

  private val session = HeadlessAgentSession(romFile)

  val debugPort: DebugPort
    get() = session.debugPort

  fun tick() {
    session.runTicks(1)
  }

  fun step() {
    requireSuccess(debugPort.step(DebugStepKind.INSTRUCTION))
  }

  fun runUntilFrame(maxTicks: Int = session.defaultFrameWaitTicks) {
    session.runUntilFrame(maxTicks)
  }

  fun runTicks(ticks: Int) {
    session.runTicks(ticks)
  }

  fun snapshot(): DebugSnapshot = requireSuccess(debugPort.snapshot())

  fun isLcdEnabled(): Boolean = snapshot().ppu().lcdEnabled()

  fun getLcdc(): Int = snapshot().ppu().lcdc()

  fun getLY(): Int = snapshot().ppu().line()

  /** Returns a caller-owned frame without introducing AWT into [DebugPort]. */
  fun getFrame(): BufferedImage? {
    val pixels = getFramePixels() ?: return null
    return BufferedImage(DISPLAY_WIDTH, DISPLAY_HEIGHT, BufferedImage.TYPE_INT_RGB).apply {
      setRGB(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT, pixels, 0, DISPLAY_WIDTH)
    }
  }

  /** Returns a caller-owned RGB frame buffer, or null when no frame is queued. */
  fun getFramePixels(): IntArray? = session.pollFrame()

  /** Returns caller-owned interleaved stereo sample buffers accumulated since the last call. */
  fun getAudio(): List<IntArray> = session.drainAudio()

  fun getRegisters(): DebugRegisters = snapshot().registers()

  fun getSP(): Int = getRegisters().sp()

  fun readMemory(request: DebugMemoryRequest): DebugMemoryBlock =
      requireSuccess(debugPort.readMemory(request))

  /**
   * Legacy address-based helper. Cartridge addresses use the parser-corrected loaded ROM image
   * rather than the mapper's live CPU window; other addresses use the side-effect-free SYSTEM_BUS
   * view.
   */
  fun getByte(address: Int): Int =
      readMemory(defaultMemoryRequest(address, 1)).unsignedByteAt(0)

  /**
   * Legacy address-based helper. A request may not cross from the physical ROM view into the
   * system-bus view; callers that need a specific view should use [readMemory].
   */
  fun getMemory(address: Int, length: Int): IntArray {
    val block = readMemory(defaultMemoryRequest(address, length))
    return IntArray(block.length()) { block.unsignedByteAt(it) }
  }

  fun pressButton(button: Button) {
    requireSuccess(debugPort.setButton(DebugButton.valueOf(button.name), true))
  }

  fun releaseButton(button: Button) {
    requireSuccess(debugPort.setButton(DebugButton.valueOf(button.name), false))
  }

  fun disassemble(address: Int): String {
    require(address in 0..0xffff) { "Address must be a 16-bit value: $address" }
    val available = minOf(MAX_INSTRUCTION_BYTES, defaultRegionEndExclusive(address) - address)
    val memory = readMemory(defaultMemoryRequest(address, available))
    return AgentDisassembler.disassemble(address, memory)
  }

  fun isCpuHalted(): Boolean = snapshot().execution().cpuState() == DebugCpuState.HALTED

  fun isCpuStopped(): Boolean = snapshot().execution().cpuState() == DebugCpuState.STOPPED

  fun getCpuState(): String = snapshot().execution().cpuState().name

  fun getCpuClockCycle(): Int = snapshot().execution().machineCycle()

  fun isImeEnabled(): Boolean = snapshot().interrupts().ime()

  fun getIF(): Int = snapshot().interrupts().requestFlags()

  fun getIE(): Int = snapshot().interrupts().enableFlags()

  fun getRomBank(): Int = snapshot().mapper().romBank()

  override fun close() {
    session.close()
  }

  private fun defaultAddressSpace(address: Int): DebugAddressSpace =
      if (address < 0x8000) DebugAddressSpace.ROM else DebugAddressSpace.SYSTEM_BUS

  private fun defaultMemoryRequest(address: Int, length: Int): DebugMemoryRequest {
    val request = DebugMemoryRequest(defaultAddressSpace(address), address, length)
    require(request.endExclusive() <= defaultRegionEndExclusive(address)) {
      "Legacy memory request crosses a debug address-space boundary; use readMemory() with an " +
          "explicit named address space"
    }
    return request
  }

  private fun defaultRegionEndExclusive(address: Int): Int =
      when (address) {
        in 0x0000..0x7fff -> 0x8000
        in 0xc000..0xfdff -> 0xfe00
        in 0xff80..0xfffe -> 0xffff
        else -> 0x10000
      }

  private fun <T> requireSuccess(stage: CompletionStage<DebugResult<T>>): T {
    val result =
        try {
          stage.toCompletableFuture().get()
        } catch (failure: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IllegalStateException("Interrupted while awaiting Agent debug command", failure)
        } catch (failure: ExecutionException) {
          throw IllegalStateException("Agent debug command failed", failure.cause)
        }
    if (result.isFailure) {
      val error = result.error()
      throw IllegalStateException("${error.code()}: ${error.message()}")
    }
    return result.value()
  }

  private companion object {
    const val DISPLAY_WIDTH = 160
    const val DISPLAY_HEIGHT = 144
    const val MAX_INSTRUCTION_BYTES = 3
  }
}
