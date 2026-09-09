package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.Dma;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

/** Imports the source mode's state; it does not recreate an Accuracy rendering of its past. */
public class GameboyCrossModeRestoreTest {
    private record Profile(HardwareProfile hardware, int speed) {}
    private static final Profile[] PROFILES = {
            new Profile(HardwareProfileRegistry.DMG, 1),
            new Profile(HardwareProfileRegistry.CGB, 1),
            new Profile(HardwareProfileRegistry.CGB, 2),
            new Profile(HardwareProfileRegistry.CGB0, 2)
    };
    private static final Set<String> HOST_PCM_FIELDS =
            Set.of("buffer", "i", "performanceSamplePhase", "audioDecimation");

    @Test public void accuracyImportsTheLivePerformanceLineAndFinishesItsExistingCursor() throws Exception {
        for (Profile p : PROFILES) try (Gameboy source = session(p, ExecutionMode.PERFORMANCE);
                                       Gameboy target = session(p, ExecutionMode.ACCURACY)) {
            source.getGpu().setPerformanceScanlineEnabled(true);
            seek(source, 2, 120);
            assertTrue("fixture is an already composed line", source.getGpu().isPerformanceScanlineCursorActive());
            ensureDeferredAudio(source);
            var saved = source.captureStateWithoutTimeSource();
            var savedFifo = source.captureDmgFifoRuntimeState();
            assertTrue("capture preserves the source cursor", source.getGpu().isPerformanceScanlineCursorActive());
            assertEquals(0, field(source.getSound(), "pendingPerformanceTicks"));
            source.restoreStateSilently(saved);
            target.restoreStateSilently(saved);
            source.restoreDmgFifoRuntimeState(savedFifo); target.restoreDmgFifoRuntimeState(savedFifo);
            source.getGpu().setPerformanceScanlineEnabled(false);
            assertEquals(ExecutionMode.ACCURACY, target.getExecutionMode());
            assertTrue("import retains the coarse endpoint", target.getGpu().isPerformanceScanlineCursorActive());
            assertAudioImport(saved, target);
            assertCrossState("imported direct state", source.captureStateWithoutTimeSource(), target.captureStateWithoutTimeSource());
            continueCrossMode(source, target, p + " imported direct", new int[]{1, 3, 17, 54, 200, 456});
            assertFalse("original line reaches its handoff", target.getGpu().isPerformanceScanlineCursorActive());
            assertEquals("Accuracy completes only the imported direct line", 1, target.getGpu().getPerformanceScanlineLines());
        }
    }

    @Test public void pendingPpuCopiesAndOwnedOamTransferSurviveBothModeDirections() throws Exception {
        for (Profile p : PROFILES) for (ExecutionMode mode : ExecutionMode.values())
            try (Gameboy source = session(p, mode); Gameboy target = session(p, opposite(mode))) {
                seek(source, 2, 120);
                for (int i = 0; i < 160; i++) source.getAddressSpace().setByte(0xc000 + i, (i * 37 + 11) & 255);
                source.getAddressSpace().setByte(0xff46, 0xc0);
                for (int i = 0; i < 9; i++) source.tick();
                if (mode == ExecutionMode.PERFORMANCE) ensureDeferredAudio(source);
                source.getGpu().setByteFromCpu(0xff4a, 3);
                source.getGpu().setByteFromCpu(0xff40, source.getGpu().getLcdcValueForCore() ^ 0x20);
                assertTrue("fixture retains owned DMA", ((Dma) field(source, "dma")).isTransferInProgress());
                assertFalse("fixture retains a CPU-to-PPU copy", pendingWindowCopies(source).isEmpty());
                var saved = source.captureStateWithoutTimeSource();
                var savedFifo = source.captureDmgFifoRuntimeState();
                assertEquals("capture materializes deferred audio", 0, field(source.getSound(), "pendingPerformanceTicks"));
                source.restoreStateSilently(saved); target.restoreStateSilently(saved);
                source.restoreDmgFifoRuntimeState(savedFifo); target.restoreDmgFifoRuntimeState(savedFifo);
                assertAudioImport(saved, target);
                assertEquals(opposite(mode), target.getExecutionMode());
                assertCrossState("pending-state import", source.captureStateWithoutTimeSource(), target.captureStateWithoutTimeSource());
                continueCrossMode(source, target, p + " " + mode + " pending", new int[]{1, 2, 4, 7, 17, 54, 81, 700});
                assertFalse("restored transfer reaches release", ((Dma) field(target, "dma")).isTransferInProgress());
                assertTrue("restored PPU copies mature", pendingWindowCopies(target).isEmpty());
            }
    }

