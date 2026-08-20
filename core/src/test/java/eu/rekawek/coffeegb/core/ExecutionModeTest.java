package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ExecutionModeTest {

    @Test
    public void accuracyIsTheDefaultForConfigurationAndSession() throws Exception {
        Rom rom = new Rom(testRom());
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(rom);

        assertEquals(ExecutionMode.ACCURACY, configuration.getExecutionMode());
        try (Gameboy gameboy = configuration.build()) {
            assertEquals(ExecutionMode.ACCURACY, gameboy.getExecutionMode());
        }
        try (Gameboy gameboy = new Gameboy(new Rom(testRom()))) {
            assertEquals(ExecutionMode.ACCURACY, gameboy.getExecutionMode());
        }
    }

    @Test
    public void performanceCanBeSelectedWhenCreatingASession() throws Exception {
        Rom rom = new Rom(testRom());
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(rom)
                .setExecutionMode(ExecutionMode.PERFORMANCE);

        assertEquals(ExecutionMode.PERFORMANCE, configuration.getExecutionMode());
        try (Gameboy gameboy = configuration.build()) {
            assertEquals(ExecutionMode.PERFORMANCE, gameboy.getExecutionMode());
        }
    }

    @Test
    public void copiedConfigurationsRetainSessionMode() throws Exception {
        Rom rom = new Rom(testRom());
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(rom)
                .setExecutionMode(ExecutionMode.PERFORMANCE);

        assertEquals(ExecutionMode.PERFORMANCE, configuration.forRestore().getExecutionMode());
        assertEquals(ExecutionMode.PERFORMANCE, configuration.forStateHistoryReplay().getExecutionMode());
        assertEquals(ExecutionMode.PERFORMANCE, configuration.forBootTemplate().getExecutionMode());
    }

    @Test
    public void nullModeIsRejected() throws Exception {
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(
                new Rom(testRom()));

        assertThrows(NullPointerException.class, () -> configuration.setExecutionMode(null));
    }

    private static byte[] testRom() {
        byte[] rom = new byte[0x8000];
        rom[0x147] = 0;
        return rom;
    }
}
