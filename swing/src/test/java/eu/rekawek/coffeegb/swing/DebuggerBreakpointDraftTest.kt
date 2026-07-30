package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugInterruptType
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess
import eu.rekawek.coffeegb.core.debug.DebugPpuMode
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterType
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugInterruptCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugMemoryCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugOpcodeCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugSerialCondition
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerBreakpointDraftTest {
  @Test
  fun `editor variants expose the negotiated core kind`() {
    assertEquals(
        mapOf(
            DebuggerBreakpointEditorKind.PROGRAM_COUNTER to
                DebugBreakpointKind.PROGRAM_COUNTER,
            DebuggerBreakpointEditorKind.MEMORY_READ to DebugBreakpointKind.MEMORY,
            DebuggerBreakpointEditorKind.MEMORY_WRITE to DebugBreakpointKind.MEMORY,
            DebuggerBreakpointEditorKind.MEMORY_EXECUTE to DebugBreakpointKind.MEMORY,
            DebuggerBreakpointEditorKind.BASE_OPCODE to DebugBreakpointKind.OPCODE,
            DebuggerBreakpointEditorKind.CB_OPCODE to DebugBreakpointKind.OPCODE,
            DebuggerBreakpointEditorKind.INTERRUPT to DebugBreakpointKind.INTERRUPT,
            DebuggerBreakpointEditorKind.PPU_STATE to DebugBreakpointKind.PPU_STATE,
            DebuggerBreakpointEditorKind.SERIAL_START to DebugBreakpointKind.SERIAL,
            DebuggerBreakpointEditorKind.SERIAL_COMPLETION to DebugBreakpointKind.SERIAL,
            DebuggerBreakpointEditorKind.MASTER_TICK to DebugBreakpointKind.COUNTER,
            DebuggerBreakpointEditorKind.FRAME_COUNTER to DebugBreakpointKind.COUNTER,
        ),
        DebuggerBreakpointEditorKind.entries.associateWith { it.negotiatedKind },
    )
    assertTrue(DebuggerBreakpointEditorKind.entries.all { it.displayName.isNotBlank() })
  }

  @Test
  fun `program counter and memory drafts reuse established hexadecimal parsing`() {
    assertEquals(
        DebugPcCondition(0x100, 0x1ff),
        parse(DebuggerBreakpointDraft.ProgramCounter("${'$'}0100..0x01FF")),
    )
    assertEquals(
        DebugMemoryCondition(DebugMemoryAccess.READ, 0xc000, 0xc000),
        parse(DebuggerBreakpointDraft.Memory(DebugMemoryAccess.READ, "C000")),
    )
    assertEquals(
        DebugMemoryCondition(DebugMemoryAccess.WRITE, 0xc000, 0xc0ff, 0x7f),
        parse(
            DebuggerBreakpointDraft.Memory(
                DebugMemoryAccess.WRITE,
                "${'$'}C000-${'$'}C0FF",
                "7f",
            )
        ),
    )
    assertEquals(
        DebugMemoryCondition(DebugMemoryAccess.EXECUTE, 0x100, 0x1ff, 0xa0, 0xf0),
        parse(
            DebuggerBreakpointDraft.Memory(
                DebugMemoryAccess.EXECUTE,
                "0100:01ff",
                "${'$'}A0",
                "0xF0",
            )
        ),
    )
  }

  @Test
  fun `opcode interrupt and serial variants build their typed conditions`() {
    assertEquals(
        DebugOpcodeCondition.base(0x3e),
        parse(DebuggerBreakpointDraft.Opcode(false, "3e")),
    )
    assertEquals(
        DebugOpcodeCondition.cb(0x7c),
        parse(DebuggerBreakpointDraft.Opcode(true, "${'$'}7C")),
    )
    assertEquals(
        DebugInterruptCondition(DebugInterruptType.VBLANK),
        parse(DebuggerBreakpointDraft.Interrupt(DebugInterruptType.VBLANK)),
    )
    assertEquals(
        DebugSerialCondition(DebugSerialCondition.Event.TRANSFER_STARTED),
        parse(DebuggerBreakpointDraft.Serial(DebugSerialCondition.Event.TRANSFER_STARTED)),
    )
    assertEquals(
        DebugSerialCondition(DebugSerialCondition.Event.BYTE_TRANSFERRED, 0xa5, 0xf0),
        parse(
            DebuggerBreakpointDraft.Serial(
                DebugSerialCondition.Event.BYTE_TRANSFERRED,
                "A5",
                "F0",
            )
        ),
    )
  }

  @Test
  fun `PPU draft accepts optional bounded decimal or explicitly hexadecimal constraints`() {
    assertEquals(
        DebugPpuCondition(42, 153, DebugPpuMode.PIXEL_TRANSFER),
        parse(
            DebuggerBreakpointDraft.Ppu(
                frameText = "42",
                lyText = "${'$'}99",
                mode = DebugPpuMode.PIXEL_TRANSFER,
            )
        ),
    )
    assertEquals(
        DebugPpuCondition.inMode(DebugPpuMode.VBLANK),
        parse(DebuggerBreakpointDraft.Ppu(mode = DebugPpuMode.VBLANK)),
    )
    assertEquals(
        DebugPpuCondition.atFrame(16),
        parse(DebuggerBreakpointDraft.Ppu(frameText = "0x10")),
    )
  }

  @Test
  fun `counter drafts distinguish decimal from prefixed hexadecimal values`() {
    assertEquals(
        DebugCounterCondition.atMasterTick(10),
        parse(DebuggerBreakpointDraft.Counter(DebugCounterType.MASTER_TICK, "10")),
    )
    assertEquals(
        DebugCounterCondition.atMasterTick(16),
        parse(DebuggerBreakpointDraft.Counter(DebugCounterType.MASTER_TICK, "${'$'}10")),
    )
    assertEquals(
        DebugCounterCondition.atFrame(16),
        parse(DebuggerBreakpointDraft.Counter(DebugCounterType.FRAME, "0x10")),
    )
    assertEquals(
        DebugCounterCondition.atFrame(Long.MAX_VALUE),
        parse(
            DebuggerBreakpointDraft.Counter(
                DebugCounterType.FRAME,
                "${'$'}7fffffffffffffff",
            )
        ),
    )
  }

  @Test
  fun `draft validation reports missing dependent and bounded values without throwing`() {
    assertError(DebuggerBreakpointDraft.Interrupt(null), "Choose an interrupt")
    assertError(DebuggerBreakpointDraft.Ppu(), "at least one PPU constraint")
    assertError(DebuggerBreakpointDraft.Ppu(lyText = "154"), "LY must be between 0 and 153")
    assertError(DebuggerBreakpointDraft.Ppu(frameText = "-1"), "Frame must be")
    assertError(
        DebuggerBreakpointDraft.Counter(DebugCounterType.MASTER_TICK, ""),
        "Master tick is required",
    )
    assertError(
        DebuggerBreakpointDraft.Counter(DebugCounterType.FRAME, "9223372036854775808"),
        "between 0 and ${Long.MAX_VALUE}",
    )
    assertError(
        DebuggerBreakpointDraft.Memory(
            DebugMemoryAccess.WRITE,
            "${'$'}C000",
            maskText = "F0",
        ),
        "value is required",
    )
    assertError(
        DebuggerBreakpointDraft.Serial(
            DebugSerialCondition.Event.TRANSFER_STARTED,
            valueText = "12",
            maskText = "00",
        ),
        "at least one set bit",
    )
  }

  @Test
  fun `every core condition round trips through an editable draft`() {
    val conditions =
        listOf<DebugBreakpointCondition>(
            DebugPcCondition(0x100, 0x1ff),
            DebugMemoryCondition(DebugMemoryAccess.READ, 0xc000, 0xc010),
            DebugMemoryCondition(DebugMemoryAccess.WRITE, 0xd000, 0xd000, 0x7f),
            DebugMemoryCondition(DebugMemoryAccess.EXECUTE, 0x100, 0x1ff, 0xa0, 0xf0),
            DebugOpcodeCondition.base(0x3e),
            DebugOpcodeCondition.cb(0x7c),
            DebugInterruptCondition(DebugInterruptType.TIMER),
            DebugPpuCondition(12, 144, DebugPpuMode.VBLANK),
            DebugSerialCondition(DebugSerialCondition.Event.TRANSFER_STARTED, 0x81),
            DebugSerialCondition(DebugSerialCondition.Event.BYTE_TRANSFERRED, 0xa0, 0xf0),
            DebugCounterCondition.atMasterTick(123_456),
            DebugCounterCondition.atFrame(789),
        )

    val drafts = conditions.map { DebuggerBreakpointDraft.from(it) }

    assertEquals(DebuggerBreakpointEditorKind.entries, drafts.map { it.editorKind })
    assertEquals(conditions, drafts.map(::parse))
    drafts.forEach { draft ->
      assertEquals(draft, DebuggerBreakpointDraft.from(parse(draft)))
    }
  }

  @Test
  fun `invalid opcode and ambiguous bare hexadecimal counters retain friendly field errors`() {
    val opcode = DebuggerBreakpointDraft.Opcode(false, "100").parse()
    assertFalse(opcode.isValid)
    assertContains(opcode.error!!, "Opcode")

    val counter =
        DebuggerBreakpointDraft.Counter(DebugCounterType.FRAME, "A").parse()
    assertFalse(counter.isValid)
    assertContains(counter.error!!, "Frame counter")
    assertContains(counter.error, "prefixed")
  }

  private fun parse(draft: DebuggerBreakpointDraft): DebugBreakpointCondition {
    val result = draft.parse()
    assertTrue(result.isValid, result.error)
    return result.value!!
  }

  private fun assertError(draft: DebuggerBreakpointDraft, expected: String) {
    val result = draft.parse()
    assertFalse(result.isValid)
    assertContains(result.error!!, expected, ignoreCase = true)
  }
}
