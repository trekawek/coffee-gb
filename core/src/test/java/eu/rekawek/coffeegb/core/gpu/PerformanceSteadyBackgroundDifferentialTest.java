package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.debug.DebugInstrumentation;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Synthetic differential coverage for the DMG/MGB performance timing cursor.
 *
 * <p>The comparison is made against an ordinary ACCURACY session, including the complete
 * record-shaped machine state. No external ROM, save, title, or host path is involved.</p>
 */
public final class PerformanceSteadyBackgroundDifferentialTest {

    private static final int STEADY_LINE = 1;

    private static final List<HardwareProfile> SUPPORTED_PROFILES =
            List.of(HardwareProfileRegistry.DMG, HardwareProfileRegistry.MGB);

    @Test
    public void uninterruptedSpanMatchesAccuracyForEveryFineScx() throws Exception {
        for (HardwareProfile profile : SUPPORTED_PROFILES) {
            for (int scx = 0; scx < 8; scx++) {
                try (Session accuracy = new Session(ExecutionMode.ACCURACY, profile, scx);
                        Session performance = new Session(ExecutionMode.PERFORMANCE, profile, scx)) {
                    enterSteadyLine(accuracy);
                    enterSteadyLine(performance);

                    tickPair(accuracy, performance);
                    assertTrue(lazyCursor(performance.gpu));
                    while (accuracy.gpu.getMode() == Mode.PixelTransfer) {
                        tickPair(accuracy, performance);
                    }

                    assertEquals(Mode.HBlank, performance.gpu.getMode());
                    assertEquals(249 + scx, performance.gpu.getTicksInLine());
                    assertFalse(lazyCursor(performance.gpu));
                    assertSameState(accuracy, performance,
                            profile.id() + " uninterrupted scx=" + scx);
                }
            }
        }
    }

    @Test
    public void steadySpanMatchesAccuracyAtArmMidLineAndNextLineBoundary() throws Exception {
        for (HardwareProfile profile : SUPPORTED_PROFILES) {
            for (int scx = 0; scx < 8; scx++) {
                try (Session accuracy = new Session(ExecutionMode.ACCURACY, profile, scx);
                        Session performance = new Session(ExecutionMode.PERFORMANCE, profile, scx)) {
                    enterSteadyLine(accuracy);
                    enterSteadyLine(performance);

                    assertEquals(80, accuracy.gpu.getTicksInLine());
                    assertEquals(80, performance.gpu.getTicksInLine());
                    tickPair(accuracy, performance);
                    assertFalse(lazyCursor(accuracy.gpu));
                    assertTrue(lazyCursor(performance.gpu));
                    assertSameState(accuracy, performance, profile.id() + " arm scx=" + scx);

                    for (int i = 0; i < 24; i++) {
                        tickPair(accuracy, performance);
                    }
                    // The arm-point state comparison above intentionally materialized the cursor;
                    // subsequent dots therefore exercise the ordinary continuation after a safe
                    // observation boundary.
                    assertFalse(lazyCursor(performance.gpu));
                    assertSameState(accuracy, performance, profile.id() + " mid-line scx=" + scx);

                    while (accuracy.gpu.getMode() == Mode.PixelTransfer) {
                        tickPair(accuracy, performance);
                    }
                    assertEquals(Mode.HBlank, accuracy.gpu.getMode());
                    assertEquals(Mode.HBlank, performance.gpu.getMode());
                    assertEquals(249 + scx, accuracy.gpu.getTicksInLine());
                    assertEquals(accuracy.gpu.getTicksInLine(), performance.gpu.getTicksInLine());
                    assertFalse(lazyCursor(performance.gpu));
                    assertSameState(accuracy, performance, profile.id() + " mode0 scx=" + scx);

                    while (accuracy.gpu.getLine() == STEADY_LINE) {
                        tickPair(accuracy, performance);
                    }
                    assertEquals(0, accuracy.gpu.getTicksInLine());
                    assertEquals(0, performance.gpu.getTicksInLine());
                    assertSameState(accuracy, performance,
                            profile.id() + " next-line-start scx=" + scx);
                }
            }
        }
    }

