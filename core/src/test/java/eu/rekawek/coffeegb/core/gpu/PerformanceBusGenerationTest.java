package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Hdma;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Focused coverage for transient DMA generations used by the guarded PPU cursor. */
public final class PerformanceBusGenerationTest {

    @Test
    public void oamDmaStartAndActiveTickInvalidateTheArmedCursor() throws Exception {
        try (Session session = new Session(HardwareProfileRegistry.DMG)) {
            armCursor(session.gameboy);
            assertTrue(cursor(session.gameboy.getGpu()));

            Dma dma = field(session.gameboy.getGpu(), "dma", Dma.class);
            long beforeStart = dma.getPpuBusGeneration();
            session.gameboy.getAddressSpace().setByte(0xff46, 0xc0);
            assertTrue(dma.getPpuBusGeneration() > beforeStart);
            session.gameboy.tick();
            assertFalse(cursor(session.gameboy.getGpu()));

            long beforeTick = dma.getPpuBusGeneration();
            dma.tick(false, true);
            assertTrue(dma.getPpuBusGeneration() > beforeTick);
        }
    }

    @Test
    public void vramDmaStartStopTickAndRestoreAdvanceGeneration() throws Exception {
        try (Session session = new Session(HardwareProfileRegistry.CGB)) {
            Hdma hdma = field(session.gameboy, "hdma", Hdma.class);
            long initial = hdma.getPpuBusGeneration();
            session.gameboy.getAddressSpace().setByte(0xff55, 0x80);
            assertTrue(hdma.getPpuBusGeneration() > initial);

            long beforeTick = hdma.getPpuBusGeneration();
            hdma.tick();
            assertTrue(hdma.getPpuBusGeneration() > beforeTick);

            long beforeStop = hdma.getPpuBusGeneration();
            session.gameboy.getAddressSpace().setByte(0xff55, 0x00);
            assertTrue(hdma.getPpuBusGeneration() > beforeStop);

            ComponentState<Hdma> state = hdma.captureState();
            long beforeRestore = hdma.getPpuBusGeneration();
            hdma.restoreState(state);
            assertTrue(hdma.getPpuBusGeneration() > beforeRestore);
        }
    }

    private static void armCursor(Gameboy gameboy) {
        Gpu gpu = gameboy.getGpu();
        while (gpu.getLine() != 1
                || gpu.getMode() != Mode.PixelTransfer
                || gpu.getTicksInLine() != 80) {
            gameboy.tick();
        }
        gameboy.tick();
    }

    private static boolean cursor(Gpu gpu) throws Exception {
        Field field = Gpu.class.getDeclaredField("steadyTimingCursor");
        field.setAccessible(true);
        return field.getBoolean(gpu);
    }

    private static <T> T field(Object object, String name, Class<T> type) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(object));
    }

    private static byte[] syntheticRom(HardwareProfile profile) {
        byte[] rom = new byte[0x8000];
        rom[0x100] = (byte) 0xc3;
        rom[0x101] = 0;
        rom[0x102] = 1;
        rom[0x147] = 0;
        if (profile.family() == HardwareProfile.Family.CGB) {
            rom[0x143] = (byte) 0x80;
        }
        return rom;
    }

    private static final class Session implements AutoCloseable {
        private final EventBusImpl eventBus = new EventBusImpl(null, null, false);
        private final Gameboy gameboy;

        private Session(HardwareProfile profile) throws Exception {
            gameboy = new Gameboy.GameboyConfiguration(new Rom(syntheticRom(profile)))
                    .setHardwareProfile(profile)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setExecutionMode(ExecutionMode.PERFORMANCE)
                    .setSupportBatterySave(false)
                    .build();
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        }

        @Override
        public void close() {
            gameboy.closeSilently();
            eventBus.close();
        }
    }
}
