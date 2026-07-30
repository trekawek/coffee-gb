package eu.rekawek.coffeegb.core.debug.breakpoint;

import com.sun.management.ThreadMXBean;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.DebugPpuMode;
import org.junit.Assume;
import org.junit.Test;

import java.lang.management.ManagementFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DebugBreakpointMatcherTest {

    @Test
    public void instructionMatchingSupportsExactAndRangePc() {
        DebugBreakpoint exact = breakpoint(DebugPcCondition.at(0x100));
        assertTrue(DebugBreakpointMatcher.matchesInstruction(exact, 0x100, false, 0x00));
        assertFalse(DebugBreakpointMatcher.matchesInstruction(exact, 0x101, false, 0x00));

        DebugBreakpoint range = breakpoint(DebugPcCondition.range(0x4000, 0x7fff));
        assertTrue(DebugBreakpointMatcher.matchesInstruction(range, 0x4000, false, 0x00));
        assertTrue(DebugBreakpointMatcher.matchesInstruction(range, 0x7fff, true, 0xff));
        assertFalse(DebugBreakpointMatcher.matchesInstruction(range, 0x3fff, false, 0x00));
        assertFalse(DebugBreakpointMatcher.matchesInstruction(range, 0x8000, false, 0x00));
    }

    @Test
    public void opcodeMatchingDistinguishesBaseAndCbTables() {
        DebugBreakpoint base = breakpoint(DebugOpcodeCondition.base(0x11));
        DebugBreakpoint cb = breakpoint(DebugOpcodeCondition.cb(0x11));

        assertTrue(DebugBreakpointMatcher.matchesInstruction(base, 0, false, 0x11));
        assertFalse(DebugBreakpointMatcher.matchesInstruction(base, 0, true, 0x11));
        assertFalse(DebugBreakpointMatcher.matchesInstruction(base, 0, false, 0x10));
        assertTrue(DebugBreakpointMatcher.matchesInstruction(cb, 0, true, 0x11));
        assertFalse(DebugBreakpointMatcher.matchesInstruction(cb, 0, false, 0x11));
    }

    @Test
    public void memoryMatchingChecksAccessInclusiveRangeAndOptionalValue() {
        DebugBreakpoint read = breakpoint(new DebugMemoryCondition(
                DebugMemoryAccess.READ, 0xc000, 0xc002));
        assertTrue(DebugBreakpointMatcher.matchesMemory(
                read, DebugMemoryAccess.READ, 0xc000, 0));
        assertTrue(DebugBreakpointMatcher.matchesMemory(
                read, DebugMemoryAccess.READ, 0xc002, 0xff));
        assertFalse(DebugBreakpointMatcher.matchesMemory(
                read, DebugMemoryAccess.WRITE, 0xc001, 0));
        assertFalse(DebugBreakpointMatcher.matchesMemory(
                read, DebugMemoryAccess.READ, 0xbfff, 0));
        assertFalse(DebugBreakpointMatcher.matchesMemory(
                read, DebugMemoryAccess.READ, 0xc003, 0));

        DebugBreakpoint exact = breakpoint(new DebugMemoryCondition(
                DebugMemoryAccess.WRITE, 0xff80, 0xff80, 0xa5));
        assertTrue(DebugBreakpointMatcher.matchesMemory(
                exact, DebugMemoryAccess.WRITE, 0xff80, 0xa5));
        assertFalse(DebugBreakpointMatcher.matchesMemory(
                exact, DebugMemoryAccess.WRITE, 0xff80, 0xa4));

        DebugBreakpoint masked = breakpoint(new DebugMemoryCondition(
                DebugMemoryAccess.EXECUTE, 0x100, 0x1ff, 0xa0, 0xf0));
        assertTrue(DebugBreakpointMatcher.matchesMemory(
                masked, DebugMemoryAccess.EXECUTE, 0x100, 0xaf));
        assertTrue(DebugBreakpointMatcher.matchesMemory(
                masked, DebugMemoryAccess.EXECUTE, 0x1ff, 0xa1));
        assertFalse(DebugBreakpointMatcher.matchesMemory(
                masked, DebugMemoryAccess.EXECUTE, 0x100, 0xb0));
    }

    @Test
    public void interruptMatchingUsesDetachedPublicTypes() {
        DebugBreakpoint timer = breakpoint(
                new DebugInterruptCondition(DebugInterruptType.TIMER));
        assertTrue(DebugBreakpointMatcher.matchesInterrupt(timer, DebugInterruptType.TIMER));
        assertFalse(DebugBreakpointMatcher.matchesInterrupt(timer, DebugInterruptType.SERIAL));
    }

    @Test
    public void ppuMatchingCombinesOnlyConfiguredDimensions() {
        DebugBreakpoint ly = breakpoint(DebugPpuCondition.atLy(144));
        assertTrue(DebugBreakpointMatcher.matchesPpu(
                ly, 3, 144, DebugPpuMode.VBLANK));
        assertFalse(DebugBreakpointMatcher.matchesPpu(
                ly, 3, 143, DebugPpuMode.PIXEL_TRANSFER));

        DebugBreakpoint mode = breakpoint(DebugPpuCondition.inMode(DebugPpuMode.HBLANK));
        assertTrue(DebugBreakpointMatcher.matchesPpu(
                mode, 100, 50, DebugPpuMode.HBLANK));

        DebugBreakpoint exact = breakpoint(DebugPpuCondition.at(
                7, 8, DebugPpuMode.OAM_SEARCH));
        assertTrue(DebugBreakpointMatcher.matchesPpu(
                exact, 7, 8, DebugPpuMode.OAM_SEARCH));
        assertFalse(DebugBreakpointMatcher.matchesPpu(
                exact, 8, 8, DebugPpuMode.OAM_SEARCH));
        assertFalse(DebugBreakpointMatcher.matchesPpu(
                exact, 7, 9, DebugPpuMode.OAM_SEARCH));
        assertFalse(DebugBreakpointMatcher.matchesPpu(
                exact, 7, 8, DebugPpuMode.PIXEL_TRANSFER));

        DebugBreakpoint frameAndMode = breakpoint(new DebugPpuCondition(
                9, DebugPpuCondition.ANY_LY, DebugPpuMode.VBLANK));
        assertTrue(DebugBreakpointMatcher.matchesPpu(
                frameAndMode, 9, 153, DebugPpuMode.VBLANK));
        assertFalse(DebugBreakpointMatcher.matchesPpu(
                frameAndMode, 9, 153, DebugPpuMode.HBLANK));
    }

    @Test
    public void counterMatchingSelectsTickOrFrameWithoutConflatingThem() {
        DebugBreakpoint tick = breakpoint(DebugCounterCondition.atMasterTick(42));
        assertTrue(DebugBreakpointMatcher.matchesCounters(tick, 42, 1));
        assertFalse(DebugBreakpointMatcher.matchesCounters(tick, 41, 42));

        DebugBreakpoint frame = breakpoint(DebugCounterCondition.atFrame(42));
        assertTrue(DebugBreakpointMatcher.matchesCounters(frame, 1, 42));
        assertFalse(DebugBreakpointMatcher.matchesCounters(frame, 42, 41));
    }

    @Test
    public void disabledAndWrongEventConditionsNeverMatch() {
        DebugBreakpoint disabled = breakpoint(DebugPcCondition.at(0x100)).disable();
        assertFalse(DebugBreakpointMatcher.matchesInstruction(
                disabled, 0x100, false, 0));

        DebugBreakpoint interrupt = breakpoint(
                new DebugInterruptCondition(DebugInterruptType.VBLANK));
        assertFalse(DebugBreakpointMatcher.matchesInstruction(
                interrupt, 0x100, false, 0));
        assertFalse(DebugBreakpointMatcher.matchesMemory(
                interrupt, DebugMemoryAccess.READ, 0xc000, 0));
        assertFalse(DebugBreakpointMatcher.matchesPpu(
                interrupt, 0, 0, DebugPpuMode.DISABLED));
        assertFalse(DebugBreakpointMatcher.matchesCounters(interrupt, 0, 0));
    }

    @Test
    public void applicableObservationsAreValidatedAtTheMatcherBoundary() {
        DebugBreakpoint pc = breakpoint(DebugPcCondition.at(0));
        assertThrows(IllegalArgumentException.class,
                () -> DebugBreakpointMatcher.matchesInstruction(pc, -1, false, 0));

        DebugBreakpoint opcode = breakpoint(DebugOpcodeCondition.base(0));
        assertThrows(IllegalArgumentException.class,
                () -> DebugBreakpointMatcher.matchesInstruction(opcode, 0, false, 0x100));

        DebugBreakpoint memory = breakpoint(new DebugMemoryCondition(
                DebugMemoryAccess.READ, 0, 0xffff));
        assertThrows(NullPointerException.class,
                () -> DebugBreakpointMatcher.matchesMemory(memory, null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DebugBreakpointMatcher.matchesMemory(
                        memory, DebugMemoryAccess.READ, 0x10000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DebugBreakpointMatcher.matchesMemory(
                        memory, DebugMemoryAccess.READ, 0, -1));

        DebugBreakpoint ppu = breakpoint(DebugPpuCondition.atLy(0));
        assertThrows(IllegalArgumentException.class,
                () -> DebugBreakpointMatcher.matchesPpu(
                        ppu, -1, 0, DebugPpuMode.HBLANK));
        assertThrows(NullPointerException.class,
                () -> DebugBreakpointMatcher.matchesPpu(ppu, 0, 0, null));

        DebugBreakpoint counter = breakpoint(DebugCounterCondition.atFrame(0));
        assertThrows(IllegalArgumentException.class,
                () -> DebugBreakpointMatcher.matchesCounters(counter, -1, 0));
        assertThrows(NullPointerException.class,
                () -> DebugBreakpointMatcher.matchesInterrupt(
                        breakpoint(new DebugInterruptCondition(DebugInterruptType.TIMER)), null));
        assertThrows(NullPointerException.class,
                () -> DebugBreakpointMatcher.matchesInstruction(null, 0, false, 0));
    }

    @Test
    public void repeatedNonHitsDoNotAllocateOnTheCallingThread() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean allocationBean = (ThreadMXBean) platformBean;
        Assume.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }

        DebugBreakpoint watchpoint = breakpoint(new DebugMemoryCondition(
                DebugMemoryAccess.WRITE, 0xc000, 0xdfff, 0xf0, 0xf0));
        for (int i = 0; i < 100_000; i++) {
            DebugBreakpointMatcher.matchesMemory(
                    watchpoint, DebugMemoryAccess.WRITE, 0xc000 + (i & 0x1fff), 0x0f);
        }

        long threadId = Thread.currentThread().getId();
        allocationBean.getThreadAllocatedBytes(threadId);
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        boolean matched = false;
        for (int i = 0; i < 1_000_000; i++) {
            matched |= DebugBreakpointMatcher.matchesMemory(
                    watchpoint, DebugMemoryAccess.WRITE, 0xc000 + (i & 0x1fff), 0x0f);
        }
        long after = allocationBean.getThreadAllocatedBytes(threadId);

        assertFalse(matched);
        assertEquals("matcher allocated on its non-hit path", 0, after - before);
    }

    private static DebugBreakpoint breakpoint(DebugBreakpointCondition condition) {
        return new DebugBreakpoint(new DebugBreakpointId(1), true, condition);
    }
}