    @Test
    public void gpuWriteMaterializesTheSpanBeforeChangingCanonicalState() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.DMG, 3);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 3)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            for (int i = 0; i < 25; i++) {
                tickPair(accuracy, performance);
            }
            assertTrue(lazyCursor(performance.gpu));

            accuracy.gpu.setByte(0xff43, 6);
            performance.gpu.setByte(0xff43, 6);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "SCX write materialization");
            for (int i = 0; i < 420; i++) {
                tickPair(accuracy, performance);
            }
            assertSameState(accuracy, performance, "SCX write invalidation");
        }
    }

    @Test
    public void retainedMutableAliasMaterializesAndPermanentlyFallsBack() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.DMG, 2);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 2)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));

            GpuRegisterValues accuracyRegisters = accuracy.gpu.getRegisters();
            GpuRegisterValues performanceRegisters = performance.gpu.getRegisters();
            assertFalse(lazyCursor(performance.gpu));
            accuracyRegisters.put(GpuRegister.SCX, 5);
            performanceRegisters.put(GpuRegister.SCX, 5);

            while (accuracy.gpu.getLine() == STEADY_LINE
                    || accuracy.gpu.getMode() != Mode.PixelTransfer
                    || accuracy.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "retained mutable register alias");
        }
    }

    @Test
    public void debugRetirementMaterializesAndBlocksUntilDetached() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.DMG, 2);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 2)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));

            performance.gameboy.enableDebugRetirementTracking();
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "retirement attach materialization");

            // Keep retirement observation attached through the next eligible line. The
            // canonical state must continue to match while the cursor stays scalar-deopted.
            while (performance.gpu.getLine() == STEADY_LINE
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "retirement blocks re-arm");

            performance.gameboy.disableDebugRetirementTracking();
            while (performance.gpu.getLine() == STEADY_LINE + 1
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "retirement detach re-enables");
        }
    }

    @Test
    public void nonPpuInstrumentationSharesObservationBlockerWithRetirement() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.DMG, 2);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 2)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));

            DebugInstrumentation instrumentation = cpuOnlyInstrumentation();
            assertFalse(instrumentation.requiresPpuHooks());
            performance.gameboy.updateDebugInstrumentation(instrumentation, 0);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "non-PPU instrumentation attach");

            while (performance.gpu.getLine() == STEADY_LINE
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "non-PPU instrumentation blocks re-arm");

            // Both observation sources are aggregated: removing instrumentation alone must not
            // re-enable the cursor while retirement tracking remains attached.
            performance.gameboy.enableDebugRetirementTracking();
            performance.gameboy.updateDebugInstrumentation(null, 0);
            while (performance.gpu.getLine() == STEADY_LINE + 1
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "retirement keeps blocker after detach");

            performance.gameboy.disableDebugRetirementTracking();
            while (performance.gpu.getLine() == STEADY_LINE + 2
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "both observations detached");
        }
    }

    @Test
    public void disabledWindowInsertionLatchFailsClosed() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.DMG, 0);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 0)) {
            armWindowMasterThenDisable(accuracy);
            armWindowMasterThenDisable(performance);
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);

            tickPair(accuracy, performance);
            assertFalse(lazyCursor(performance.gpu));
            for (int i = 0; i < 420; i++) {
                tickPair(accuracy, performance);
            }
            assertSameState(accuracy, performance, "disabled-window insertion latch");
        }
    }

    @Test
    public void performanceCheckpointRestoresIntoBothModesThroughFrameAndAudioEdges()
            throws Exception {
        try (Session source = new Session(ExecutionMode.PERFORMANCE,
                HardwareProfileRegistry.DMG, 5);
                Session accuracy = new Session(ExecutionMode.ACCURACY,
                        HardwareProfileRegistry.DMG, 5);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 5)) {
            enterSteadyLine(source);
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(source, accuracy);
            tickPair(source, performance);
            for (int i = 0; i < 22; i++) {
                source.gameboy.tick();
                accuracy.gameboy.tick();
                performance.gameboy.tick();
            }
            assertTrue(lazyCursor(source.gpu));

            ComponentState<Gameboy> saved = source.gameboy.captureStateWithoutTimeSource();
            accuracy.gameboy.restoreStateSilently(saved);
            performance.gameboy.restoreStateSilently(saved);
            assertFalse(lazyCursor(accuracy.gpu));
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "restored checkpoint");

            // restoreStateSilently deliberately suppresses the abandoned partial frame. Two
            // physical frames reach the first unsuppressed visible frame and at least one audio
            // buffer, exercising display and APU event payloads as well as the PPU line edge.
            for (int i = 0; i < 145_000; i++) {
                tickPair(accuracy, performance);
            }
            assertSameState(accuracy, performance, "cross-mode continuation");
            assertEquals(accuracy.gameboy.getAddressSpace().getByte(0xff41),
                    performance.gameboy.getAddressSpace().getByte(0xff41));
            assertEquals(accuracy.gameboy.getAddressSpace().getByte(0xff0f),
                    performance.gameboy.getAddressSpace().getByte(0xff0f));
            assertEquals(accuracy.gpu.getLine(), performance.gpu.getLine());
            assertEquals(accuracy.gpu.getTicksInLine(), performance.gpu.getTicksInLine());
            assertEquals(accuracy.gpu.getMode(), performance.gpu.getMode());
            assertEquals(accuracy.events.frameCount, performance.events.frameCount);
            assertEquals(accuracy.events.frameHash, performance.events.frameHash);
            assertEquals(accuracy.events.audioCount, performance.events.audioCount);
            assertEquals(accuracy.events.audioHash, performance.events.audioHash);
            assertTrue("synthetic run must publish a visible frame",
                    accuracy.events.frameCount > 0);
            assertTrue("synthetic run must publish an audio buffer",
                    accuracy.events.audioCount > 0);
        }
    }

    private static void enterSteadyLine(Session session) {
        while (session.gpu.getLine() != STEADY_LINE
                || session.gpu.getMode() != Mode.PixelTransfer) {
            session.gameboy.tick();
        }
    }

    private static void armWindowMasterThenDisable(Session session) {
        session.gpu.setByte(0xff40, 0x00);
        session.gpu.setByte(0xff4a, 0x00);
        session.gpu.setByte(0xff4b, 0x07);
        session.gpu.setByte(0xff40, 0xb1);
        while (session.gpu.getLine() != 0
                || session.gpu.getMode() != Mode.HBlank
                || session.gpu.getTicksInLine() < 300) {
            session.gameboy.tick();
        }
        session.gpu.setByte(0xff40, 0x91);
    }

    private static void tickPair(Session left, Session right) {
        assertEquals(left.gameboy.tick(), right.gameboy.tick());
    }

    private static void assertSameState(Session left, Session right, String point)
            throws Exception {
        assertEquals(point, stateDigest(left.gameboy), stateDigest(right.gameboy));
        assertEquals(point + " STAT", left.gameboy.getAddressSpace().getByte(0xff41),
                right.gameboy.getAddressSpace().getByte(0xff41));
        assertEquals(point + " IF", left.gameboy.getAddressSpace().getByte(0xff0f),
                right.gameboy.getAddressSpace().getByte(0xff0f));
    }

    private static boolean lazyCursor(Gpu gpu) throws Exception {
        var cursor = Gpu.class.getDeclaredField("steadyTimingCursor");
        cursor.setAccessible(true);
        return cursor.getBoolean(gpu);
    }

    private static byte[] syntheticRom() {
        byte[] rom = new byte[0x8000];
        rom[0x100] = (byte) 0xc3; // JP $0100: a stable CPU-side workload with no MMIO writes
        rom[0x101] = 0;
        rom[0x102] = 1;
        rom[0x147] = 0;
        return rom;
    }

    private static DebugInstrumentation cpuOnlyInstrumentation() {
        DebugInstrumentation instrumentation = new DebugInstrumentation(
                2,
                32,
                8,
                EnumSet.of(DebugBreakpointKind.PROGRAM_COUNTER),
                EnumSet.of(TraceCategory.CPU));
        instrumentation.configureTrace(new TraceConfiguration(
                8, EnumSet.of(TraceCategory.CPU)));
        return instrumentation;
    }

    private static long stateDigest(Gameboy gameboy) throws Exception {
        return digest(gameboy.captureStateWithoutTimeSource());
    }

    private static long digest(Object value) throws Exception {
        Hasher hasher = new Hasher();
        visit(value, hasher, new IdentityHashMap<>());
        return hasher.value;
    }

    private static void visit(Object value, Hasher hasher, IdentityHashMap<Object, Boolean> seen)
            throws Exception {
        if (value == null) {
            hasher.mix(0);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            hasher.mix(type.getName().hashCode());
            int length = Array.getLength(value);
            hasher.mix(length);
            for (int i = 0; i < length; i++) {
                Object child = Array.get(value, i);
                if (type.getComponentType().isPrimitive()) {
                    hasher.mix(child.hashCode());
                } else {
                    visit(child, hasher, seen);
                }
            }
            return;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof String || value instanceof Enum<?>) {
            hasher.mix(type.getName().hashCode());
            hasher.mix(value.hashCode());
            return;
        }
        if (value instanceof List<?> list) {
            hasher.mix(type.getName().hashCode());
            hasher.mix(list.size());
            for (Object child : list) {
                visit(child, hasher, seen);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            hasher.mix(type.getName().hashCode());
            hasher.mix(map.size());
            for (var entry : map.entrySet()) {
                visit(entry.getKey(), hasher, seen);
                visit(entry.getValue(), hasher, seen);
            }
            return;
        }
        if (!type.isRecord()) {
            throw new AssertionError("Unexpected machine-state shape: " + type.getName());
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            hasher.mix(0x51ed270b);
            return;
        }
        hasher.mix(type.getName().hashCode());
        for (RecordComponent component : type.getRecordComponents()) {
            hasher.mix(component.getName().hashCode());
            var accessor = component.getAccessor();
            if (!accessor.trySetAccessible()) {
                throw new AssertionError("State accessor is not accessible: " + component);
            }
            try {
                visit(accessor.invoke(value), hasher, seen);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new AssertionError("Unable to read state component " + component, e);
            }
        }
    }

    private static final class Hasher {
        private long value = 0xcbf29ce484222325L;

        private void mix(long next) {
            value ^= next + 0x9e3779b97f4a7c15L + (value << 6) + (value >>> 2);
        }
    }

    private static final class EventDigest {
        private int frameCount;
        private long frameHash = 0xcbf29ce484222325L;
        private int audioCount;
        private long audioHash = 0xcbf29ce484222325L;

        private void onFrame(Display.DmgFrameReadyEvent event) {
            frameCount++;
            mix(event.lcdBlank() ? 1 : 0);
            for (int pixel : event.pixels()) {
                mix(pixel);
            }
        }

        private void onAudio(Sound.SoundSampleEvent event) {
            audioCount++;
            for (int sample : event.buffer()) {
                mixAudio(sample);
            }
        }

        private void mix(long next) {
            frameHash ^= next & 0xffffffffL;
            frameHash *= 0x100000001b3L;
        }

        private void mixAudio(long next) {
            audioHash ^= next & 0xffffffffL;
            audioHash *= 0x100000001b3L;
        }
    }

    private static final class Session implements AutoCloseable {
        private final EventBusImpl eventBus = new EventBusImpl(null, null, false);
        private final EventDigest events = new EventDigest();
        private final Gameboy gameboy;
        private final Gpu gpu;

        private Session(ExecutionMode mode, HardwareProfile profile, int scx) throws Exception {
            eventBus.register(events::onFrame, Display.DmgFrameReadyEvent.class);
            eventBus.register(events::onAudio, Sound.SoundSampleEvent.class);
            gameboy = new Gameboy.GameboyConfiguration(new Rom(syntheticRom()))
                    .setHardwareProfile(profile)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setExecutionMode(mode)
                    .setSupportBatterySave(false)
                    .setDisplaySgbBorder(false)
                    .build();
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            gpu = gameboy.getGpu();
            for (int address = 0x8000; address < 0xa000; address++) {
                gpu.writeVideoRam0ForCore(address,
                        (address * 37 ^ address >>> 3 ^ 0x5a) & 0xff);
            }
            gpu.setByte(0xff42, (scx * 19 + 3) & 0xff);
            gpu.setByte(0xff43, scx);
        }

        @Override
        public void close() {
            gameboy.closeSilently();
            eventBus.close();
        }
    }
}
