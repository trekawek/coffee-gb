package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A saved direct line must survive import into a fresh receiver whose cached clock domain is
 * different from the incoming state.  The receiver is deliberately advanced in the opposite
 * domain first; constructing it with the incoming domain would hide the restore ordering bug.
 */
public final class GameboyClockDomainRestoreTest {
    private static final int LCDC = 0xff40;
    private static final int MAX_TICKS = 3 * 456 * 154;
    private static final int SPEED_ROUTINE = 0x180;
    private static final Field SCANLINE_END = field("performanceScanlineEndTick");
    private static final Set<String> HOST_PCM_FIELDS = Set.of(
            "buffer", "i", "performanceSamplePhase", "audioDecimation");
    private static final Set<String> HOST_GPU_FIELDS = Set.of("performanceWindowLineCounter");

    @Test
    public void freshReceiverPreservesCursorForBothClockDirectionsAndModes() throws Exception {
        for (ExecutionMode targetMode : ExecutionMode.values()) {
            assertImportedCursor(false, true, targetMode);
            assertImportedCursor(true, false, targetMode);
        }
    }

    private static void assertImportedCursor(boolean sourceX2, boolean targetX2,
                                             ExecutionMode targetMode) throws Exception {
        String direction = sourceX2 ? "x2->x1" : "x1->x2";
        try (Gameboy source = session(ExecutionMode.PERFORMANCE);
                Gameboy target = session(targetMode)) {
            if (sourceX2) enterDoubleSpeed(source);
            seekRetainedCursor(source, direction);
            assertTrue(direction + " source cursor was not active",
                    source.getGpu().isPerformanceScanlineCursorActive());
            int sourceEnd = SCANLINE_END.getInt(source.getGpu());
            int sourceSpeed = source.getSpeedMode().getSpeedMode();

            if (targetX2) enterDoubleSpeed(target);
            int oldTargetSpeed = target.getSpeedMode().getSpeedMode();
            assertTrue(direction + " did not start in a different domain",
                    oldTargetSpeed != sourceSpeed);
            long targetLinesBefore = target.getGpu().getPerformanceScanlineLines();
            var saved = source.captureStateWithoutTimeSource();
            target.restoreStateSilently(saved);

            assertEquals("restored clock domain", sourceSpeed, target.getSpeedMode().getSpeedMode());
            assertTrue(direction + " " + targetMode
                            + " lost imported cursor",
                    target.getGpu().isPerformanceScanlineCursorActive());
            assertEquals(direction + " endpoint",
                    sourceEnd, SCANLINE_END.getInt(target.getGpu()));

            if (targetMode == ExecutionMode.PERFORMANCE) {
                PerformanceStateAssertions.assertStateEquals(direction + " initial canonical",
                        source.captureStateWithoutTimeSource(),
                        target.captureStateWithoutTimeSource());
                continueSameModeScalar(source, target, direction);
            } else {
                assertCrossState(direction + " initial cross-mode",
                        source.captureStateWithoutTimeSource(),
                        target.captureStateWithoutTimeSource());
                continueAccuracyImport(source, target, direction,
                        targetLinesBefore);
            }
        }
    }

    private static void continueSameModeScalar(Gameboy source, Gameboy target, String label)
            throws Exception {
        source.setPerformanceBatchingEnabled(false);
        target.setPerformanceBatchingEnabled(false);
        for (int ticks : new int[]{1, 3, 17, 54, 200, 456}) {
            assertEquals(label + " frame callback", source.runTicks(ticks), target.runTicks(ticks));
            PerformanceStateAssertions.assertStateEquals(label + " scalar canonical " + ticks,
                    source.captureStateWithoutTimeSource(), target.captureStateWithoutTimeSource());
        }
    }

    private static void continueAccuracyImport(Gameboy source, Gameboy target, String label,
                                               long targetLinesBefore) throws Exception {
        source.getGpu().setPerformanceScanlineEnabled(false);
        int continuation = 0;
        while (target.getGpu().isPerformanceScanlineCursorActive() && continuation++ < 512) {
            assertEquals(label + " cross-mode frame callback", source.tick(), target.tick());
            assertCrossState(label + " cross-mode " + continuation,
                    source.captureStateWithoutTimeSource(), target.captureStateWithoutTimeSource());
            assertEquals(label + " speed", source.getSpeedMode().getSpeedMode(),
                    target.getSpeedMode().getSpeedMode());
            assertEquals(label + " STAT", source.getGpu().getByte(0xff41),
                    target.getGpu().getByte(0xff41));
            assertEquals(label + " LY", source.getGpu().getByte(0xff44),
                    target.getGpu().getByte(0xff44));
        }
        assertFalse(label + " stranded imported cursor",
                target.getGpu().isPerformanceScanlineCursorActive());
        assertTrue(label + " did not consume an imported line", continuation > 0);
        assertEquals(label + " imported direct handoff count", targetLinesBefore + 1,
                target.getGpu().getPerformanceScanlineLines());
    }

