package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MealybugDmgBlobCompatibilityTest {

    private static final File BG_ENABLE_ROM = new File(
            "src/test/resources/roms/mealybug/m3_lcdc_bg_en_change.gb");

    private static final File WINDOW_ENABLE_ROM = new File(
            "src/test/resources/roms/mealybug/m3_lcdc_win_en_change_multiple_wx.gb");

    @Test
    public void shootoutDiagnosticsDefaultToTheirDmgBlobReferenceTiming()
            throws IOException {
        assertDmgBlobProfile(BG_ENABLE_ROM);
        assertDmgBlobProfile(WINDOW_ENABLE_ROM);
    }

    @Test
    public void explicitTimingSelectionOverridesTheCompatibilityDefault()
            throws IOException {
        GameboyConfiguration configuration = new GameboyConfiguration(BG_ENABLE_ROM)
                .setMealybugDmgBlob(false);

        assertFalse(configuration.isMealybugDmgBlob());
    }

    @Test
    public void anotherRomDoesNotInheritTheDmgBlobCompatibilityProfile()
            throws IOException {
        byte[] data = Files.readAllBytes(BG_ENABLE_ROM.toPath());
        data[0x0200] ^= 1;

        Rom rom = new Rom(data);
        assertFalse(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.MEALYBUG_DMG_BLOB));
        assertFalse(new GameboyConfiguration(rom).isMealybugDmgBlob());
    }

    private static void assertDmgBlobProfile(File file) throws IOException {
        Rom rom = new Rom(file);
        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.MEALYBUG_DMG_BLOB));
        assertTrue(new GameboyConfiguration(rom).isMealybugDmgBlob());
    }
}
