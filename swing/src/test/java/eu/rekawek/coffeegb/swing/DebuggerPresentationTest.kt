package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugAnchoredMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugApuState
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugCpuState
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugExecutionState
import eu.rekawek.coffeegb.core.debug.DebugFeatureState
import eu.rekawek.coffeegb.core.debug.DebugInterruptState
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionResult
import eu.rekawek.coffeegb.core.debug.DebugMapperState
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugPpuMode
import eu.rekawek.coffeegb.core.debug.DebugPpuState
import eu.rekawek.coffeegb.core.debug.DebugRegisters
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.debug.DebugTimerState
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugMemoryCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryCapabilities
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPoint
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPosition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult
import java.util.EnumSet
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerPresentationTest {

  @Test
  fun `address and inclusive range parsers accept conventional hexadecimal forms`() {
    assertEquals(0xc000, DebuggerPresentation.parseAddress("  ${'$'}c000 ").value)
    assertEquals(0xffff, DebuggerPresentation.parseAddress("0xFFFF").value)
    assertEquals(0x1234, DebuggerPresentation.parseAddress("1234").value)

    val range = DebuggerPresentation.parseAddressRange("${'$'}C000 .. 0xC0ff").value
    assertNotNull(range)
    assertEquals(0xc000, range.startAddress)
    assertEquals(0xc0ff, range.endAddress)
    assertEquals(0x100, range.length)
    assertFalse(range.isExact)
    assertEquals(0x100, range.memoryRequest(DebugAddressSpace.SYSTEM_BUS).length())

    val exact = DebuggerPresentation.parseAddressRange("42").value
    assertNotNull(exact)
    assertTrue(exact.isExact)
    assertEquals(0x42, exact.startAddress)
  }

  @Test
  fun `address and byte parsers return stable display errors instead of throwing`() {
    val malformed = DebuggerPresentation.parseAddress("0x10000")
    val descending = DebuggerPresentation.parseAddressRange("${'$'}C100-${'$'}C000")
    val badByte = DebuggerPresentation.parseByte("100")

    assertFalse(malformed.isValid)
    assertContains(malformed.error!!, "16-bit")
    assertFalse(descending.isValid)
    assertContains(descending.error!!, "must not precede")
    assertFalse(badByte.isValid)
    assertContains(badByte.error!!, "8-bit")
  }

  @Test
  fun `condition parsers create typed immutable breakpoint conditions`() {
    val pc = DebuggerPresentation.parseProgramCounterCondition("100-10f").value
    assertEquals(DebugPcCondition.range(0x100, 0x10f), pc)

    val memory =
        DebuggerPresentation.parseMemoryCondition(
                "${'$'}C000:${'$'}C0FF",
                DebugMemoryAccess.WRITE,
                valueText = "a5",
                maskText = "f0",
            )
            .value
    assertNotNull(memory)
    assertEquals(0xc000, memory.startAddress())
    assertEquals(0xc0ff, memory.endAddress())
    assertEquals(0xa5, memory.value())
    assertEquals(0xf0, memory.valueMask())

    val maskWithoutValue =
        DebuggerPresentation.parseMemoryCondition(
            "c000",
            DebugMemoryAccess.READ,
            maskText = "ff",
        )
    val zeroMask =
        DebuggerPresentation.parseMemoryCondition(
            "c000",
            DebugMemoryAccess.READ,
            valueText = "1",
            maskText = "0",
        )
    assertContains(maskWithoutValue.error!!, "value is required")
    assertContains(zeroMask.error!!, "at least one set bit")
  }

  @Test
  fun `hex flags and register presentation are fixed width and accessible`() {
    assertEquals("${'$'}00", DebuggerPresentation.formatByte(0))
    assertEquals("${'$'}ABCD", DebuggerPresentation.formatWord(0xabcd))
    assertEquals("Z=1 N=0 H=1 C=0", DebuggerPresentation.formatFlags(0xa0))
    assertEquals("Z-H-", DebuggerPresentation.formatCompactFlags(0xa0))

    val view = DebuggerPresentation.snapshot(snapshot(sp = 0xfffe))
    assertEquals(DebuggerSnapshotIdentity(7, 11, 250), view.identity)
    assertEquals("Session 7, snapshot 11, tick 250", view.identity.label)
    assertEquals("${'$'}12A0", view.registers.af)
    assertEquals("${'$'}FFFE", view.registers.sp)
    assertEquals("${'$'}CB ${'$'}11", view.opcode)
    assertContains(view.timingText, "Frame 2")
  }

  @Test
  fun `breakpoint rows preserve identity state support and readable conditions`() {
    val breakpoints =
        listOf(
            DebugBreakpoint(
                DebugBreakpointId(3),
                true,
                DebugMemoryCondition(DebugMemoryAccess.WRITE, 0xc000, 0xc0ff, 0xa0, 0xf0),
            ),
            DebugBreakpoint(DebugBreakpointId(4), false, DebugPcCondition.at(0x150)),
            DebugBreakpoint(
                DebugBreakpointId(5),
                true,
                DebugPpuCondition.at(2, 42, DebugPpuMode.HBLANK),
            ),
        )
    val rows = DebuggerPresentation.breakpointRows(breakpoints, pcOnlyCapabilities())

    assertEquals(3L, rows[0].id)
    assertFalse(rows[0].supported)
    assertEquals("Write ${'$'}C000-${'$'}C0FF, value ${'$'}A0, mask ${'$'}F0", rows[0].condition)
    assertContains(rows[0].accessibilityText, "unsupported in this session")
    assertTrue(rows[1].supported)
    assertFalse(rows[1].enabled)
    assertEquals("${'$'}0150", rows[1].condition)
    assertEquals("frame 2, LY 42, mode Hblank", rows[2].condition)
  }

  @Test
  fun `memory rows are detached display values and explicitly report coherence`() {
    val expected = DebuggerSnapshotIdentity(7, 11, 250)
    val block =
        DebugMemoryBlock(
            DebugAddressSpace.SYSTEM_BUS,
            0xc000,
            byteArrayOf(0x41, 0x7f, 0x20, 0xff.toByte()),
        )
    val coherent =
        DebuggerPresentation.memory(expected, DebuggerMemoryCapture(expected, block), bytesPerRow = 2)
    assertEquals(DebuggerMemoryCoherence.COHERENT, coherent.coherence)
    assertNull(coherent.coherenceExplanation)
    assertEquals("41 7F", coherent.rows[0].hexText)
    assertEquals("A.", coherent.rows[0].asciiText)
    assertEquals(listOf(0x20, 0xff), coherent.rows[1].bytes)
    assertFailsWith<UnsupportedOperationException> {
      (coherent.rows as MutableList).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (coherent.rows[0].bytes as MutableList).clear()
    }

    val stale =
        DebuggerPresentation.memory(
            expected,
            DebuggerMemoryCapture(expected.copy(sequence = 10), block),
        )
    assertEquals(DebuggerMemoryCoherence.STALE_SNAPSHOT, stale.coherence)
    assertContains(stale.coherenceExplanation!!, "different snapshot")

    val replaced =
        DebuggerPresentation.memory(
            expected,
            DebuggerMemoryCapture(expected.copy(sessionGeneration = 6), block),
        )
    assertEquals(DebuggerMemoryCoherence.DIFFERENT_SESSION, replaced.coherence)
  }

  @Test
  fun `stack view explains unavailable reads and clips safely at address-space end`() {
    val snapshot = snapshot(sp = 0xfffe)
    val identity = DebuggerSnapshotIdentity.from(snapshot)
    val noMemory =
        DebuggerPresentation.stack(snapshot, capabilities(memoryRead = false), null, 4)
    assertFalse(noMemory.available)
    assertContains(noMemory.explanation!!, "does not support memory reads")

    val stale =
        DebuggerPresentation.stack(
            snapshot,
            capabilities(),
            DebuggerMemoryCapture(
                identity.copy(sequence = identity.sequence - 1),
                DebugMemoryBlock(
                    DebugAddressSpace.SYSTEM_BUS,
                    0xfffe,
                    byteArrayOf(1, 2),
                ),
            ),
            4,
        )
    assertFalse(stale.available)
    assertContains(stale.explanation!!, "different snapshot")

    val clipped =
        DebuggerPresentation.stack(
            snapshot,
            capabilities(),
            DebuggerMemoryCapture(
                identity,
                DebugMemoryBlock(
                    DebugAddressSpace.SYSTEM_BUS,
                    0xfffe,
                    byteArrayOf(0x34, 0x12),
                ),
            ),
            4,
        )
    assertTrue(clipped.available)
    assertTrue(clipped.clipped)
    assertContains(clipped.explanation!!, "16-bit address space")
    assertEquals(listOf("${'$'}FFFE", "${'$'}FFFF"), clipped.entries.map { it.addressText })
    assertEquals(listOf("${'$'}34", "${'$'}12"), clipped.entries.map { it.valueText })
  }

  @Test
  fun `stack distinguishes an incomplete response from an unsupported stack`() {
    val snapshot = snapshot(sp = 0xc000)
    val identity = DebuggerSnapshotIdentity.from(snapshot)
    val stack =
        DebuggerPresentation.stack(
            snapshot,
            capabilities(),
            DebuggerMemoryCapture(
                identity,
                DebugMemoryBlock(DebugAddressSpace.SYSTEM_BUS, 0xc000, byteArrayOf(1, 2)),
            ),
            requestedBytes = 4,
        )

    assertTrue(stack.available)
    assertTrue(stack.clipped)
    assertContains(stack.explanation!!, "returned memory block")
  }

  @Test
  fun `inspection overloads derive coherent memory and stack identity without caller tagging`() {
    val snapshot = snapshot(sp = 0xc000)
    val stackBlock =
        DebugMemoryBlock(DebugAddressSpace.SYSTEM_BUS, 0xc000, byteArrayOf(0x34, 0x12))
    val inspection =
        DebugInspectionResult(
            snapshot,
            DebugInspectionRequest(
                listOf(
                    DebugAnchoredMemoryRequest(DebugInspectionAnchor.STACK_POINTER, 0, 2)
                ),
                emptyList(),
            ),
            listOf(stackBlock),
            emptyList(),
        )

    val memory = DebuggerPresentation.memory(inspection, stackBlock)
    val stack = DebuggerPresentation.stack(inspection, capabilities(), requestedBytes = 2)

    assertEquals(DebuggerSnapshotIdentity.from(snapshot), memory.identity)
    assertEquals(DebuggerMemoryCoherence.COHERENT, memory.coherence)
    assertTrue(stack.available)
    assertFalse(stack.clipped)
    assertEquals(listOf("${'$'}34", "${'$'}12"), stack.entries.map { it.valueText })
  }

  @Test
  fun `history actions distinguish unsupported disabled exhausted and available states`() {
    val unsupported = DebuggerPresentation.history(capabilities(history = false), null)
    assertFalse(unsupported.reverseFrame.enabled)
    assertContains(unsupported.reverseFrame.explanation!!, "unsupported")

    val available = DebuggerPresentation.history(capabilities(), historyStatus())
    assertTrue(available.enabled)
    assertEquals(3, available.checkpointCount)
    assertEquals(1, available.futureCheckpointCount)
    assertEquals("User rewind", available.truncation)
    assertTrue(available.reverseFrame.enabled)
    assertTrue(available.reverseInstruction.enabled)
    assertContains(available.cursor, "position 12")

    val atOldest =
        historyStatus(
            cursor = DebugHistoryPosition(100, 1, 0),
            futureCheckpointCount = 2,
        )
    val exhausted = DebuggerPresentation.history(capabilities(), atOldest)
    assertFalse(exhausted.reverseFrame.enabled)
    assertFalse(exhausted.reverseInstruction.enabled)
    assertContains(exhausted.reverseFrame.explanation!!, "oldest retained frame")

    val insideOldestFrame =
        DebuggerPresentation.history(
            capabilities(),
            historyStatus(
                cursor = DebugHistoryPosition(150, 1, 50),
                futureCheckpointCount = 2,
            ),
        )
    assertTrue(insideOldestFrame.reverseFrame.enabled)
  }

  @Test
  fun `reverse outcomes preserve coherent snapshot identity and present failures`() {
    val status = historyStatus()
    val result =
        DebugReverseStepResult(
            DebugStepKind.INSTRUCTION,
            status.cursor,
            DebugHistoryPoint(2, 200, 2),
            snapshot(sp = 0xc000),
            status,
        )
    val success = DebuggerPresentation.reverseOutcome(DebugResult.success(result))
    assertTrue(success.completed)
    val step = assertNotNull(success.step)
    assertEquals(DebuggerSnapshotIdentity(7, 11, 250), step.identity)
    assertContains(step.replayAnchor, "Checkpoint 2")
    assertEquals(1, step.futureCheckpointCount)
    assertNull(success.errorCode)

    val failure =
        DebuggerPresentation.reverseOutcome(
            DebugResult.failure(DebugErrorCode.HISTORY_EXHAUSTED, "No earlier instruction retained")
        )
    assertFalse(failure.completed)
    assertNull(failure.step)
    assertEquals("HISTORY_EXHAUSTED", failure.errorCode)
    assertEquals("No earlier instruction retained", failure.message)
  }

  @Test
  fun `capability view exposes negotiated limits without inventing support`() {
    val view = DebuggerPresentation.capabilities(pcOnlyCapabilities())
    assertTrue(view.pauseResume)
    assertEquals(256, view.maxMemoryReadLength)
    assertTrue(view.coherentInspection)
    assertEquals(16, view.maxInspectionBlocks)
    assertEquals(256, view.maxInspectionBytes)
    assertEquals(listOf("Program counter"), view.breakpointKinds)
    assertEquals(8, view.maxBreakpoints)
    assertFalse(view.reverseHistory)
  }

  private fun snapshot(sp: Int): DebugSnapshot =
      DebugSnapshot(
          7,
          11,
          250,
          2,
          12,
          true,
          DebugRegisters(0x12, 0xa0, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, sp, 0x150),
          DebugInterruptState(true, false, 0x01, 0x01, 0x01),
          DebugTimerState(0x1234, 0x10, 0x20, 0x04, false, 0),
          DebugPpuState(
              true,
              DebugPpuMode.OAM_SEARCH,
              2,
              12,
              0x91,
              0x82,
              0,
              0,
              0,
              0,
              0,
          ),
          DebugApuState(true, 0, false, false, false, false, 0x77, 0xf3, 0x80),
          DebugMapperState(
              "MBC1",
              1,
              0,
              DebugFeatureState.ENABLED,
              DebugFeatureState.UNKNOWN,
              DebugFeatureState.DISABLED,
          ),
          DebugExecutionState(DebugCpuState.EXECUTING, 0xcb, 0x11, 1, false, false, 20),
      )

  private fun capabilities(
      memoryRead: Boolean = true,
      history: Boolean = true,
  ): DebugCapabilities {
    val historyCapabilities =
        if (history) {
          DebugHistoryCapabilities(
              true,
              true,
              true,
              120,
              DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES,
          )
        } else {
          DebugHistoryCapabilities.disabled()
        }
    return DebugCapabilities(
        true,
        true,
        true,
        true,
        true,
        memoryRead,
        true,
        if (memoryRead) 256 else 0,
        EnumSet.allOf(DebugBreakpointKind::class.java),
        32,
        emptySet(),
        0,
        0,
        historyCapabilities,
    )
  }

  private fun pcOnlyCapabilities(): DebugCapabilities =
      DebugCapabilities(
          true,
          true,
          true,
          true,
          true,
          true,
          true,
          256,
          EnumSet.of(DebugBreakpointKind.PROGRAM_COUNTER),
          8,
          emptySet(),
          0,
          0,
      )

  private fun historyStatus(
      cursor: DebugHistoryPosition = DebugHistoryPosition(250, 2, 12),
      futureCheckpointCount: Int = 1,
  ): DebugHistoryStatus =
      DebugHistoryStatus(
          DebugHistoryConfiguration(
              true,
              120,
              DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES,
          ),
          3,
          1024,
          0,
          DebugHistoryPoint(1, 100, 1),
          DebugHistoryPoint(3, 300, 3),
          cursor,
          futureCheckpointCount,
          DebugHistoryTruncationReason.USER_REWIND,
      )
}