    @Test public void importedAccuracyStateCanResumeThePerformanceBatchRunner() throws Exception {
        for (Profile p : PROFILES) try (Gameboy source = session(p, ExecutionMode.ACCURACY);
                                       Gameboy scalar = session(p, ExecutionMode.PERFORMANCE);
                                       Gameboy batched = session(p, ExecutionMode.PERFORMANCE)) {
            seek(source, 2, 120);
            source.getGpu().setByteFromCpu(0xff40, source.getGpu().getLcdcValueForCore() ^ 0x20);
            var saved = source.captureStateWithoutTimeSource();
            var savedFifo = source.captureDmgFifoRuntimeState();
            // Use the public restore path on both receiving sessions: its documented host
            // partial-frame suppression must not depend on the originating execution mode.
            scalar.restoreState(saved); batched.restoreState(saved);
            scalar.restoreDmgFifoRuntimeState(savedFifo); batched.restoreDmgFifoRuntimeState(savedFifo);
            assertAudioImport(saved, batched);
            assertStateEquals("same imported target representation", scalar.captureStateWithoutTimeSource(), batched.captureStateWithoutTimeSource());
            scalar.setPerformanceBatchingEnabled(false);
            batched.resetPerformanceBulkCounters();
            for (int ticks : new int[]{1, 3, 17, 54, 700, 70_224}) {
                assertEquals("physical frames after cross-mode import", scalar.runTicks(ticks), batched.runTicks(ticks));
                assertStateEquals(p + " target-mode packet " + ticks,
                        scalar.captureStateWithoutTimeSource(), batched.captureStateWithoutTimeSource());
                assertStateEquals("target-mode FIFO supplement", scalar.captureDmgFifoRuntimeState(), batched.captureDmgFifoRuntimeState());
            }
            assertTrue("imported detailed FIFO does not strand the batch runner",
                    batched.getPerformanceEpochTicks() + batched.getPerformanceBulkTicks() > 10_000);
            assertTrue("future target-mode lines can use their normal compositor", batched.getGpu().getPerformanceScanlineLines() > 0);
        }
    }

    @Test public void everyFetchResidueRestoresIntoAFreshSameModeMachine() throws Exception {
        for (Profile p : PROFILES) for (ExecutionMode mode : ExecutionMode.values())
            for (int residue = 0; residue < 8; residue++)
                try (Gameboy source = session(p, mode); Gameboy target = session(p, mode)) {
                    seek(source, 2, 128 + residue);
                    var saved = source.captureStateWithoutTimeSource();
                    var fifo = source.captureDmgFifoRuntimeState();
                    source.restoreStateSilently(saved); target.restoreStateSilently(saved);
                    source.restoreDmgFifoRuntimeState(fifo); target.restoreDmgFifoRuntimeState(fifo);
                    for (int tick = 0; tick < 54; tick++) {
                        assertEquals(source.tick(), target.tick());
                        assertStateEquals(p + " " + mode + " residue=" + residue + " tick=" + tick,
                                source.captureStateWithoutTimeSource(), target.captureStateWithoutTimeSource());
                        assertStateEquals("full DMG latch supplement", source.captureDmgFifoRuntimeState(), target.captureDmgFifoRuntimeState());
                    }
                }
    }

    private static void continueCrossMode(Gameboy a, Gameboy b, String label, int[] spans) throws Exception {
        for (int ticks : spans) {
            for (int t = 0; t < ticks; t++) assertEquals(label + " frame dot", a.tick(), b.tick());
            assertCrossState(label + " after " + ticks, a.captureStateWithoutTimeSource(), b.captureStateWithoutTimeSource());
            assertStateEquals(label + " DMG FIFO supplement", a.captureDmgFifoRuntimeState(), b.captureDmgFifoRuntimeState());
        }
    }

    private static void assertAudioImport(Object sourceState, Gameboy target) throws Exception {
        Object original = component(sourceState, "soundMemento");
        Object restored = target.getSound().captureState();
        assertEquals("imported compositor metadata is retained",
                component(component(sourceState, "gpuMemento"), "performanceWindowLineCounter"),
                target.getGpu().getPerformanceWindowLineCounter());
        assertTrue("fixture has a pending source PCM prefix", ((Number) component(original, "i")).intValue() > 0);
        assertNotEquals("receiving mode owns its output rate", component(original, "audioDecimation"), component(restored, "audioDecimation"));
        assertEquals("old PCM backlog is discarded", 0, component(restored, "i"));
        assertEquals("new output phase starts at zero", 0, component(restored, "performanceSamplePhase"));
        assertEquals(0, field(target.getSound(), "pendingPerformanceTicks"));
        assertCrossState("all emulated APU fields", original, restored);
    }

