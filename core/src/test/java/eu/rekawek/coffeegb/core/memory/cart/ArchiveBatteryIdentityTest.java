package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryFlush;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceResult;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ArchiveBatteryIdentityTest {

    @Test
    public void entriesInOneArchiveCannotCollideOnBatteryPath() throws Exception {
        Path directory = Files.createTempDirectory("coffee-gb-archive-battery");
        try {
            Path container = directory.resolve("collection.zip");
            RomOrigin firstOrigin =
                    RomOrigin.archiveEntry(container, "red/game.gb", false);
            RomOrigin secondOrigin =
                    RomOrigin.archiveEntry(container, "blue/game.gb", false);
            Path firstSave =
                    firstOrigin.persistencePath(".sav").orElseThrow();
            Path secondSave =
                    secondOrigin.persistencePath(".sav").orElseThrow();
            assertNotEquals(firstSave, secondSave);

            persistOneByte(firstOrigin, 0x12);
            persistOneByte(secondOrigin, 0x34);

            assertEquals(0x12, Files.readAllBytes(firstSave)[0] & 0xff);
            assertEquals(0x34, Files.readAllBytes(secondSave)[0] & 0xff);
            assertFalse(Files.exists(directory.resolve("collection.sav")));
        } finally {
            try (var files = Files.list(directory)) {
                files.forEach(
                        file -> {
                            try {
                                Files.deleteIfExists(file);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
            Files.deleteIfExists(directory);
        }
    }

    private static void persistOneByte(RomOrigin origin, int value) throws Exception {
        Cartridge cartridge = new Cartridge(new Rom(new RomImage(origin, batteryRom())), true);
        cartridge.setByte(0x0000, 0x0a);
        cartridge.setByte(0xa000, value);

        BatteryFlush capture = cartridge.prepareBatteryFlush();
        BatteryPersistenceResult result = capture.persist();

        assertTrue(result instanceof BatteryPersistenceResult.Success);
        capture.complete(result);
    }

    private static byte[] batteryRom() {
        byte[] rom = new byte[0x8000];
        rom[0x147] = 0x03; // MBC1 + RAM + battery
        rom[0x149] = 0x02; // 8 KiB RAM
        return rom;
    }
}
