package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** End-to-end coverage for the live PlayerInputHub path used by Android adapters. */
public final class GameboyPlayerInputHubPerformanceTest {

    @Test
    public void performanceHubMatchesScalarAtRandomChunkBoundariesAndRestores() throws Exception {
        for (boolean cgb : new boolean[]{false, true}) {
            for (boolean held : new boolean[]{false, true}) {
                PlayerInputHub performanceHub = new PlayerInputHub();
                PlayerInputHub.SourceHandle performanceSource = performanceHub.openSource(0);
                AtomicReference<PlayerInputSnapshot> scalarInput = new AtomicReference<>(
                        PlayerInputSnapshot.released());
                if (held) {
                    Set<Button> initial = Set.of(Button.A, Button.RIGHT);
                    performanceSource.update(initial);
                    scalarInput.set(snapshot(initial));
                }
                try (Gameboy performance = session(cgb, ExecutionMode.PERFORMANCE, performanceHub);
                        Gameboy scalar = session(cgb, ExecutionMode.PERFORMANCE, scalarInput::get)) {
                    Random random = new Random(0x4f3d_21a7L + (cgb ? 1 : 0)
                            + (held ? 7 : 0));
                    for (int chunkIndex = 0; chunkIndex < 90; chunkIndex++) {
                        int chunk = 1 + random.nextInt(97);
                        long scalarFrames = 0;
                        for (int tick = 0; tick < chunk; tick++) {
                            if (scalar.runTicks(1) != 0) {
                                scalarFrames++;
                            }
                        }
                        assertEquals("frame events cgb=" + cgb + ", held=" + held
                                        + ", chunk=" + chunkIndex,
                                scalarFrames, performance.runTicks(chunk));
                        ComponentState<Gameboy> scalarState = scalar.captureState();
                        ComponentState<Gameboy> performanceState = performance.captureState();
                        assertEquals("state cgb=" + cgb + ", held=" + held
                                        + ", chunk=" + chunkIndex + " "
                                        + componentHashes(scalarState, performanceState),
                                stateHash(scalarState), stateHash(performanceState));
                        assertRasterEquivalent(scalar, performance,
                                "cgb=" + cgb + ", held=" + held + ", chunk=" + chunkIndex);
                    }
                    assertTrue("hub performance path had no bulk coverage cgb=" + cgb
                                    + ", held=" + held,
                            performance.getPerformanceBulkTicks() > 0);

                    ComponentState<Gameboy> saved = performance.captureState();
                    int savedHash = stateHash(saved);
                    performance.runTicks(73);
                    performance.restoreState(saved);
                    assertEquals("capture/restore cgb=" + cgb + ", held=" + held,
                            savedHash, stateHash(performance.captureState()));

                    for (int tick = 0; tick < 32; tick++) {
                        long scalarFrame = scalar.runTicks(1);
                        assertEquals("restore frame cgb=" + cgb + ", held=" + held,
                                scalarFrame != 0, performance.runTicks(1) != 0);
                        assertEquals("restore state cgb=" + cgb + ", held=" + held,
                                stateHash(scalar.captureState()), stateHash(performance.captureState()));
                        assertRasterEquivalent(scalar, performance,
                                "restore cgb=" + cgb + ", held=" + held);
                    }
                }
            }
        }
    }

    @Test
    public void cgbBulkTailPublishesGpuTimingBeforeImmediateFf55Start() throws Exception {
        for (int tail = 1; tail <= 3; tail++) {
            PlayerInputHub hub = new PlayerInputHub();
            hub.openSource(0);
            AtomicReference<PlayerInputSnapshot> scalarInput = new AtomicReference<>(
                    PlayerInputSnapshot.released());
            try (Gameboy performance = session(true, ExecutionMode.PERFORMANCE, hub);
                    Gameboy scalar = session(true, ExecutionMode.PERFORMANCE, scalarInput::get)) {
                // Find a synchronized endpoint where the immediately preceding call is exactly
                // one all-subsystem packet of the requested 1–3 dots. The scalar oracle follows
                // the same PERFORMANCE renderer lifecycle but has a custom source, so its
                // all-subsystem packet horizon is zero.
                boolean allBulk = false;
                for (int attempt = 0; attempt < 4_000 && !allBulk; attempt++) {
                    long before = performance.getPerformanceBulkTicks();
                    performance.runTicks(tail);
                    for (int i = 0; i < tail; i++) {
                        scalar.runTicks(1);
                    }
                    allBulk = performance.getPerformanceBulkTicks() - before == tail;
                }
                assertTrue("CGB setup did not exercise an all-bulk " + tail + "-dot tail",
                        allBulk);

                // Start GDMA immediately after the proven packet tail. The final GPU timing
                // publication must make FF55 observe the same request state as scalar execution.
                startOneBlockGdma(performance);
                startOneBlockGdma(scalar);
                performance.runTicks(1);
                scalar.runTicks(1);

                assertEquals("FF55 start after " + tail + "-dot packet tail",
                        scalar.getAddressSpace().getByte(0xff55),
                        performance.getAddressSpace().getByte(0xff55));
                assertEquals("HDMA timing latch after " + tail + "-dot packet tail",
                        stateHash(scalar.getHdma().captureState()),
                        stateHash(performance.getHdma().captureState()));
            }
        }
    }

