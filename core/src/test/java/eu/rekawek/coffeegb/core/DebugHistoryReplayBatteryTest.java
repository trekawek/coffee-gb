package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage;
import eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery;
import eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery;
import eu.rekawek.coffeegb.core.memory.cart.battery.StateReplayBattery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DebugHistoryReplayBatteryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void liveFileBatteryShapeSurvivesNonPersistentConfigurationMutation()
            throws Exception {
        BatteryStorage liveStorage = BatteryStorage.direct(
                temporaryFolder.newFolder("live-file-battery").toPath().resolve("save.sav"));
        PlayerInputSource replayInput = PlayerInputSnapshot::released;
        GameboyConfiguration source =
                new GameboyConfiguration(new Rom(batteryRom()))
                        .setBatteryStorage(liveStorage, null);

        try (Gameboy liveGameboy = source.build()) {
            assertTrue(battery(primaryCartridge(liveGameboy)) instanceof FileBattery);
            source.setSupportBatterySave(false).setBatteryStorage(null, null);

            GameboyConfiguration replay = source.forDebugHistoryReplay(
                    liveGameboy, new VirtualTimeSource(1234), replayInput);
            try (Gameboy gameboy = replay.forRestore().build()) {
                Cartridge cartridge = primaryCartridge(gameboy);
                assertTrue(battery(cartridge) instanceof StateReplayBattery);
                assertSame(replayInput, replay.getPlayerInputSource());

                cartridge.setByte(0x0000, 0x0a);
                cartridge.setByte(0xa000, 0x5a);
                cartridge.flushBattery();
            }
        }
    }

    @Test
    public void memoryBatteryKindIsPreserved() throws Exception {
        GameboyConfiguration memorySource =
                new GameboyConfiguration(new Rom(batteryRom()))
                        .setBatteryData(new byte[0x2000]);
        try (Gameboy liveGameboy = memorySource.build()) {
            GameboyConfiguration replay = memorySource.forDebugHistoryReplay(
                    liveGameboy, new VirtualTimeSource(), PlayerInputSource.RELEASED);
            try (Gameboy gameboy = replay.build()) {
                assertTrue(battery(primaryCartridge(gameboy)) instanceof MemoryBattery);
            }
        }
    }

    @Test
    public void liveNullBatteryShapeSurvivesConfigurationGainingStorage() throws Exception {
        GameboyConfiguration nullSource =
                new GameboyConfiguration(new Rom(batteryRom()))
                        .setSupportBatterySave(false);
        try (Gameboy liveGameboy = nullSource.build()) {
            assertSame(Battery.NULL_BATTERY, battery(primaryCartridge(liveGameboy)));
            BatteryStorage laterStorage = BatteryStorage.direct(
                    temporaryFolder.newFolder("later-file-battery")
                            .toPath()
                            .resolve("save.sav"));
            nullSource.setSupportBatterySave(true).setBatteryStorage(laterStorage, null);

            GameboyConfiguration replay = nullSource.forDebugHistoryReplay(
                    liveGameboy, new VirtualTimeSource(), PlayerInputSource.RELEASED);
            try (Gameboy gameboy = replay.build()) {
                assertSame(Battery.NULL_BATTERY, battery(primaryCartridge(gameboy)));
            }
        }
    }

    @Test
    public void actionReplaySlotUsesTheSameBatteryKindSelection() throws Exception {
        BatteryStorage liveSlotStorage = BatteryStorage.direct(
                temporaryFolder.newFolder("live-slot-battery").toPath().resolve("slot.sav"));
        GameboyConfiguration fileSlotSource =
                new GameboyConfiguration(new Rom(datelRom()))
                        .setSlotRom(new Rom(batteryRom()))
                        .setBatteryStorage(null, liveSlotStorage);
        try (Gameboy liveGameboy = fileSlotSource.build()) {
            GameboyConfiguration replay = fileSlotSource.forDebugHistoryReplay(
                    liveGameboy, new VirtualTimeSource(), PlayerInputSource.RELEASED);
            try (Gameboy gameboy = replay.build()) {
                assertTrue(battery(slotCartridge(gameboy)) instanceof StateReplayBattery);
            }
        }

        GameboyConfiguration memorySlotSource =
                new GameboyConfiguration(new Rom(datelRom()))
                        .setSlotRom(new Rom(batteryRom()))
                        .setSlotBatteryData(new byte[0x2000]);
        try (Gameboy liveGameboy = memorySlotSource.build()) {
            GameboyConfiguration replay = memorySlotSource.forDebugHistoryReplay(
                    liveGameboy, new VirtualTimeSource(), PlayerInputSource.RELEASED);
            try (Gameboy gameboy = replay.build()) {
                assertTrue(battery(slotCartridge(gameboy)) instanceof MemoryBattery);
            }
        }
    }

    private static Cartridge primaryCartridge(Gameboy gameboy) throws Exception {
        return (Cartridge) field(Gameboy.class, "cartridge").get(gameboy);
    }

    private static Cartridge slotCartridge(Gameboy gameboy) throws Exception {
        return (Cartridge) field(Gameboy.class, "slotCartridge").get(gameboy);
    }

    private static Battery battery(Cartridge cartridge) throws Exception {
        return (Battery) field(Cartridge.class, "battery").get(cartridge);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static byte[] batteryRom() {
        byte[] rom = new byte[0x8000];
        rom[0x0147] = 0x03; // MBC1 + RAM + battery
        rom[0x0148] = 0x00; // 32 KiB ROM
        rom[0x0149] = 0x02; // 8 KiB RAM
        return rom;
    }

    private static byte[] datelRom() {
        byte[] rom = new byte[0x20000];
        rom[0x0100] = 0x00;
        rom[0x0101] = (byte) 0xc3;
        rom[0x0102] = 0x50;
        rom[0x0103] = 0x01;
        rom[0x0104] = 0x44; // deliberately bad logo, as on the supported Datel image
        byte[] title = "Action Replay V4".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, 0x0134, title.length);
        rom[0x0147] = 0x00;
        rom[0x0148] = 0x02;
        return rom;
    }
}
