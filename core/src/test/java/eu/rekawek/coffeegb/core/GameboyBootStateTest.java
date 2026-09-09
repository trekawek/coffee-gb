package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.sound.Sound.SoundSampleEvent;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GameboyBootStateTest {

    @Test
    public void authenticBootStartsFromRawInterruptAndSoundPowerState() throws Exception {
        Rom rom = new Rom(mbc1BatteryRom());
        for (GameboyType type : new GameboyType[]{GameboyType.DMG, GameboyType.CGB}) {
            try (Gameboy gameboy = new GameboyConfiguration(rom)
                    .setGameboyType(type)
                    .setBootstrapMode(BootstrapMode.NORMAL)
                    .build()) {
                AddressSpace bus = gameboy.getAddressSpace();

                assertArrayEquals(
                    new int[]{0xe0, 0x00, 0x00, 0x70},
                        new int[]{bus.getByte(0xff0f), bus.getByte(0xff24),
                                bus.getByte(0xff25), bus.getByte(0xff26)});
                assertFalse(gameboy.isBootstrapReady());
            }
        }
    }

    @Test
    public void skippedBootKeepsPostBootInterruptAndSoundPresets() throws Exception {
        Rom rom = new Rom(mbc1BatteryRom());
        try (Gameboy gameboy = new GameboyConfiguration(rom)
                .setGameboyType(GameboyType.DMG)
                .setBootstrapMode(BootstrapMode.SKIP)
                .build()) {
            AddressSpace bus = gameboy.getAddressSpace();

            assertArrayEquals(
                        new int[]{0xe1, 0x77, 0xf3, 0xf1},
                        new int[]{bus.getByte(0xff0f), bus.getByte(0xff24),
                                bus.getByte(0xff25), bus.getByte(0xff26)});
            assertTrue(gameboy.isBootstrapReady());
        }
    }

    @Test
    public void skippedBootRoutesMusicWithoutARomWritingNr51() throws Exception {
        for (GameboyType type : new GameboyType[]{GameboyType.DMG, GameboyType.CGB}) {
            byte[] bytes = mbc1BatteryRom();
            // A stationary CPU leaves channel 1 playing without touching mixer routing.
            bytes[0x100] = 0x18;
            bytes[0x101] = (byte) 0xfe;
            try (Gameboy gameboy = new GameboyConfiguration(new Rom(bytes))
                    .setGameboyType(type).setBootstrapMode(BootstrapMode.SKIP).build()) {
                var events = new EventBusImpl(null, null, false);
                boolean[] positive = new boolean[2];
                boolean[] negative = new boolean[2];
                events.register(event -> {
                    for (int i = 0; i < event.buffer().length; i++) {
                        positive[i & 1] |= event.buffer()[i] > 0;
                        negative[i & 1] |= event.buffer()[i] < 0;
                    }
                }, SoundSampleEvent.class);
                gameboy.init(events, SerialEndpoint.NULL_ENDPOINT, null);
                AddressSpace bus = gameboy.getAddressSpace();
                bus.setByte(0xff11, 0x80);
                bus.setByte(0xff12, 0xf0);
                bus.setByte(0xff13, 0x00);
                bus.setByte(0xff14, 0x87);
                for (int tick = 0; tick < 140_448; tick++) {
                    gameboy.tick();
                }
                for (int side = 0; side < 2; side++) {
                    assertTrue(type + " must produce a waveform on speaker " + side,
                            positive[side] && negative[side]);
                }
            }
        }
    }

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
            ComponentState<Gameboy> fullState = source.captureState();

            target.getAddressSpace().setByte(0x0000, 0x0a);
            target.getAddressSpace().setByte(0xa000, 0x66);
            target.getAddressSpace().setByte(0xc123, 0x00);
            target.restoreBootState(bootState);

            assertEquals(0x77, target.getAddressSpace().getByte(0xc123));
            assertEquals(source.getCpu().getRegisters().getPC(), target.getCpu().getRegisters().getPC());
            assertEquals(0x66, target.getAddressSpace().getByte(0xa000));

            // Ordinary save-state restoration must retain its historical whole-machine behavior.
            target.restoreState(fullState);
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

    @Test
    public void dmgSkipBootExplicitlyDisablesTheBootRom() throws Exception {
        byte[] bytes = new byte[0x8000];
        bytes[0] = 0x42;
        Rom rom = new Rom(bytes);

        try (Gameboy gameboy = new GameboyConfiguration(rom)
                .setGameboyType(GameboyType.DMG)
                .setBootstrapMode(BootstrapMode.SKIP)
                .build()) {
            assertEquals(0x42, gameboy.getAddressSpace().getByte(0x0000));
        }
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