    @Test
    public void settledHaltMatchesScalarDmgAndCgbWithBulkCoverage() throws Exception {
        for (boolean cgb : new boolean[]{false, true}) {
            PlayerInputHub hub = new PlayerInputHub();
            hub.openSource(0);
            try (Gameboy performance = haltSession(cgb, hub);
                    // A non-hub source deliberately makes Joypad's horizon zero, retaining the
                    // PERFORMANCE components while forcing the scalar scheduler as the oracle.
                    Gameboy scalar = haltSession(cgb,
                            () -> PlayerInputSnapshot.released())) {
                for (int chunk = 0; chunk < 120; chunk++) {
                    performance.runTicks(100);
                    scalar.runTicks(100);
                }

                assertEquals("HALT state cgb=" + cgb, Cpu.State.HALTED,
                        performance.getCpu().getState());
                assertEquals("scalar HALT state cgb=" + cgb, Cpu.State.HALTED,
                        scalar.getCpu().getState());
                assertEquals("HALT differential cgb=" + cgb + " "
                                + componentHashes(scalar.captureState(), performance.captureState()),
                        stateHash(scalar.captureState()), stateHash(performance.captureState()));
                assertRasterEquivalent(scalar, performance, "HALT cgb=" + cgb);
                assertTrue("stable HALT had no substantial bulk coverage cgb=" + cgb
                                + " ticks=" + performance.getPerformanceBulkTicks(),
                        performance.getPerformanceBulkTicks() > 1_000);
            }
        }
    }

    @Test
    public void settledHaltWakeEdgesMatchScalarForOneToThreeDotTails() throws Exception {
        for (boolean cgb : new boolean[]{false, true}) {
            for (WakeSource wakeSource : WakeSource.values()) {
                for (int tail = 1; tail <= 3; tail++) {
                    PlayerInputHub hub = new PlayerInputHub();
                    hub.openSource(0);
                    try (Gameboy performance = haltSession(cgb, hub);
                            Gameboy scalar = haltSession(cgb,
                                    () -> PlayerInputSnapshot.released())) {
                        settleHalt(performance, scalar, cgb, wakeSource, tail);
                        armWakeSource(performance, wakeSource);
                        armWakeSource(scalar, wakeSource);
                        performance.resetPerformanceBulkCounters();
                        scalar.resetPerformanceBulkCounters();

                        boolean sawWholeTail = false;
                        int elapsed = 0;
                        while (performance.getCpu().getState() == Cpu.State.HALTED
                                && elapsed < 2_000) {
                            long bulkBefore = performance.getPerformanceBulkTicks();
                            long performanceFrames = performance.runTicks(tail);
                            long scalarFrames = runScalarTicks(scalar, tail);
                            sawWholeTail |= performance.getPerformanceBulkTicks() - bulkBefore
                                    == tail;
                            assertEquals(context(cgb, wakeSource, tail) + " frame events",
                                    scalarFrames, performanceFrames);
                            assertEquals(context(cgb, wakeSource, tail) + " CPU wake tick",
                                    scalar.getCpu().getState(), performance.getCpu().getState());
                            elapsed += tail;
                        }

                        assertTrue(context(cgb, wakeSource, tail) + " did not wake",
                                performance.getCpu().getState() != Cpu.State.HALTED);
                        assertTrue(context(cgb, wakeSource, tail)
                                        + " never committed a whole caller tail",
                                sawWholeTail);

                        // Continue past the wake boundary. Inactive OAM DMA must leave its
                        // HALT-pause latch on the same running-CPU value as the scalar machine.
                        assertEquals(context(cgb, wakeSource, tail) + " post-wake frame events",
                                runScalarTicks(scalar, 8), performance.runTicks(8));
                        ComponentState<Gameboy> scalarState = scalar.captureState();
                        ComponentState<Gameboy> performanceState = performance.captureState();
                        assertEquals(context(cgb, wakeSource, tail) + " DMA pause latch",
                                recordComponentHash(scalarState, "dmaMemento"),
                                recordComponentHash(performanceState, "dmaMemento"));
                        assertStateEquivalent(scalarState, performanceState,
                                context(cgb, wakeSource, tail));
                        assertRasterEquivalent(scalar, performance,
                                context(cgb, wakeSource, tail));
                    }
                }
            }
        }
    }

