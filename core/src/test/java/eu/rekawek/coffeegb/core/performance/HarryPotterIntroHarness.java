package eu.rekawek.coffeegb.core.performance;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/** Shared setup for the Harry Potter ROM probes. */
public final class HarryPotterIntroHarness {

    /** Gzip-compressed 8 KiB battery save that skips the language-selection menu. */
    private static final String EMBEDDED_BATTERY_SAVE =
            "H4sIAAAAAAAEAO3BMQEAIAgAMKhAAksR0ceSmsGPY9vpXZkrAAAAgH8XAAAAYIgH3AhargAgAAA=";

    private HarryPotterIntroHarness() {
    }

    /**
     * Resolves the user-supplied ROM while keeping the external-ROM probes out of ordinary test
     * runs. Supplying an invalid path is still an error; omitting the property skips the probe.
     */
    public static File requireRom() {
        String configuredRom = System.getProperty("harryPotterRom");
        org.junit.Assume.assumeTrue(
                "Set -DharryPotterRom=<absolute path> to run the Harry Potter probe",
                configuredRom != null && !configuredRom.isBlank());
        File romFile = new File(configuredRom);
        if (!romFile.isFile()) {
            throw new IllegalArgumentException("ROM not found: " + romFile);
        }
        return romFile;
    }

    public static byte[] loadBatteryData() throws IOException {
        String configuredSave = System.getProperty("harryPotterBatterySave");
        if (configuredSave != null && !configuredSave.isBlank()) {
            File saveFile = new File(configuredSave);
            if (!saveFile.isFile()) {
                throw new IllegalArgumentException("Battery save not found: " + saveFile);
            }
            byte[] data = Files.readAllBytes(saveFile.toPath());
            System.out.printf("Harry Potter battery save: %s (%d bytes)%n",
                    saveFile.getAbsolutePath(), data.length);
            return data;
        }

        byte[] compressed = Base64.getDecoder().decode(EMBEDDED_BATTERY_SAVE);
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] data = input.readAllBytes();
            System.out.printf("Harry Potter battery save: embedded (%d bytes)%n", data.length);
            return data;
        }
    }
}
