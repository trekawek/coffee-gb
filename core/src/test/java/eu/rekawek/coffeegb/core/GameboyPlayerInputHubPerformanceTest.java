package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
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
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(mode)
                .setSupportBatterySave(false)
                .setPlayerInputSource(inputSource)
                .build();
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