    private static void assertCrossState(String path, Object a, Object b) throws Exception {
        if (a == null || b == null) { assertEquals(path, a, b); return; }
        Class<?> c = a.getClass(); assertEquals(path + " type", c, b.getClass());
        if (c.isArray() && c.getComponentType().isPrimitive()
                && java.util.Objects.deepEquals(a, b)) return;
        if (c.isArray()) {
            assertEquals(path + " length", Array.getLength(a), Array.getLength(b));
            for (int i = 0; i < Array.getLength(a); i++) assertCrossState(path + "[" + i + "]", Array.get(a, i), Array.get(b, i));
        } else if (a instanceof List<?> x) {
            List<?> y = (List<?>) b; assertEquals(path + " size", x.size(), y.size());
            for (int i = 0; i < x.size(); i++) assertCrossState(path + "[" + i + "]", x.get(i), y.get(i));
        } else if (c.isRecord()) {
            for (RecordComponent f : c.getRecordComponents()) {
                if (c.getSimpleName().equals("SoundState") && HOST_PCM_FIELDS.contains(f.getName())) continue;
                // Accuracy does not maintain this derived compositor counter on future lines.
                // Both real PixelTransfer window counters remain part of this comparison.
                if (c.getSimpleName().equals("GpuState") && f.getName().equals("performanceWindowLineCounter")) continue;
                var m = f.getAccessor(); m.setAccessible(true);
                assertCrossState(path + "." + f.getName(), m.invoke(a), m.invoke(b));
            }
        } else assertEquals(path, a, b);
    }

    private static Gameboy session(Profile p, ExecutionMode mode) throws Exception {
        byte[] image = new byte[0x8000]; image[0x100] = (byte) 0xc3; image[0x101] = 0x50; image[0x102] = 1;
        image[0x143] = p.hardware.capabilities().colorDisplay() ? (byte) 0x80 : 0;
        image[0x150] = 0; image[0x151] = (byte) 0xc3; image[0x152] = 0x50; image[0x153] = 1;
        Gameboy g = new Gameboy.GameboyConfiguration(new Rom(image)).setHardwareProfile(p.hardware)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setExecutionMode(mode)
                .setRtcTimeSource(() -> 0L).setSupportBatterySave(false).build();
        if (p.speed == 2) {
            g.getSpeedMode().setByte(0xff4d, 1);
            var stop = g.getSpeedMode().getClass().getDeclaredMethod("onStop"); stop.setAccessible(true);
            assertEquals(true, stop.invoke(g.getSpeedMode()));
        }
        g.getGpu().setByte(0xff40, 0);
        for (int a = 0x8000; a < 0xa000; a++) g.getGpu().setByte(a, (a * 37 ^ a >>> 3) & 255);
        for (int a = 0xfe00; a < 0xfea0; a++) g.getGpu().setByte(a, 0);
        g.getGpu().setByte(0xff43, 3);
        g.getGpu().setByte(0xff40, 0x93);
        g.getAddressSpace().setByte(0xff41, 0); g.getAddressSpace().setByte(0xff45, 255);
        g.getAddressSpace().setByte(0xffff, 0); g.getAddressSpace().setByte(0xff0f, 0);
        g.getSound().setByte(0xff26, 0x80); g.getSound().setByte(0xff24, 0x77); g.getSound().setByte(0xff25, 0x22);
        g.getSound().setByte(0xff16, 0x80); g.getSound().setByte(0xff17, 0xf0);
        g.getSound().setByte(0xff18, 0x70); g.getSound().setByte(0xff19, 0x87);
        return g;
    }

    private static void seek(Gameboy g, int line, int dot) {
        for (int left = 70_224; left > 0; left--) {
            if (g.getGpu().getLine() == line && g.getGpu().getTicksInLine() == dot) return;
            g.tick();
        }
        fail("raster fixture was not reached");
    }
    private static void ensureDeferredAudio(Gameboy g) throws Exception {
        for (int left = 55; left > 0 && ((Number) field(g.getSound(), "pendingPerformanceTicks")).intValue() == 0; left--) g.tick();
        assertTrue("live lazy APU interval", ((Number) field(g.getSound(), "pendingPerformanceTicks")).intValue() > 0);
    }
    private static List<?> pendingWindowCopies(Gameboy g) throws Exception {
        if (g.getHardwareProfile().capabilities().colorDisplay()) return (List<?>) field(g.getGpu(), "pendingPpuWrites");
        return (List<?>) field(field(g.getGpu(), "pixelMachine"), "pendingWindowDisplayWrites");
    }
    private static ExecutionMode opposite(ExecutionMode m) { return m == ExecutionMode.ACCURACY ? ExecutionMode.PERFORMANCE : ExecutionMode.ACCURACY; }
    private static Object component(Object value, String name) throws Exception { var m = value.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(value); }
    private static Object field(Object value, String name) throws Exception { var f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value); }
}