    private static Gameboy session(boolean cgb, ExecutionMode mode, PlayerInputHub hub)
            throws Exception {
        return session(cgb, mode, (PlayerInputSource) hub);
    }

    private static Gameboy session(boolean cgb, ExecutionMode mode, PlayerInputSource inputSource)
            throws Exception {
        byte[] image = new byte[0x8000];
        // Select both JOYP rows once, then hold a stable CPU loop. Android's physical hub is
        // therefore observable through the same filtered P10-P13 path as a real game.
        image[0x100] = 0x3e; // LD A,0
        image[0x101] = 0;
        image[0x102] = (byte) 0xe0; // LDH (FF00),A
        image[0x103] = 0;
        image[0x104] = 0x18; // JR $0104
        image[0x105] = (byte) 0xfe;
        image[0x143] = (byte) (cgb ? 0x80 : 0);
        image[0x147] = 0;
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(mode)
                .setSupportBatterySave(false)
                .setPlayerInputSource(inputSource)
                .build();
        // Use the real disconnected cable endpoint used by the controller link path.  Its
        // PERFORMANCE capability is intentionally distinct from the NULL endpoint and must
        // remain quiet only while it has no peer.
        gameboy.init(new EventBusImpl(null, null, false), new Peer2PeerSerialEndpoint(), null);
        return gameboy;
    }

