package eu.rekawek.coffeegb.core.debug;

import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DebugDisassemblerTest {

    @Test
    public void formatsDetachedBaseAndExtendedOpcodesWithTheirSourceView() {
        String base = DebugDisassembler.disassemble(new DebugMemoryBlock(
                DebugAddressSpace.ROM, 0x4000, new byte[]{0x3e, 0x7b, 0}));
        assertTrue(base, base.startsWith("4000: 3E 7B"));
        assertTrue(base, base.contains("LD A,7B"));
        assertTrue(base, base.contains(
                "best-effort: parser-corrected ROM image, not the mapped CPU window"));

        String extended = DebugDisassembler.disassemble(new DebugMemoryBlock(
                DebugAddressSpace.SYSTEM_BUS, 0xc000,
                new byte[]{(byte) 0xcb, 0x7c}));
        assertTrue(extended, extended.startsWith("C000: CB 7C"));
        assertTrue(extended, extended.endsWith("[best-effort: SYSTEM_BUS]"));
    }

    @Test
    public void rejectsEmptyAndTruncatedInstructionBlocks() {
        assertThrows(IllegalArgumentException.class, () -> DebugDisassembler.disassemble(
                new DebugMemoryBlock(DebugAddressSpace.ROM, 0x100, new byte[]{})));
        assertThrows(IllegalArgumentException.class, () -> DebugDisassembler.disassemble(
                new DebugMemoryBlock(DebugAddressSpace.ROM, 0x100, new byte[]{0x01})));
        assertThrows(IllegalArgumentException.class, () -> DebugDisassembler.disassemble(
                new DebugMemoryBlock(DebugAddressSpace.ROM, 0x100,
                        new byte[]{(byte) 0xcb})));
        assertThrows(NullPointerException.class, () -> DebugDisassembler.disassemble(null));
    }
}