    private static void seekRetainedCursor(Gameboy gameboy, String label) throws Exception {
        gameboy.getGpu().setPerformanceScanlineEnabled(true);
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            Gpu gpu = gameboy.getGpu();
            boolean beforeCursor = gpu.isPerformanceScanlineCursorActive();
            int beforeLcdc = gpu.getByte(LCDC);
            gameboy.runTicks(1);
            int afterLcdc = gpu.getByte(LCDC);
            if (beforeCursor && gpu.isPerformanceScanlineCursorActive()
                    && beforeLcdc != afterLcdc
                    && (beforeLcdc & 0x80) != 0 && (afterLcdc & 0x80) != 0) {
                return;
            }
        }
        throw new AssertionError(label + " did not observe a retained CPU on->on line");
    }

    /** Redirects only the opcode PC; the existing CPU clock phase and PPU timeline are retained. */
    private static void enterDoubleSpeed(Gameboy gameboy) {
        assertEquals("speed setup must start at an opcode boundary", Cpu.State.OPCODE,
                gameboy.getCpu().getState());
        gameboy.getCpu().getRegisters().setPC(SPEED_ROUTINE);
        int guard = 0;
        while ((gameboy.getSpeedMode().getSpeedMode() != 2
                        || gameboy.getCpu().isSpeedSwitching())
                && guard++ < MAX_TICKS) {
            gameboy.runTicks(1);
        }
        assertFalse("speed switch still active", gameboy.getCpu().isSpeedSwitching());
        assertEquals("speed switch did not reach x2", 2,
                gameboy.getSpeedMode().getSpeedMode());
    }

    private static Gameboy session(ExecutionMode mode) throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(commonImage()))
                .setHardwareProfile(HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(mode)
                .setRtcTimeSource(() -> 0L)
                .setPlayerInputSource(new PlayerInputHub())
                .setSupportBatterySave(false)
                .build();
    }

    /** One immutable cartridge image is shared by every source/target pair. */
    private static byte[] commonImage() {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0x50;
        image[0x102] = 1;
        image[0x143] = (byte) 0x80;
        int p = 0x150;
        image[p++] = (byte) 0xf3;       // DI
        image[p++] = 0x3e; image[p++] = (byte) 0x91;
        image[p++] = (byte) 0xe0; image[p++] = 0x40;
        int loop = p;
        image[p++] = 0x3e; image[p++] = (byte) 0xb1;
        image[p++] = (byte) 0xe0; image[p++] = 0x40;
        image[p++] = 0x3e; image[p++] = (byte) 0x91;
        image[p++] = (byte) 0xe0; image[p++] = 0x40;
        image[p++] = (byte) 0xc3;
        image[p++] = (byte) loop; image[p++] = (byte) (loop >> 8);
        p = SPEED_ROUTINE;
        image[p++] = 0x3e; image[p++] = 1;
        image[p++] = (byte) 0xe0; image[p++] = 0x4d;
        image[p++] = 0x10; image[p++] = 0;
        image[p++] = (byte) 0xc3;
        image[p++] = 0x50; image[p++] = 1;
        return image;
    }

    private static void assertCrossState(String path, Object expected, Object actual)
            throws Exception {
        if (expected == null || actual == null) {
            assertEquals(path, expected, actual);
            return;
        }
        Class<?> type = expected.getClass();
        assertEquals(path + " type", type, actual.getClass());
        if (type.isArray() && type.getComponentType().isPrimitive()
                && java.util.Objects.deepEquals(expected, actual)) {
            return;
        }
        if (type.isArray()) {
            assertEquals(path + " length", Array.getLength(expected), Array.getLength(actual));
            for (int i = 0; i < Array.getLength(expected); i++) {
                assertCrossState(path + '[' + i + ']', Array.get(expected, i),
                        Array.get(actual, i));
            }
        } else if (expected instanceof List<?> list) {
            List<?> other = (List<?>) actual;
            assertEquals(path + " size", list.size(), other.size());
            for (int i = 0; i < list.size(); i++) {
                assertCrossState(path + '[' + i + ']', list.get(i), other.get(i));
            }
        } else if (type.isRecord()) {
            String record = type.getSimpleName();
            for (RecordComponent component : type.getRecordComponents()) {
                if (record.equals("SoundState") && HOST_PCM_FIELDS.contains(component.getName())) {
                    continue;
                }
                if (record.equals("GpuState") && HOST_GPU_FIELDS.contains(component.getName())) {
                    continue;
                }
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertCrossState(path + '.' + component.getName(), accessor.invoke(expected),
                        accessor.invoke(actual));
            }
        } else {
            assertEquals(path, expected, actual);
        }
    }

    private static Field field(String name) {
        try {
            Field result = Gpu.class.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