    private static Gameboy haltSession(boolean cgb, PlayerInputSource inputSource) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x76; // HALT with no interrupt sources enabled
        image[0x101] = 0x00;
        image[0x143] = (byte) (cgb ? 0x80 : 0);
        image[0x147] = 0;
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
        gameboy.init(new EventBusImpl(null, null, false), new Peer2PeerSerialEndpoint(), null);
        return gameboy;
    }

    private static void settleHalt(Gameboy performance, Gameboy scalar, boolean cgb,
                                   WakeSource wakeSource, int tail) {
        performance.runTicks(128);
        runScalarTicks(scalar, 128);
        assertEquals(context(cgb, wakeSource, tail) + " candidate did not settle in HALT",
                Cpu.State.HALTED, performance.getCpu().getState());
        assertEquals(context(cgb, wakeSource, tail) + " oracle did not settle in HALT",
                Cpu.State.HALTED, scalar.getCpu().getState());

        // Arm mode-2 STAT only from a deasserted source so the tested interrupt is the next
        // PPU edge, rather than the register write itself.
        if (wakeSource == WakeSource.STAT) {
            int guard = 0;
            while (performance.getGpu().getVisibleStatMode() == 2 && guard++ < 100) {
                performance.runTicks(1);
                scalar.runTicks(1);
            }
            assertTrue(context(cgb, wakeSource, tail) + " STAT source did not deassert",
                    performance.getGpu().getVisibleStatMode() != 2);
        }
        assertStateEquivalent(scalar.captureState(), performance.captureState(),
                context(cgb, wakeSource, tail) + " pre-arm");
    }

    private static void armWakeSource(Gameboy gameboy, WakeSource wakeSource) {
        var bus = gameboy.getAddressSpace();
        bus.setByte(0xffff, 0);
        bus.setByte(0xff0f, 0);
        if (wakeSource == WakeSource.TIMER) {
            bus.setByte(0xff07, 0);
            bus.setByte(0xff04, 0);
            bus.setByte(0xff06, 0);
            bus.setByte(0xff05, 0xff);
            bus.setByte(0xff07, 0x05);
            bus.setByte(0xffff, 0x04);
        } else {
            bus.setByte(0xff41, 0x20);
            bus.setByte(0xff0f, 0);
            bus.setByte(0xffff, 0x02);
        }
    }

    private static long runScalarTicks(Gameboy scalar, int ticks) {
        long frameEvents = 0;
        for (int tick = 0; tick < ticks; tick++) {
            frameEvents += scalar.runTicks(1);
        }
        return frameEvents;
    }

    private static String context(boolean cgb, WakeSource wakeSource, int tail) {
        return "cgb=" + cgb + ", wake=" + wakeSource + ", tail=" + tail;
    }

    private static void assertStateEquivalent(ComponentState<Gameboy> scalar,
                                              ComponentState<Gameboy> performance,
                                              String context) {
        int scalarHash = stateHash(scalar);
        int performanceHash = stateHash(performance);
        if (scalarHash != performanceHash) {
            assertEquals(context + " " + componentHashes(scalar, performance),
                    scalarHash, performanceHash);
        }
    }

    private static int recordComponentHash(Object value, String componentName) {
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            if (!component.getName().equals(componentName)) {
                continue;
            }
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                return stateHash(accessor.invoke(value));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Cannot hash state component " + component, e);
            }
        }
        throw new AssertionError("Missing record component " + componentName);
    }

    private enum WakeSource {
        TIMER,
        STAT
    }

    private static PlayerInputSnapshot snapshot(Set<Button> buttons) {
        ArrayList<Set<Button>> players = new ArrayList<>();
        players.add(buttons);
        players.add(Set.of());
        players.add(Set.of());
        players.add(Set.of());
        return PlayerInputSnapshot.of(players);
    }

    private static void startOneBlockGdma(Gameboy gameboy) {
        var bus = gameboy.getAddressSpace();
        bus.setByte(0xff51, 0);
        bus.setByte(0xff52, 0);
        bus.setByte(0xff53, 0);
        bus.setByte(0xff54, 0);
        bus.setByte(0xff55, 0);
    }

    /** Canonical recursive hash for record mementos, including primitive/object arrays. */
    private static int stateHash(Object value) {
        if (value == null) {
            return 0;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int hash = 1;
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                hash = 31 * hash + stateHash(Array.get(value, i));
            }
            return hash;
        }
        if (type.isRecord()) {
            int hash = type.getName().hashCode();
            for (RecordComponent component : type.getRecordComponents()) {
                try {
                    if (isHostOnlyState(type, component.getName())) {
                        continue;
                    }
                    hash = 31 * hash + component.getName().hashCode();
                    var accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    hash = 31 * hash + stateHash(accessor.invoke(value));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("Cannot hash state component " + component, e);
                }
            }
            return hash;
        }
        return value.hashCode();
    }

    private static boolean isHostOnlyState(Class<?> type, String componentName) {
        String name = type.getName();
        if (name.equals("eu.rekawek.coffeegb.core.sound.Sound$SoundState")
                || name.equals("eu.rekawek.coffeegb.core.sound.Sound$SoundMemento")) {
            return switch (componentName) {
                case "buffer", "i", "performanceSamplePhase", "audioDecimation" -> true;
                default -> false;
            };
        }
        if (name.equals("eu.rekawek.coffeegb.core.gpu.Gpu$GpuState")
                || name.equals("eu.rekawek.coffeegb.core.gpu.Gpu$GpuMemento")) {
            return switch (componentName) {
                case "performanceWindowLineCounter", "performanceScanlineCursor",
                        "performanceScanlineLine", "performanceScanlineEndTick",
                        "pixelTransferPhaseMemento", "pixelMachineMemento" -> true;
                default -> false;
            };
        }
        return name.equals("eu.rekawek.coffeegb.core.gpu.VRamTransfer$VRamTransferState")
                || name.equals("eu.rekawek.coffeegb.core.gpu.VRamTransfer$VRamTransferMemento");
    }

    private static void assertRasterEquivalent(Gameboy scalar, Gameboy performance,
                                               String context) {
        assertEquals(context + " LY", scalar.getGpu().getLine(), performance.getGpu().getLine());
        assertEquals(context + " line ticks", scalar.getGpu().getTicksInLine(),
                performance.getGpu().getTicksInLine());
        assertEquals(context + " mode", scalar.getGpu().getMode(), performance.getGpu().getMode());
        assertEquals(context + " LCDC", scalar.getGpu().getByte(0xff40),
                performance.getGpu().getByte(0xff40));
        assertEquals(context + " STAT", scalar.getGpu().getByte(0xff41),
                performance.getGpu().getByte(0xff41));
    }

    private static String componentHashes(Object left, Object right) {
        if (!left.getClass().isRecord() || !right.getClass().isRecord()) {
            return "";
        }
        StringBuilder result = new StringBuilder("components[");
        for (RecordComponent component : left.getClass().getRecordComponents()) {
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                int leftHash = stateHash(accessor.invoke(left));
                int rightHash = stateHash(accessor.invoke(right));
                if (leftHash != rightHash) {
                    result.append(component.getName()).append('=').append(leftHash)
                            .append('/').append(rightHash).append(',');
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        return result.append(']').toString();
    }
}
