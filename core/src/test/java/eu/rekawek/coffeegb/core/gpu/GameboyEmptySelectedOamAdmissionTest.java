package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.performance.PerformanceDiagnostics;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Focused proof for admitting a direct native-CGB x2 mode-3 line after OAM DMA already owns
 * OAM, when both pixel machines selected no objects. The proof uses a real CPU FF46 store and
 * compares the composed owner with a scalar PERFORMANCE owner.
 */
public final class GameboyEmptySelectedOamAdmissionTest {
    private static final HardwareProfile[] PROFILES = {
            HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0
    };

    @Test
    public void emptySelectedBackgroundAndWindowAdmitAndMatchScalarPerformance() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            for (VisualCase visual : new VisualCase[]{VisualCase.BACKGROUND, VisualCase.WINDOW}) {
                try (Session scalar = session(ExecutionMode.PERFORMANCE, profile, visual);
                        Session composed = session(ExecutionMode.PERFORMANCE, profile, visual)) {
                    enableDoubleSpeed(scalar.gameboy);
                    enableDoubleSpeed(composed.gameboy);
                    scalar.gameboy.setPerformanceBatchingEnabled(false);
                    scalar.gameboy.getGpu().setPerformanceScanlineEnabled(true);
                    composed.gameboy.setPerformanceBatchingEnabled(true);
                    composed.gameboy.getGpu().setPerformanceScanlineEnabled(true);
                    advanceToLineDot(scalar, composed, 1, 60);
                    alignCpuOpcode(scalar, composed);
                    installRealFf46(scalar.gameboy, 0xff80, 0xc0);
                    installRealFf46(composed.gameboy, 0xff80, 0xc0);
                    PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(100_000);
                    composed.gameboy.setPerformanceDiagnostics(diagnostics);

                    reachMode3AfterOwnedDma(scalar, composed);
                    assertTrue(profile.id() + " " + visual + " composed direct line",
                            composed.gameboy.getGpu().isPerformanceScanlineCursorActive());
                    assertTrue(profile.id() + " " + visual + " scalar direct line",
                            scalar.gameboy.getGpu().isPerformanceScanlineCursorActive());
                    assertTrue(profile.id() + " " + visual + " DMA active",
                            dma(composed.gameboy).isTransferInProgress());
                    assertTrue(profile.id() + " " + visual + " PPU owns OAM",
                            dma(composed.gameboy).ownsOamForPpu());
                    assertTrue(profile.id() + " " + visual + " stable WRAM interior",
                            dma(composed.gameboy).performanceNativeCgbWramReplaySpanLimit(1) > 0);
                    assertEquals(profile.id() + " " + visual + " positive direct count", 1,
                            diagnostics.snapshot().directLines());
                    assertEquals(profile.id() + " " + visual + " positive reject count", 0,
                            diagnostics.snapshot().rejectedLines());
                    assertEquals(profile.id() + " " + visual + " positive DMA blocker", 0L,
                            diagnostics.snapshot().blockers().get(PerformanceDiagnostics.Blocker.PPU_DMA).longValue());

                    ComponentState<Gameboy> scalarCheckpoint =
                            scalar.gameboy.captureStateWithoutTimeSource();
                    ComponentState<Gameboy> composedCheckpoint =
                            composed.gameboy.captureStateWithoutTimeSource();
                    assertEquals(profile.id() + " " + visual + " DMA state at admission",
                            dma(scalar.gameboy).captureState(), dma(composed.gameboy).captureState());

                    // The owner pair has the same PERFORMANCE admission policy and the same
                    // direct cursor snapshot. One owner runs exact scalar ticks; the other runs
                    // the trusted composed owner. Their canonical state must agree.
                    int elapsed = invokeDetailedOwner(composed.gameboy, 54);
                    assertEquals(profile.id() + " " + visual + " detailed owner commits full prefix", 54,
                            elapsed);
                    for (int i = 0; i < 54; i++) scalar.tick();
                    assertStateEquals(profile.id() + " " + visual + " detailed prefix state",
                            scalar.gameboy.captureStateWithoutTimeSource(),
                            composed.gameboy.captureStateWithoutTimeSource());
                    assertEquals(profile.id() + " " + visual + " detailed prefix DMA",
                            dma(scalar.gameboy).captureState(), dma(composed.gameboy).captureState());

                    // Restore the exact entry and repeat the prefix to cover cursor-state
                    // serialization without importing an incompatible execution-mode memento.
                    for (int requested = 8; requested <= 54; requested++) {
                        scalar.gameboy.restoreStateSilently(scalarCheckpoint);
                        composed.gameboy.restoreStateSilently(composedCheckpoint);
                        settleRestoredCursor(scalar, composed);
                        elapsed = invokeDetailedOwner(composed.gameboy, requested);
                        assertEquals(profile.id() + " " + visual
                                + " restored detailed prefix=" + requested, requested, elapsed);
                        for (int i = 0; i < requested; i++) scalar.tick();
                        assertStateEquals(profile.id() + " " + visual
                                        + " restored detailed state=" + requested,
                                scalar.gameboy.captureStateWithoutTimeSource(),
                                composed.gameboy.captureStateWithoutTimeSource());
                    }

                    // Complete a physical frame through the public owners. Compare only the
                    // observable frame stream because bulk and scalar accounting counters are
                    // owner-local.
                    int scalarFrames = scalar.events.cgbFrames;
                    int composedFrames = composed.events.cgbFrames;
                    long scalarFrameEvents = scalar.runTicks(70_224);
                    long composedFrameEvents = composed.runTicks(70_224);
                    assertEquals(profile.id() + " " + visual + " frame events", scalarFrameEvents,
                            composedFrameEvents);
                    assertTrue(profile.id() + " " + visual + " frame published", scalarFrameEvents > 0);
                    assertEquals(profile.id() + " " + visual + " scalar frame count",
                            scalarFrames + scalarFrameEvents, scalar.events.cgbFrames);
                    assertEquals(profile.id() + " " + visual + " composed frame count",
                            composedFrames + composedFrameEvents, composed.events.cgbFrames);
                    assertEquals(profile.id() + " " + visual + " frame hash",
                            scalar.events.frameHash, composed.events.frameHash);
                }
            }
        }
    }

    @Test
    public void selectedObjectsIncludingOffscreenRemainRejected() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            for (VisualCase visual : new VisualCase[]{VisualCase.OBJECTS, VisualCase.OFFSCREEN_OBJECT}) {
                try (Session scalar = session(ExecutionMode.PERFORMANCE, profile, visual);
                        Session composed = session(ExecutionMode.PERFORMANCE, profile, visual)) {
                    enableDoubleSpeed(scalar.gameboy);
                    enableDoubleSpeed(composed.gameboy);
                    scalar.gameboy.setPerformanceBatchingEnabled(false);
                    scalar.gameboy.getGpu().setPerformanceScanlineEnabled(true);
                    composed.gameboy.setPerformanceBatchingEnabled(true);
                    composed.gameboy.getGpu().setPerformanceScanlineEnabled(true);
                    advanceToLineDot(scalar, composed, 1, 60);
                    alignCpuOpcode(scalar, composed);
                    installRealFf46(scalar.gameboy, 0xff80, 0xc0);
                    installRealFf46(composed.gameboy, 0xff80, 0xc0);
                    PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(100_000);
                    composed.gameboy.setPerformanceDiagnostics(diagnostics);

                    reachMode3AfterOwnedDma(scalar, composed);
                    assertFalse(profile.id() + " " + visual + " selected object must reject direct line",
                            composed.gameboy.getGpu().isPerformanceScanlineCursorActive());
                    assertFalse(profile.id() + " " + visual + " scalar selected object direct line",
                            scalar.gameboy.getGpu().isPerformanceScanlineCursorActive());
                    assertEquals(profile.id() + " " + visual + " rejected count", 1,
                            diagnostics.snapshot().rejectedLines());
                    assertTrue(profile.id() + " " + visual + " DMA rejection attribution",
                            diagnostics.snapshot().blockers().get(PerformanceDiagnostics.Blocker.PPU_DMA) > 0);
                    assertEquals(profile.id() + " " + visual + " scalar setup DMA",
                            dma(scalar.gameboy).captureState(), dma(composed.gameboy).captureState());

                    for (int i = 0; i < 400; i++) {
                        assertEquals(profile.id() + " " + visual + " scalar continuation",
                                scalar.tick(), composed.tick());
                    }
                    assertStateEquals(profile.id() + " " + visual + " rejected scalar continuation",
                            scalar.gameboy.captureStateWithoutTimeSource(),
                            composed.gameboy.captureStateWithoutTimeSource());
                }
            }
        }
    }

    @Test
    public void realCpuRestartAndReleaseKeepDmaAndPpuStateExact() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            try (Session scalar = session(ExecutionMode.PERFORMANCE, profile, VisualCase.BACKGROUND);
                    Session composed = session(ExecutionMode.PERFORMANCE, profile, VisualCase.BACKGROUND)) {
                enableDoubleSpeed(scalar.gameboy);
                enableDoubleSpeed(composed.gameboy);
                scalar.gameboy.setPerformanceBatchingEnabled(false);
                scalar.gameboy.getGpu().setPerformanceScanlineEnabled(true);
                composed.gameboy.setPerformanceBatchingEnabled(true);
                composed.gameboy.getGpu().setPerformanceScanlineEnabled(true);
                advanceToLineDot(scalar, composed, 1, 60);
                alignCpuOpcode(scalar, composed);
                installRealFf46(scalar.gameboy, 0xff80, 0xc0);
                installRealFf46(composed.gameboy, 0xff80, 0xc0);
                reachMode3AfterOwnedDma(scalar, composed);
                assertTrue(profile.id() + " direct admission before restart",
                        composed.gameboy.getGpu().isPerformanceScanlineCursorActive());

                // A decoded JR from the first FF46 loop must not overwrite this redirection.
                // Align both CPU owners to an opcode boundary before issuing the restart.
                alignCpuOpcode(scalar, composed);
                installRealFf46(scalar.gameboy, 0xff90, 0xc1);
                installRealFf46(composed.gameboy, 0xff90, 0xc1);
                int guard = 0;
                boolean restartObserved = false;
                while (guard++ < 300) {
                    assertEquals(profile.id() + " restart frame", scalar.tick(), composed.tick());
                    Dma.DmaState scalarDma = (Dma.DmaState) dma(scalar.gameboy).captureState();
                    Dma.DmaState composedDma = (Dma.DmaState) dma(composed.gameboy).captureState();
                    assertEquals(profile.id() + " restart DMA state", scalarDma, composedDma);
                    if (composedDma.restarted()) {
                        restartObserved = true;
                        break;
                    }
                }
                assertTrue(profile.id() + " real CPU restart", restartObserved);
                assertEquals(profile.id() + " restart PPU state",
                        scalar.gameboy.getGpu().getMode(), composed.gameboy.getGpu().getMode());
                assertEquals(profile.id() + " restart quiet span must reject", 0,
                        composed.gameboy.getGpu().performanceNativeCgbOamReplayQuietSpanLimit(54));

                for (int i = 0; i < 900; i++) {
                    assertEquals(profile.id() + " release frame", scalar.tick(), composed.tick());
                    if ((i & 15) == 0) {
                        assertEquals(profile.id() + " release DMA state tick=" + i,
                                dma(scalar.gameboy).captureState(), dma(composed.gameboy).captureState());
                    }
                }
                assertFalse(profile.id() + " restart transfer release",
                        dma(composed.gameboy).isTransferInProgress());
                assertFalse(profile.id() + " restart PPU ownership release",
                        dma(composed.gameboy).ownsOamForPpu());
                assertStateEquals(profile.id() + " post-release exact continuation",
                        scalar.gameboy.captureStateWithoutTimeSource(),
                        composed.gameboy.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void renderedPerformanceSnapshotImportsIntoAccuracyWithoutNewArm() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            try (Session performance = session(ExecutionMode.PERFORMANCE, profile, VisualCase.BACKGROUND);
                    Session accuracy = session(ExecutionMode.ACCURACY, profile, VisualCase.BACKGROUND)) {
                enableDoubleSpeed(performance.gameboy);
                enableDoubleSpeed(accuracy.gameboy);
                performance.gameboy.setPerformanceBatchingEnabled(true);
                performance.gameboy.getGpu().setPerformanceScanlineEnabled(true);
                advanceToLineDot(accuracy, performance, 1, 60);
                alignCpuOpcode(accuracy, performance);
                installRealFf46(accuracy.gameboy, 0xff80, 0xc0);
                installRealFf46(performance.gameboy, 0xff80, 0xc0);
                reachMode3AfterOwnedDma(accuracy, performance);
                assertTrue(profile.id() + " import source direct line",
                        performance.gameboy.getGpu().isPerformanceScanlineCursorActive());

                // Import while the newly admitted cursor is still active: pixels have already
                // been composed from the mode-3 entry snapshot, but the handoff dot remains
                // owned by the cursor. ACCURACY must finish that exact tail without arming a
                // second direct line.
                ComponentState<Gameboy> rendered = performance.gameboy.captureStateWithoutTimeSource();
                accuracy.gameboy.restoreStateSilently(rendered);
                assertTrue(profile.id() + " imported snapshot cursor active",
                        accuracy.gameboy.getGpu().isPerformanceScanlineCursorActive());
                assertEquals(profile.id() + " imported DMA state",
                        dma(performance.gameboy).captureState(), dma(accuracy.gameboy).captureState());

                int guard = 0;
                while (performance.gameboy.getGpu().isPerformanceScanlineCursorActive()
                        && guard++ < 500) {
                    assertEquals(profile.id() + " imported rendered tail frame",
                            performance.tick(), accuracy.tick());
                    assertEquals(profile.id() + " imported tail cursor parity",
                            performance.gameboy.getGpu().isPerformanceScanlineCursorActive(),
                            accuracy.gameboy.getGpu().isPerformanceScanlineCursorActive());
                }
                assertTrue(profile.id() + " import source finished direct line", guard < 500);
                assertFalse(profile.id() + " accuracy cursor after imported tail",
                        accuracy.gameboy.getGpu().isPerformanceScanlineCursorActive());
                assertEquals(profile.id() + " imported tail mode",
                        performance.gameboy.getGpu().getMode(), accuracy.gameboy.getGpu().getMode());
                assertEquals(profile.id() + " imported tail line",
                        performance.gameboy.getGpu().getLine(), accuracy.gameboy.getGpu().getLine());
                assertEquals(profile.id() + " imported tail dot",
                        performance.gameboy.getGpu().getTicksInLine(), accuracy.gameboy.getGpu().getTicksInLine());
                assertEquals(profile.id() + " imported tail DMA state",
                        dma(performance.gameboy).captureState(), dma(accuracy.gameboy).captureState());

                // Continue ACCURACY alone through the next visible line entry and prove that
                // its ordinary policy never re-arms a PERFORMANCE cursor.
                int importedLine = accuracy.gameboy.getGpu().getLine();
                long importedCompletionCount = accuracy.gameboy.getGpu().getPerformanceScanlineLines();
                boolean nextLinePixelTransferReached = false;
                for (int i = 0; i < 1_200; i++) {
                    accuracy.tick();
                    assertFalse(profile.id() + " accuracy arm after import",
                            accuracy.gameboy.getGpu().isPerformanceScanlineCursorActive());
                    assertEquals(profile.id() + " accuracy completion count after import",
                            importedCompletionCount,
                            accuracy.gameboy.getGpu().getPerformanceScanlineLines());
                    if (accuracy.gameboy.getGpu().getLine() != importedLine
                            && accuracy.gameboy.getGpu().getMode() == Mode.PixelTransfer) {
                        nextLinePixelTransferReached = true;
                        break;
                    }
                }
                assertTrue(profile.id() + " imported snapshot reached following line mode 3",
                        nextLinePixelTransferReached);
                assertEquals(profile.id() + " imported snapshot completion count stable",
                        importedCompletionCount, accuracy.gameboy.getGpu().getPerformanceScanlineLines());
            }
        }
    }

    private static void reachMode3AfterOwnedDma(Session scalar, Session composed) {
        int guard = 0;
        while (!(composed.gameboy.getGpu().getMode() == Mode.PixelTransfer
                && dma(composed.gameboy).isTransferInProgress()) && guard++ < 1_000) {
            assertEquals("mode-3 setup frame", scalar.tick(), composed.tick());
        }
        assertTrue("real CPU FF46 did not reach owned mode-3 entry", guard < 1_000);
        assertTrue("mode-3 setup must own OAM", dma(composed.gameboy).ownsOamForPpu());
        assertTrue("mode-3 setup must be a native x2 WRAM interior",
                dma(composed.gameboy).performanceNativeCgbWramReplaySpanLimit(1) > 0);
        assertEquals("scalar/composed DMA setup",
                dma(scalar.gameboy).captureState(), dma(composed.gameboy).captureState());
    }

    private static void advanceToLineDot(Session scalar, Session composed,
                                          int targetLine, int targetDot) {
        int guard = 0;
        while (!(composed.gameboy.getGpu().getLine() == targetLine
                && composed.gameboy.getGpu().getMode() == Mode.OamSearch
                && composed.gameboy.getGpu().getTicksInLine() == targetDot)
                && guard++ < 200_000) {
            assertEquals("line setup frame", scalar.tick(), composed.tick());
        }
        assertTrue("did not reach the native x2 OAM setup point", guard < 200_000);
    }

    private static void alignCpuOpcode(Session first, Session second) {
        int guard = 0;
        while ((first.gameboy.getCpu().getState() != Cpu.State.OPCODE
                || second.gameboy.getCpu().getState() != Cpu.State.OPCODE)
                && guard++ < 64) {
            assertEquals("CPU opcode alignment frame", first.tick(), second.tick());
        }
        assertTrue("CPU owners did not reach an opcode boundary", guard < 64);
        assertEquals(Cpu.State.OPCODE, first.gameboy.getCpu().getState());
        assertEquals(Cpu.State.OPCODE, second.gameboy.getCpu().getState());
    }

    private static void settleRestoredCursor(Session first, Session second) {
        for (int i = 0; i < 9; i++) {
            assertEquals("restored cursor fixed-point frame", first.tick(), second.tick());
        }
        first.gameboy.getGpu().setPerformanceScanlineEnabled(true);
        second.gameboy.getGpu().setPerformanceScanlineEnabled(true);
    }

    private static void installRealFf46(Gameboy gameboy, int address, int sourcePage) {
        assertEquals("FF46 redirection must start at opcode boundary",
                Cpu.State.OPCODE, gameboy.getCpu().getState());
        // Keep a long HRAM INC BC lease after the real FF46 store. The detailed owner can then
        // prove every direct prefix through the 54-dot request without a JR boundary truncating
        // the CPU lease; the trailing loop still keeps the fixture alive for restart checks.
        int[] program = new int[56];
        program[0] = 0x3e;
        program[1] = sourcePage;
        program[2] = 0xe0;
        program[3] = 0x46;
        for (int i = 4; i < program.length - 2; i++) {
            program[i] = 0x03; // INC BC: a safe two-cycle register-only detailed instruction.
        }
        program[program.length - 2] = 0x18;
        program[program.length - 1] = 0xfe;
        for (int i = 0; i < program.length; i++) {
            gameboy.getAddressSpace().setByte(address + i, program[i]);
        }
        gameboy.getCpu().getRegisters().setPC(address);
    }

    private static void enableDoubleSpeed(Gameboy gameboy) throws Exception {
        gameboy.getSpeedMode().setByte(0xff4d, 1);
        Method onStop = gameboy.getSpeedMode().getClass().getDeclaredMethod("onStop");
        onStop.setAccessible(true);
        assertTrue((boolean) onStop.invoke(gameboy.getSpeedMode()));
        assertEquals(2, gameboy.getSpeedMode().getSpeedMode());
    }

    private static Dma dma(Gameboy gameboy) {
        try {
            Field field = Gameboy.class.getDeclaredField("dma");
            field.setAccessible(true);
            return (Dma) field.get(gameboy);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static int invokeDetailedOwner(Gameboy gameboy, int requested) throws Exception {
        Method method = Gameboy.class.getDeclaredMethod(
                "tryPerformanceNativeCgbDetailedPpuEpoch", long.class);
        method.setAccessible(true);
        return (int) method.invoke(gameboy, (long) requested);
    }

    private static Session session(ExecutionMode mode, HardwareProfile profile, VisualCase visual)
            throws Exception {
        EventDigest events = new EventDigest();
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        eventBus.register(events::onFrame, Display.GbcFrameReadyEvent.class);
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0;
        image[0x102] = 1;
        image[0x143] = (byte) 0x80;
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(mode)
                .setPlayerInputSource(PlayerInputSource.RELEASED)
                .setRtcTimeSource(() -> 0L)
                .setSupportBatterySave(false)
                .build();
        gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        prepareVisualFixture(gameboy, visual);
        return new Session(gameboy, eventBus, events);
    }

    private static void prepareVisualFixture(Gameboy gameboy, VisualCase visual) {
        Gpu gpu = gameboy.getGpu();
        gpu.setByte(0xff40, 0);
        gpu.setByte(0xff41, 0x08);
        gpu.setByte(0xff45, 0xff);
        for (int row = 0; row < 8; row++) {
            gpu.setByte(0x8020 + row * 2, 0x18 ^ row * 3);
            gpu.setByte(0x8021 + row * 2, 0x81 ^ row * 5);
        }
        for (int i = 0; i < 0x400; i++) {
            gpu.setByte(0x9800 + i, (i * 3 + 2) & 0xff);
            gpu.setByte(0x9c00 + i, (i * 5 + 1) & 0xff);
        }
        for (int i = 0; i < 0xa0; i++) {
            gpu.setByte(0xfe00 + i, 0);
        }
        configureNativePalette(gpu);
        if (visual == VisualCase.WINDOW) {
            gpu.setByte(0xff4a, 1);
            gpu.setByte(0xff4b, 7);
            gpu.setByte(0xff40, 0xb1);
        } else if (visual == VisualCase.OBJECTS || visual == VisualCase.OFFSCREEN_OBJECT) {
            int x = visual == VisualCase.OBJECTS ? 8 : 0;
            gpu.setByte(0xfe00, 17);
            gpu.setByte(0xfe01, x);
            gpu.setByte(0xfe02, 1);
            gpu.setByte(0xfe03, 0);
            gpu.setByte(0xff40, 0x93);
        } else {
            gpu.setByte(0xff40, 0x91);
        }
    }

    private static void configureNativePalette(Gpu gpu) {
        for (int palette = 0; palette < 8; palette++) {
            gpu.setByte(0xff68, 0x80 | (palette << 3));
            for (int color = 0; color < 4; color++) {
                int value = (palette * 0x13 + color * 0x2d + 0x1f) & 0x7fff;
                gpu.setByte(0xff69, value & 0xff);
                gpu.setByte(0xff69, value >>> 8);
            }
        }
    }

    private enum VisualCase { BACKGROUND, WINDOW, OBJECTS, OFFSCREEN_OBJECT }

    private static final class Session implements AutoCloseable {
        private final Gameboy gameboy;
        private final EventBusImpl eventBus;
        private final EventDigest events;

        private Session(Gameboy gameboy, EventBusImpl eventBus, EventDigest events) {
            this.gameboy = gameboy;
            this.eventBus = eventBus;
            this.events = events;
        }

        private boolean tick() {
            events.masterTicks++;
            return gameboy.tick();
        }

        private long runTicks(long ticks) {
            events.masterTicks += ticks;
            return gameboy.runTicks(ticks);
        }

        @Override
        public void close() {
            gameboy.closeSilently();
            eventBus.close();
        }
    }

    private static final class EventDigest {
        private int cgbFrames;
        private long frameHash = 0xcbf29ce484222325L;
        private long masterTicks;

        private void onFrame(Display.GbcFrameReadyEvent event) {
            cgbFrames++;
            for (int pixel : event.pixels()) {
                frameHash ^= pixel & 0xffff_ffffL;
                frameHash *= 0x100000001b3L;
            }
        }
    }
}
