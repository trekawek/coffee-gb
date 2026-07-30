package eu.rekawek.coffeegb.core.debug.breakpoint;

import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.DebugPpuMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DebugBreakpointModelTest {

    @Test
    public void idsAreStableAcrossEnableDisableCopies() {
        DebugBreakpointId id = new DebugBreakpointId(17);
        DebugBreakpoint enabled = new DebugBreakpoint(id, true, DebugPcCondition.at(0x100));

        assertSame(enabled, enabled.enable());
        DebugBreakpoint disabled = enabled.disable();
        assertNotSame(enabled, disabled);
        assertSame(id, disabled.id());
        assertSame(enabled.condition(), disabled.condition());
        assertFalse(disabled.enabled());
        assertSame(disabled, disabled.disable());
        assertEquals(enabled, disabled.enable());

        assertThrows(IllegalArgumentException.class, () -> new DebugBreakpointId(-1));
        assertThrows(NullPointerException.class,
                () -> new DebugBreakpoint(null, true, DebugPcCondition.at(0)));
        assertThrows(NullPointerException.class,
                () -> new DebugBreakpoint(id, true, null));
    }

    @Test
    public void pcConditionsRepresentExactAddressesAndInclusiveRanges() {
        DebugPcCondition exact = DebugPcCondition.at(0xffff);
        assertEquals(0xffff, exact.startAddress());
        assertEquals(0xffff, exact.endAddress());
        assertTrue(exact.isExact());
        assertEquals(DebugBreakpointKind.PROGRAM_COUNTER, exact.kind());

        DebugPcCondition range = DebugPcCondition.range(0x100, 0x1ff);
        assertFalse(range.isExact());
        assertEquals(new DebugPcCondition(0x100, 0x1ff), range);

        assertThrows(IllegalArgumentException.class, () -> DebugPcCondition.at(-1));
        assertThrows(IllegalArgumentException.class, () -> DebugPcCondition.at(0x10000));
        assertThrows(IllegalArgumentException.class,
                () -> DebugPcCondition.range(0x200, 0x100));
    }

    @Test
    public void memoryConditionsHaveUnambiguousOptionalValueSemantics() {
        DebugMemoryCondition addressOnly = new DebugMemoryCondition(
                DebugMemoryAccess.READ, 0xc000, 0xdfff);
        assertEquals(DebugBreakpointKind.MEMORY, addressOnly.kind());
        assertFalse(addressOnly.hasValueConstraint());
        assertEquals(0, addressOnly.value());
        assertEquals(0, addressOnly.valueMask());

        DebugMemoryCondition exact = new DebugMemoryCondition(
                DebugMemoryAccess.WRITE, 0xff80, 0xfffe, 0xa5);
        assertTrue(exact.hasValueConstraint());
        assertEquals(0xa5, exact.value());
        assertEquals(0xff, exact.valueMask());

        DebugMemoryCondition masked = new DebugMemoryCondition(
                DebugMemoryAccess.EXECUTE, 0, 0xffff, 0xa0, 0xf0);
        assertEquals(masked, new DebugMemoryCondition(
                DebugMemoryAccess.EXECUTE, 0, 0xffff, 0xa0, 0xf0));
        assertEquals(masked.hashCode(), new DebugMemoryCondition(
                DebugMemoryAccess.EXECUTE, 0, 0xffff, 0xa0, 0xf0).hashCode());
        assertTrue(masked.toString().contains("EXECUTE"));

        assertThrows(NullPointerException.class,
                () -> new DebugMemoryCondition(null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugMemoryCondition(DebugMemoryAccess.READ, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugMemoryCondition(DebugMemoryAccess.READ, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugMemoryCondition(DebugMemoryAccess.READ, 0, 0, 0x100));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugMemoryCondition(DebugMemoryAccess.READ, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugMemoryCondition(DebugMemoryAccess.READ, 0, 0, 0, 0x100));
    }

    @Test
    public void opcodeAndInterruptConditionsAreTypedAndValidated() {
        DebugOpcodeCondition base = DebugOpcodeCondition.base(0xcb);
        DebugOpcodeCondition cb = DebugOpcodeCondition.cb(0x11);
        assertFalse(base.cbPrefixed());
        assertTrue(cb.cbPrefixed());
        assertEquals(DebugBreakpointKind.OPCODE, base.kind());
        assertEquals(DebugBreakpointKind.INTERRUPT,
                new DebugInterruptCondition(DebugInterruptType.TIMER).kind());

        assertThrows(IllegalArgumentException.class, () -> DebugOpcodeCondition.base(-1));
        assertThrows(IllegalArgumentException.class, () -> DebugOpcodeCondition.cb(0x100));
        assertThrows(NullPointerException.class, () -> new DebugInterruptCondition(null));
    }

    @Test
    public void serialConditionsDistinguishTransferEdgesAndOptionalByteMasks() {
        DebugSerialCondition started = new DebugSerialCondition(
                DebugSerialCondition.Event.TRANSFER_STARTED);
        assertEquals(DebugBreakpointKind.SERIAL, started.kind());
        assertEquals(DebugSerialCondition.Event.TRANSFER_STARTED, started.event());
        assertFalse(started.hasValueConstraint());
        assertEquals(0, started.value());
        assertEquals(0, started.valueMask());

        DebugSerialCondition exact = new DebugSerialCondition(
                DebugSerialCondition.Event.BYTE_TRANSFERRED, 0xa5);
        assertTrue(exact.hasValueConstraint());
        assertEquals(0xa5, exact.value());
        assertEquals(0xff, exact.valueMask());

        DebugSerialCondition masked = new DebugSerialCondition(
                DebugSerialCondition.Event.BYTE_TRANSFERRED, 0xa0, 0xf0);
        assertEquals(masked, new DebugSerialCondition(
                DebugSerialCondition.Event.BYTE_TRANSFERRED, 0xa0, 0xf0));
        assertEquals(masked.hashCode(), new DebugSerialCondition(
                DebugSerialCondition.Event.BYTE_TRANSFERRED, 0xa0, 0xf0).hashCode());
        assertTrue(masked.toString().contains("BYTE_TRANSFERRED"));

        assertThrows(NullPointerException.class, () -> new DebugSerialCondition(null));
        assertThrows(IllegalArgumentException.class, () -> new DebugSerialCondition(
                DebugSerialCondition.Event.TRANSFER_STARTED, -1));
        assertThrows(IllegalArgumentException.class, () -> new DebugSerialCondition(
                DebugSerialCondition.Event.TRANSFER_STARTED, 0x100));
        assertThrows(IllegalArgumentException.class, () -> new DebugSerialCondition(
                DebugSerialCondition.Event.BYTE_TRANSFERRED, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DebugSerialCondition(
                DebugSerialCondition.Event.BYTE_TRANSFERRED, 0, 0x100));
    }

    @Test
    public void ppuConditionsCanConstrainAnyNonEmptySubset() {
        DebugPpuCondition frame = DebugPpuCondition.atFrame(12);
        assertEquals(12, frame.frame());
        assertTrue(frame.constrainsFrame());
        assertFalse(frame.constrainsLy());
        assertFalse(frame.constrainsMode());

        DebugPpuCondition ly = DebugPpuCondition.atLy(153);
        assertFalse(ly.constrainsFrame());
        assertTrue(ly.constrainsLy());

        DebugPpuCondition mode = DebugPpuCondition.inMode(DebugPpuMode.HBLANK);
        assertTrue(mode.constrainsMode());

        DebugPpuCondition exact = DebugPpuCondition.at(
                4, 23, DebugPpuMode.PIXEL_TRANSFER);
        assertEquals(DebugBreakpointKind.PPU_STATE, exact.kind());
        assertTrue(exact.constrainsFrame());
        assertTrue(exact.constrainsLy());
        assertTrue(exact.constrainsMode());

        DebugPpuCondition frameAndMode = new DebugPpuCondition(
                8, DebugPpuCondition.ANY_LY, DebugPpuMode.VBLANK);
        assertTrue(frameAndMode.constrainsFrame());
        assertFalse(frameAndMode.constrainsLy());
        assertTrue(frameAndMode.constrainsMode());

        assertThrows(IllegalArgumentException.class,
                () -> new DebugPpuCondition(
                        DebugPpuCondition.ANY_FRAME, DebugPpuCondition.ANY_LY, null));
        assertThrows(IllegalArgumentException.class,
                () -> DebugPpuCondition.atFrame(-2));
        assertThrows(IllegalArgumentException.class,
                () -> DebugPpuCondition.atLy(154));
        assertThrows(NullPointerException.class,
                () -> DebugPpuCondition.inMode(null));
        assertThrows(NullPointerException.class,
                () -> DebugPpuCondition.at(0, 0, null));
    }

    @Test
    public void countersUseExactNonNegativeValues() {
        DebugCounterCondition tick = DebugCounterCondition.atMasterTick(1_000_000);
        DebugCounterCondition frame = DebugCounterCondition.atFrame(60);
        assertEquals(DebugCounterType.MASTER_TICK, tick.counter());
        assertEquals(1_000_000, tick.value());
        assertEquals(DebugCounterType.FRAME, frame.counter());
        assertEquals(DebugBreakpointKind.COUNTER, frame.kind());

        assertThrows(NullPointerException.class, () -> new DebugCounterCondition(null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DebugCounterCondition.atMasterTick(-1));
        assertThrows(IllegalArgumentException.class,
                () -> DebugCounterCondition.atFrame(-1));
    }
}
