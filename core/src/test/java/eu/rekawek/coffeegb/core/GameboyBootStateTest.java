package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class GameboyBootStateTest {

    @Test
    public void bootStateRestoresMachineButKeepsFreshCartridgeData() throws Exception {
        Rom rom = new Rom(mbc1BatteryRom());
        try (Gameboy source = skipped(rom); Gameboy target = skipped(rom)) {
            source.getAddressSpace().setByte(0x0000, 0x0a);
            source.getAddressSpace().setByte(0xa000, 0x11);
            source.getAddressSpace().setByte(0xc123, 0x77);
            for (int i = 0; i < 128; i++) {
                source.tick();
            }

            Gameboy.BootState bootState = source.saveBootState();
            Memento<Gameboy> fullState = source.saveToMemento();

            target.getAddressSpace().setByte(0x0000, 0x0a);
            target.getAddressSpace().setByte(0xa000, 0x66);
            target.getAddressSpace().setByte(0xc123, 0x00);
            target.restoreBootState(bootState);

            assertEquals(0x77, target.getAddressSpace().getByte(0xc123));
            assertEquals(source.getCpu().getRegisters().getPC(), target.getCpu().getRegisters().getPC());
            assertEquals(0x66, target.getAddressSpace().getByte(0xa000));

            // Ordinary save-state restoration must retain its historical whole-machine behavior.
            target.restoreFromMemento(fullState);
            assertEquals(0x11, target.getAddressSpace().getByte(0xa000));
        }
    }

    @Test
    public void fastForwardBootCanBeCancelledBeforeTicking() throws Exception {
        Rom rom = new Rom(mbc1BatteryRom());
        GameboyConfiguration configuration = new GameboyConfiguration(rom)
                .setGameboyType(GameboyType.CGB)
                .setBootstrapMode(BootstrapMode.FAST_FORWARD)
                .setBootCancellation(() -> true);

        assertThrows(CancellationException.class, configuration::build);
    }

    private static Gameboy skipped(Rom rom) {
        return new GameboyConfiguration(rom)
                .setGameboyType(GameboyType.CGB)
                .setBootstrapMode(BootstrapMode.SKIP)
                .build();
    }

    private static byte[] mbc1BatteryRom() {
        byte[] rom = new byte[0x8000];
        byte[] title = "BOOT CACHE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, 0x0134, title.length);
        rom[0x0143] = (byte) 0x80;
        rom[0x0147] = 0x03; // MBC1 + RAM + battery
        rom[0x0148] = 0x00;
        rom[0x0149] = 0x02;
        return rom;
    }
}
