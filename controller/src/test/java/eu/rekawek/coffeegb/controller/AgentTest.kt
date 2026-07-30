package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugRegisters
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun instructionStepRetiresExactlyOneOrdinaryInstruction() {
    Agent(testRom(0x3e, 0x12, 0x3c, 0x18, 0xfe)).use { agent ->
      val initial = agent.getRegisters()
      assertEquals(0x100, initial.pc())

      agent.step()
      val afterLoad = agent.getRegisters()
      assertEquals(0x12, afterLoad.a())
      assertEquals(0x102, afterLoad.pc())
      assertEquals(1, agent.snapshot().execution().retiredInstructions())

      agent.step()
      val afterIncrement = agent.getRegisters()
      assertEquals(0x13, afterIncrement.a())
      assertEquals(0x103, afterIncrement.pc())
      assertEquals(2, agent.snapshot().execution().retiredInstructions())
    }
  }

  @Test
  fun registerInspectionReturnsDetachedImmutableValues() {
    Agent(testRom(0x3e, 0x7b, 0x00)).use { agent ->
      val before = agent.getRegisters()
      assertEquals(DebugRegisters::class.java, before.javaClass)

      agent.step()
      val after = agent.getRegisters()
      assertNotSame(before, after)
      assertEquals(0x100, before.pc())
      assertEquals(0x102, after.pc())
      assertEquals(0x7b, after.a())
      assertEquals(0x100, before.pc(), "Earlier register snapshots must not change")
    }
  }

  @Test
  fun legacyReadAndDisassemblyHelpersUseTheSideEffectFreeRomView() {
    Agent(testRom(0x3e, 0x7b, 0x00)).use { agent ->
      assertEquals(0x3e, agent.getByte(0x100))
      assertEquals(listOf(0x3e, 0x7b, 0x00), agent.getMemory(0x100, 3).toList())
      val disassembly = agent.disassemble(0x100)
      assertTrue(disassembly.startsWith("0100: 3E 7B"), disassembly)
      assertTrue("7B" in disassembly, disassembly)
      assertTrue("best-effort: parser-corrected ROM image" in disassembly, disassembly)
    }
  }

  @Test
  fun legacyRomHelpersNeverSilentlyCrossIntoTheSystemBus() {
    Agent(testRom(0x00, 0x18, 0xfd)).use { agent ->
      assertFailsWith<IllegalArgumentException> { agent.getMemory(0x7fff, 2) }

      val boundaryInstruction = agent.disassemble(0x7fff)
      assertTrue(boundaryInstruction.startsWith("7FFF: 00"), boundaryInstruction)
      assertTrue("not the mapped CPU window" in boundaryInstruction, boundaryInstruction)
    }
  }

  @Test
  fun oneByteDisassemblyAtTheEndOfAReadableRamRegionDoesNotReadPastIt() {
    Agent(testRom(0x3e, 0x00, 0xea, 0xfe, 0xff, 0x18, 0xfe)).use { agent ->
      agent.step()
      agent.step()

      val disassembly = agent.disassemble(0xfffe)
      assertTrue(disassembly.startsWith("FFFE: 00"), disassembly)
      assertTrue("best-effort: SYSTEM_BUS" in disassembly, disassembly)
    }
  }

  @Test
  fun bankedCodeIsExplicitlyReportedAsAPhysicalImageView() {
    Agent(bankedTestRom()).use { agent ->
      repeat(3) { agent.step() }
      assertEquals(0x4000, agent.getRegisters().pc())

      // The live MBC1 window now selects bank 2, while this legacy helper deliberately reads
      // physical image offset 4000 (bank 1). The label must prevent clients treating it as mapped.
      assertEquals(0x00, agent.getByte(0x4000))
      assertEquals(
          0x3c,
          agent
              .readMemory(DebugMemoryRequest(DebugAddressSpace.ROM, 0x8000, 1))
              .unsignedByteAt(0),
      )
      val disassembly = agent.disassemble(0x4000)
      assertTrue(disassembly.startsWith("4000: 00"), disassembly)
      assertTrue("not the mapped CPU window" in disassembly, disassembly)
    }
  }

  @Test
  fun headlessMemoryRangeFailuresUseTheDocumentedTypedError() {
    Agent(testRom(0x00, 0x18, 0xfd)).use { agent ->
      val result =
          agent.debugPort
              .readMemory(DebugMemoryRequest(DebugAddressSpace.WORK_RAM, 0xbfff, 1))
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)

      assertTrue(result.isFailure)
      assertEquals(DebugErrorCode.SIDE_EFFECTFUL_ADDRESS, result.error().code())
    }
  }

  @Test
  fun headlessPortReturnsTypedFailuresForNullJavaArguments() {
    Agent(testRom(0x00, 0x18, 0xfd)).use { agent ->
      assertDebugFailure(agent.debugPort.step(null), DebugErrorCode.INVALID_ARGUMENT)
      assertDebugFailure(agent.debugPort.readMemory(null), DebugErrorCode.INVALID_ARGUMENT)
      assertDebugFailure(agent.debugPort.setButton(null, true), DebugErrorCode.INVALID_ARGUMENT)
    }
  }

  @Test
  fun headlessFrameStepReportsItsActualRetirementDelta() {
    Agent(testRom(0x00, 0x18, 0xfd)).use { agent ->
      val before = agent.snapshot().execution().retiredInstructions()
      val result =
          agent.debugPort
              .step(DebugStepKind.FRAME)
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
      assertTrue(result.isSuccess, result.toString())
      val step = result.value()
      assertTrue(step.instructionsRetired() > 0)
      assertEquals(
          step.snapshot().execution().retiredInstructions() - before,
          step.instructionsRetired(),
      )
    }
  }

  @Test
  fun initialHeadlessPauseAccruesMbc3WallClockTime() {
    Agent(rtcTestRom()).use { agent ->
      repeat(4) { agent.step() }
      Thread.sleep(1_200)

      // Admission is FIFO: pause is already waiting when resume changes ownership, so no guest
      // tick can be mistaken for the wall-clock catch-up under test.
      val resumed = agent.debugPort.resume()
      val paused = agent.debugPort.pause()
      assertTrue(resumed.toCompletableFuture().get(5, TimeUnit.SECONDS).isSuccess)
      assertTrue(paused.toCompletableFuture().get(5, TimeUnit.SECONDS).isSuccess)

      repeat(2) { agent.step() }
      val seconds =
          agent
              .readMemory(DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0xff80, 1))
              .unsignedByteAt(0)
      assertTrue(seconds in 1..59, "Expected paused RTC catch-up, got $seconds seconds")
    }
  }

  @Test
  fun concurrentCloseCannotLeaveAnOwnerlessDebugFuture() {
    val agent = Agent(testRom(0x00, 0x18, 0xfd))
    val stages =
        Collections.synchronizedList(
            mutableListOf<CompletionStage<DebugResult<DebugSnapshot>>>())
    val executor = Executors.newFixedThreadPool(4)
    val start = CountDownLatch(1)
    try {
      repeat(4) {
        executor.execute {
          start.await()
          repeat(16) { stages += agent.debugPort.snapshot() }
        }
      }
      val closer = Thread({ start.await(); agent.close() }, "agent-close-race")
      closer.start()
      start.countDown()
      executor.shutdown()
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
      closer.join(5_000)
      assertFalse(closer.isAlive)

      stages.forEach { stage ->
        val result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS)
        assertTrue(
            result.isSuccess || result.error().code() == DebugErrorCode.PORT_CLOSED,
            result.toString(),
        )
      }
    } finally {
      executor.shutdownNow()
      agent.close()
    }
  }

  @Test
  fun ownerAndCompletionThreadsStayOffTheCallerAndCloseCleansUpOwner() {
    val existingIds = agentOwnerThreads().map(Thread::getId).toSet()
    val agent = Agent(testRom(0x00, 0x18, 0xfd))
    val owner =
        awaitValue("Agent owner did not start") {
          agentOwnerThreads().singleOrNull { it.id !in existingIds }
        }
    assertFalse(owner === Thread.currentThread())

    val callbackThread =
        agent.debugPort
            .snapshot()
            .thenApply { Thread.currentThread() }
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS)
    assertFalse(
        callbackThread.name.startsWith(OWNER_THREAD_PREFIX),
        "Debug completion ran on ${callbackThread.name}",
    )

    agent.close()
    agent.close()
    awaitValue("Agent owner survived close") { (!owner.isAlive).takeIf { it } }
    assertTrue(agent.debugPort.isClosed)
  }

  @Test
  fun agentSourceAndPublicSurfaceCannotExposeLiveCoreOrReflectionBackdoors() {
    val source =
        repositoryRoot()
            .resolve("controller/src/main/java/eu/rekawek/coffeegb/controller/Agent.kt")
            .readText()
    listOf(
            "getDeclaredField",
            "setAccessible",
            ".isAccessible",
            "import eu.rekawek.coffeegb.core.Gameboy",
            "import eu.rekawek.coffeegb.core.AddressSpace",
            "import eu.rekawek.coffeegb.core.cpu.Cpu",
            "import eu.rekawek.coffeegb.core.cpu.Registers",
        )
        .forEach { forbidden ->
          assertFalse(forbidden in source, "Agent source contains forbidden access: $forbidden")
        }

    assertEquals(DebugRegisters::class.java, Agent::class.java.getMethod("getRegisters").returnType)
    assertFalse(Agent::class.java.methods.any { it.name == "getRegistersObj" })
    assertFalse(Agent::class.java.methods.any { it.name == "writeMemory" })
    val forbiddenTypes =
        setOf(
            "eu.rekawek.coffeegb.core.Gameboy",
            "eu.rekawek.coffeegb.core.AddressSpace",
            "eu.rekawek.coffeegb.core.cpu.Cpu",
            "eu.rekawek.coffeegb.core.cpu.Registers",
        )
    Agent::class.java.declaredFields.forEach { field ->
      assertFalse(field.type.name in forbiddenTypes, "Agent field exposes ${field.type.name}")
    }
    Agent::class.java.methods.forEach { method ->
      assertFalse(method.returnType.name in forbiddenTypes, "${method.name} returns a live core type")
      method.parameterTypes.forEach { parameter ->
        assertFalse(parameter.name in forbiddenTypes, "${method.name} accepts a live core type")
      }
    }
  }

  private fun testRom(vararg program: Int) =
      temporaryFolder.newFile("agent-${System.nanoTime()}.gb").apply {
        val bytes = ByteArray(0x8000)
        "AGENT-TEST".forEachIndexed { index, character ->
          bytes[0x134 + index] = character.code.toByte()
        }
        program.forEachIndexed { index, value -> bytes[0x100 + index] = value.toByte() }
        writeBytes(bytes)
      }

  private fun <T> assertDebugFailure(
      stage: CompletionStage<DebugResult<T>>,
      expected: DebugErrorCode,
  ) {
    val result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS)
    assertTrue(result.isFailure, result.toString())
    assertEquals(expected, result.error().code())
  }

  private fun bankedTestRom() =
      temporaryFolder.newFile("agent-banked-${System.nanoTime()}.gb").apply {
        val bytes = ByteArray(0x10000)
        "AGENT-MBC1".forEachIndexed { index, character ->
          bytes[0x134 + index] = character.code.toByte()
        }
        bytes[0x147] = 0x01
        bytes[0x148] = 0x01
        intArrayOf(0x3e, 0x02, 0xea, 0x00, 0x20, 0xc3, 0x00, 0x40)
            .forEachIndexed { index, value -> bytes[0x100 + index] = value.toByte() }
        bytes[0x4000] = 0x00
        bytes[0x8000] = 0x3c
        writeBytes(bytes)
      }

  private fun rtcTestRom() =
      temporaryFolder.newFile("agent-rtc-${System.nanoTime()}.gb").apply {
        val bytes = ByteArray(0x8000)
        "AGENT-RTC".forEachIndexed { index, character ->
          bytes[0x134 + index] = character.code.toByte()
        }
        bytes[0x147] = 0x0f // MBC3 timer + battery
        intArrayOf(
                0x3e,
                0x0a, // LD A,$0A
                0xea,
                0x00,
                0x00, // LD ($0000),A: enable RTC
                0x3e,
                0x08, // LD A,$08
                0xea,
                0x00,
                0x40, // LD ($4000),A: select RTC seconds
                0xfa,
                0x00,
                0xa0, // LD A,($A000)
                0xe0,
                0x80, // LDH ($80),A
                0x18,
                0xf9, // JR back to the RTC read
            )
            .forEachIndexed { index, value -> bytes[0x100 + index] = value.toByte() }
        writeBytes(bytes)
      }

  private fun agentOwnerThreads(): List<Thread> =
      Thread.getAllStackTraces().keys.filter { it.name.startsWith(OWNER_THREAD_PREFIX) }

  private fun <T : Any> awaitValue(message: String, block: () -> T?): T {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
      block()?.let { return it }
      Thread.sleep(10)
    }
    fail(message)
  }

  private fun repositoryRoot(): Path {
    var current = Path.of("").toAbsolutePath()
    while (!Files.exists(current.resolve("pom.xml")) ||
        !Files.exists(current.resolve("controller"))) {
      current = current.parent ?: error("repository root not found")
    }
    return current
  }

  private companion object {
    const val OWNER_THREAD_PREFIX = "coffee-gb-agent-emulation-"
  }
}
