package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.debug.DebugInstrumentation;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.DebugPpuMode;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugMemoryCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugOpcodeCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.debug.trace.PpuTrace;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceEntry;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GameboyDebugInstrumentationTest {

    @Test
    public void pcBreakpointUsesTheRealFetchAndStopsAfterThatInstructionRetires()
            throws Exception {
        try (Gameboy gameboy = gameboy()) {
            DebugInstrumentation instrumentation = instrumentation();
            instrumentation.setBreakpoint(new DebugBreakpoint(
                    new DebugBreakpointId(7), true, DebugPcCondition.at(0x100)));
            gameboy.updateDebugInstrumentation(instrumentation, 0);

            DebugInstrumentation.BreakpointMatch hit = null;
            int ticks = 0;
            while (hit == null && ticks < 100) {
                gameboy.tick();
                ticks++;
                hit = instrumentation.pollBreakpointMatch();
            }

            assertNotNull(hit);
            assertEquals(7, hit.breakpointId().value());
            assertEquals(ticks, hit.matchMasterTick());
            assertEquals(0x101, gameboy.getCpu().getRegisters().getPC());
        }
    }

    @Test
    public void detachedExecutionDoesNotAppendAndReattachmentKeepsTickAlignment()
            throws Exception {
        try (Gameboy gameboy = gameboy()) {
            DebugInstrumentation instrumentation = instrumentation();
            instrumentation.configureTrace(new TraceConfiguration(
                    32, EnumSet.of(TraceCategory.CPU, TraceCategory.MEMORY)));
            gameboy.updateDebugInstrumentation(instrumentation, 0);
            for (int tick = 0; tick < 20; tick++) gameboy.tick();
            long beforeDetach = instrumentation.readTrace(
                    TraceReadRequest.initial(64)).nextSequence();
            assertTrue(beforeDetach > 0);

            gameboy.updateDebugInstrumentation(null, 20);
            for (int tick = 0; tick < 20; tick++) gameboy.tick();
            assertEquals(beforeDetach, instrumentation.readTrace(
                    TraceReadRequest.initial(64)).nextSequence());

            gameboy.updateDebugInstrumentation(instrumentation, 40);
            for (int tick = 0; tick < 8; tick++) gameboy.tick();
            var read = instrumentation.readTrace(TraceReadRequest.initial(64));
            assertTrue(read.nextSequence() > beforeDetach);
            assertTrue(read.entries().stream().anyMatch(entry ->
                    entry.sequence() >= beforeDetach
                            && entry.masterTick() >= 41
                            && entry.masterTick() <= 48));
        }
    }

    @Test
    public void stoppedCpuBusPollingCanTriggerAReadWatchpointWithoutRetirement()
            throws Exception {
        try (Gameboy gameboy = stoppedGameboy()) {
            int completedTicks = 0;
            while (gameboy.getCpu().getState() != Cpu.State.STOPPED
                    && completedTicks < 100) {
                gameboy.tick();
                completedTicks++;
            }
            assertEquals(Cpu.State.STOPPED, gameboy.getCpu().getState());

            DebugInstrumentation instrumentation = instrumentation();
            instrumentation.setBreakpoint(new DebugBreakpoint(
                    new DebugBreakpointId(11), true,
                    new DebugMemoryCondition(DebugMemoryAccess.READ, 0xff00, 0xff00)));
            gameboy.updateDebugInstrumentation(instrumentation, completedTicks);

            DebugInstrumentation.BreakpointMatch match = null;
            for (int tick = 0; match == null && tick < 16; tick++) {
                gameboy.tick();
                completedTicks++;
                match = instrumentation.pollBreakpointMatch();
            }

            assertNotNull(match);
            assertEquals(11, match.breakpointId().value());
            assertEquals(completedTicks, match.matchMasterTick());
            assertEquals(Cpu.State.STOPPED, gameboy.getCpu().getState());
        }
    }

    @Test
    public void stopPaddingDoesNotMasqueradeAsACbPrefixedOpcode() throws Exception {
        try (Gameboy gameboy = programGameboy(0x10, 0x11)) {
            DebugInstrumentation instrumentation = instrumentation();
            instrumentation.setBreakpoint(new DebugBreakpoint(
                    new DebugBreakpointId(12), true, DebugOpcodeCondition.cb(0x11)));
            gameboy.updateDebugInstrumentation(instrumentation, 0);

            for (int tick = 0; tick < 100; tick++) {
                gameboy.tick();
            }

            assertEquals(Cpu.State.STOPPED, gameboy.getCpu().getState());
            assertNull(instrumentation.pollBreakpointMatch());
        }
    }

    @Test
    public void realCbPrefixedOpcodeMatchesAtRetirement() throws Exception {
        try (Gameboy gameboy = programGameboy(0xcb, 0x11, 0x18, 0xfc)) {
            DebugInstrumentation instrumentation = instrumentation();
            instrumentation.setBreakpoint(new DebugBreakpoint(
                    new DebugBreakpointId(13), true, DebugOpcodeCondition.cb(0x11)));
            gameboy.updateDebugInstrumentation(instrumentation, 0);

            DebugInstrumentation.BreakpointMatch match = null;
            for (int tick = 0; match == null && tick < 100; tick++) {
                gameboy.tick();
                match = instrumentation.pollBreakpointMatch();
            }

            assertNotNull(match);
            assertEquals(13, match.breakpointId().value());
            assertEquals(0x102, gameboy.getCpu().getRegisters().getPC());
        }
    }

    @Test
    public void ppuAttachmentIsSilentEvenWhenTheAlignedStateMatches() throws Exception {
        try (Gameboy gameboy = gameboy()) {
            var snapshot = gameboy.captureDebugSnapshot(1, 1, 0, 0, 0, true);
            DebugInstrumentation instrumentation = instrumentation();
            instrumentation.configureTrace(new TraceConfiguration(
                    64, EnumSet.of(TraceCategory.PPU)));
            instrumentation.setBreakpoint(new DebugBreakpoint(
                    new DebugBreakpointId(20),
                    true,
                    new DebugPpuCondition(
                            DebugPpuCondition.ANY_FRAME,
                            snapshot.ppu().line(),
                            snapshot.ppu().mode())));

            gameboy.updateDebugInstrumentation(instrumentation, 0);

            assertNull(instrumentation.pollBreakpointMatch());
            assertTrue(instrumentation.readTrace(
                    TraceReadRequest.initial(64)).entries().isEmpty());
        }
    }

    @Test
    public void ppuBoundaryEventsAreOrderedAndPhysicalFramesAdvanceWhileDetached()
            throws Exception {
        try (Gameboy gameboy = gameboy()) {
            DebugInstrumentation instrumentation = instrumentation();
            instrumentation.configureTrace(new TraceConfiguration(
                    64, EnumSet.of(TraceCategory.PPU)));
            gameboy.updateDebugInstrumentation(instrumentation, 0);
            int frameTicks = gameboy.getClockSpec().controllerTicksPerFrame();

            tick(gameboy, frameTicks);

            List<TraceEntry> firstFrame = instrumentation.readTrace(
                    TraceReadRequest.initial(64)).entries();
            int readyIndex = indexOfFrameReady(firstFrame, 1);
            assertTrue(readyIndex >= 2);
            assertPpuEvent(firstFrame.get(readyIndex - 2),
                    PpuTrace.Kind.SCANLINE_STARTED, 1, 144, 0, DebugPpuMode.VBLANK);
            assertPpuEvent(firstFrame.get(readyIndex - 1),
                    PpuTrace.Kind.MODE_CHANGED, 1, 144, 0, DebugPpuMode.VBLANK);
            assertPpuEvent(firstFrame.get(readyIndex),
                    PpuTrace.Kind.FRAME_READY, 1, 144, 0, DebugPpuMode.VBLANK);
            assertEquals(firstFrame.get(readyIndex - 2).masterTick(),
                    firstFrame.get(readyIndex - 1).masterTick());
            assertEquals(firstFrame.get(readyIndex - 2).masterTick(),
                    firstFrame.get(readyIndex).masterTick());

            long sequenceBeforeDetach = instrumentation.readTrace(
                    TraceReadRequest.initial(64)).nextSequence();
            gameboy.updateDebugInstrumentation(null, frameTicks);
            tick(gameboy, frameTicks);
            assertEquals(sequenceBeforeDetach, instrumentation.readTrace(
                    TraceReadRequest.initial(64)).nextSequence());

            gameboy.updateDebugInstrumentation(instrumentation, frameTicks * 2L);
            assertEquals(sequenceBeforeDetach, instrumentation.readTrace(
                    TraceReadRequest.initial(64)).nextSequence());
            tick(gameboy, frameTicks);

            List<TraceEntry> reattached = instrumentation.readTrace(
                    new TraceReadRequest(sequenceBeforeDetach, 64)).entries();
            assertTrue(indexOfFrameReady(reattached, 3) >= 0);
        }
    }

    @Test
    public void lcdSwitchesProduceExplicitPpuEdges() throws Exception {
        try (Gameboy gameboy = gameboy()) {
            DebugInstrumentation instrumentation = instrumentation();
            instrumentation.configureTrace(new TraceConfiguration(
                    8, EnumSet.of(TraceCategory.PPU)));
            gameboy.updateDebugInstrumentation(instrumentation, 0);
            int lcdc = gameboy.captureDebugSnapshot(1, 1, 0, 0, 0, true).ppu().lcdc();

            gameboy.getAddressSpace().setByte(0xff40, lcdc & 0x7f);
            gameboy.getAddressSpace().setByte(0xff40, lcdc | 0x80);

            List<TraceEntry> entries = instrumentation.readTrace(
                    TraceReadRequest.initial(8)).entries();
            assertEquals(2, entries.size());
            assertPpuEvent(entries.get(0),
                    PpuTrace.Kind.LCD_DISABLED, 0, 0, 0, DebugPpuMode.DISABLED);
            assertPpuEvent(entries.get(1),
                    PpuTrace.Kind.LCD_ENABLED, 0, 0, 0, DebugPpuMode.OAM_SEARCH);
        }
    }

    private static int indexOfFrameReady(List<TraceEntry> entries, long ppuFrame) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).event() instanceof PpuTrace ppu
                    && ppu.kind() == PpuTrace.Kind.FRAME_READY
                    && ppu.ppuFrame() == ppuFrame) {
                return i;
            }
        }
        return -1;
    }

    private static void assertPpuEvent(
            TraceEntry entry,
            PpuTrace.Kind kind,
            long ppuFrame,
            int line,
            int dot,
            DebugPpuMode mode) {
        PpuTrace event = (PpuTrace) entry.event();
        assertEquals(kind, event.kind());
        assertEquals(ppuFrame, event.ppuFrame());
        assertEquals(line, event.line());
        assertEquals(dot, event.dot());
        assertEquals(mode, event.mode());
    }

    private static void tick(Gameboy gameboy, int ticks) {
        for (int i = 0; i < ticks; i++) {
            gameboy.tick();
        }
    }

    private static DebugInstrumentation instrumentation() {
        return new DebugInstrumentation(
                8,
                64,
                16,
                EnumSet.allOf(DebugBreakpointKind.class),
                EnumSet.allOf(TraceCategory.class));
    }

    private static Gameboy gameboy() throws Exception {
        byte[] rom = new byte[0x8000];
        rom[0x100] = 0x00; // NOP
        rom[0x101] = 0x18; // JR -3
        rom[0x102] = (byte) 0xfd;
        rom[0x147] = 0;
        return new Gameboy.GameboyConfiguration(new Rom(rom))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy stoppedGameboy() throws Exception {
        return programGameboy(0x10, 0x00);
    }

    private static Gameboy programGameboy(int... program) throws Exception {
        byte[] rom = new byte[0x8000];
        for (int i = 0; i < program.length; i++) {
            rom[0x100 + i] = (byte) program[i];
        }
        rom[0x147] = 0;
        return new Gameboy.GameboyConfiguration(new Rom(rom))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
    }
}
