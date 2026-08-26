package eu.rekawek.coffeegb.android;

import static org.junit.Assert.fail;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryFlush;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceResult;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage;
import eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class AndroidBatteryPersistenceSmokeTest {

    @Test
    public void managedBatteryCanBeWrittenInAppPrivateStorage() throws Exception {
        Path managedRoot = ApplicationProvider.getApplicationContext().getNoBackupFilesDir()
                .toPath().resolve("coffee-gb");
        Files.createDirectories(managedRoot);
        Path testDirectory = Files.createTempDirectory(managedRoot, "battery-smoke-");
        try {
            Path target = testDirectory.resolve("game").resolve("battery.sav");
            BatteryStorage storage = new BatteryStorage(
                    BatteryStorage.Source.appPrivate(target, managedRoot), List.of());
            FileBattery battery = new FileBattery(storage, 0x2000);
            int[] ram = new int[0x2000];
            ram[0] = 0x5a;
            battery.saveRam(ram);
            BatteryFlush flush = battery.prepareFlush(() -> { });
            BatteryPersistenceResult result = flush.persist();
            if (result instanceof BatteryPersistenceResult.Failure failure) {
                throw new AssertionError("App-private battery write failed", failure.cause());
            }
            flush.complete(result);
        } finally {
            if (Files.exists(testDirectory)) {
                try (var paths = Files.walk(testDirectory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception failure) {
                            fail("Unable to clean battery smoke-test storage: "
                                    + failure.getClass().getSimpleName());
                        }
                    });
                }
            }
        }
    }
}
