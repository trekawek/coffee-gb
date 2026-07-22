package eu.rekawek.coffeegb.core.rumble;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodeBreakerRumbleCompatibilityTest {

    private static final Path DEMO = Paths.get(
            "src/test/resources/roms/codebreaker/In-Game Rumble Demo (PD) [C].gbc");

    private static final Path SHOOTOUT_DMA_DIR = Paths.get(
            "src/test/resources/roms/samesuite/dma");

    @Test
    public void knownDemoAutoAttachesCodeBreakerAccessory() throws Exception {
        Rom rom = new Rom(DEMO.toFile());
        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.CODEBREAKER_RUMBLE));

        List<Boolean> motorLog = new ArrayList<>();
        try (Gameboy gameboy = newGameboy(rom, motorLog)) {
            gameboy.getAddressSpace().setByte(0xfffe, 0x80);
            gameboy.getAddressSpace().setByte(0xfffe, 0x00);
        }

        assertEquals(List.of(true, false), motorLog);
    }

    @Test
    public void ordinaryShootoutRomsDoNotAttachCodeBreakerAccessory() throws Exception {
        for (String name : List.of("gdma_addr_mask", "hdma_lcd_off", "hdma_mode0")) {
            Rom rom = new Rom(SHOOTOUT_DMA_DIR.resolve(name + "-shootout.gb").toFile());
            assertFalse(rom.getCartridgeProperties().has(
                    CartridgeProperties.Feature.CODEBREAKER_RUMBLE));

            List<Boolean> motorLog = new ArrayList<>();
            try (Gameboy gameboy = newGameboy(rom, motorLog)) {
                gameboy.getAddressSpace().setByte(0xfffe, 0xfe);
                assertEquals(0xfe, gameboy.getAddressSpace().getByte(0xfffe));
            }
            assertEquals(name, List.of(), motorLog);
        }
    }

    private static Gameboy newGameboy(Rom rom, List<Boolean> motorLog) {
        Gameboy gameboy = new Gameboy.GameboyConfiguration(rom)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        eventBus.register(event -> motorLog.add(event.on()), RumbleEvent.class);
        gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        return gameboy;
    }
}
