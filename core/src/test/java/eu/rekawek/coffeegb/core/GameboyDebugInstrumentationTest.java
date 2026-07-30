package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.debug.DebugInstrumentation;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugMemoryCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugOpcodeCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.util.EnumSet;

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

    private static DebugInstrumentation instrumentation() {
        return new DebugInstrumentation(
                8,
                64,
                16,
                EnumSet.of(
                        DebugBreakpointKind.PROGRAM_COUNTER,
                        DebugBreakpointKind.MEMORY,
                        DebugBreakpointKind.OPCODE,
                        DebugBreakpointKind.INTERRUPT,
                        DebugBreakpointKind.COUNTER),
                EnumSet.of(
                        TraceCategory.CPU,
                        TraceCategory.MEMORY,
                        TraceCategory.INTERRUPT));
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
