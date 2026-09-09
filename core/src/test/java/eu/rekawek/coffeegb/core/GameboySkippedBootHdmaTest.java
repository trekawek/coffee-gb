package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.Hdma;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

public class GameboySkippedBootHdmaTest {
    @Test
    public void skipBootstrapStartsHdmaWithTheAlreadyRunningLcdAndNeedsNoLcdcToggle() throws Exception {
        for (var hardware : new eu.rekawek.coffeegb.core.hardware.HardwareProfile[]{
                HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0}) {
            try (Gameboy scalar = session(hardware); Gameboy direct = session(hardware)) {
                scalar.setPerformanceBatchingEnabled(false);
                for (Gameboy gameboy : new Gameboy[]{scalar, direct}) {
                    assertTrue(gameboy.getGpu().isLcdEnabled());
                    assertTrue("SKIP must publish the initial LCD level to HDMA",
                            ((Hdma.HdmaState) gameboy.getHdma().captureState()).lcdEnabled());
                    for (int index = 0; index < 48; index++) {
                        gameboy.getAddressSpace().setByte(0xc800 + index, index + 0x40);
                    }
                }
                assertEquals(scalar.runTicks(70_224), direct.runTicks(70_224));
                assertFalse("programmed HBlank DMA never completed", direct.getHdma().hasActiveOrPendingTransfer());
                for (int index = 0; index < 48; index++) {
                    assertEquals(index + 0x40, direct.getGpu().readSelectedVideoRamForCore(0x8000 + index));
                }
                assertTrue("no-toggle workload lost its performance epochs", direct.getPerformanceEpochTicks() > 10_000);
                assertStateEquals("SKIP no-toggle HDMA " + hardware,
                        scalar.captureStateWithoutTimeSource(), direct.captureStateWithoutTimeSource());
            }
        }
    }

    private static Gameboy session(eu.rekawek.coffeegb.core.hardware.HardwareProfile hardware)
            throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0x50;
        image[0x102] = 1;
        image[0x143] = (byte) 0x80;
        int[] program = {
                0xf3, 0xaf, 0xea, 0xff, 0xff, // DI; IE=0.
                0x3e, 0xc8, 0xe0, 0x51,       // source C800
                0xaf, 0xe0, 0x52, 0xe0, 0x53, 0xe0, 0x54,
                0x3e, 0x82, 0xe0, 0x55,       // three HBlank blocks; LCDC is never written
                0x00, 0x18, 0xfd
        };
        for (int i = 0; i < program.length; i++) image[0x150 + i] = (byte) program[i];
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(hardware)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(new PlayerInputHub())
                .setSupportBatterySave(false)
                .build();
    }
}
