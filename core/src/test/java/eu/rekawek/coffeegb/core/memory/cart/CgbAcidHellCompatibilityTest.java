package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CgbAcidHellCompatibilityTest {

    private static final File ROM = new File(
            "src/test/resources/roms/cgb-acid-hell/cgb-acid-hell.gbc");

    @Test
    public void defaultsToTheCgb0RevisionTargetedByTheRom() throws IOException {
        Rom rom = new Rom(ROM);

        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.CGB0_REVISION));
        assertTrue(new GameboyConfiguration(rom).isCgb0Revision());
    }

    @Test
    public void explicitRevisionSelectionStillOverridesTheCompatibilityDefault()
            throws IOException {
        GameboyConfiguration configuration = new GameboyConfiguration(ROM)
                .setCgb0Revision(false);

        assertFalse(configuration.isCgb0Revision());
    }

    @Test
    public void anotherRomDoesNotInheritTheCgb0CompatibilityProfile() throws IOException {
        byte[] data = Files.readAllBytes(ROM.toPath());
        data[0x0200] ^= 1;

        Rom rom = new Rom(data);
        assertFalse(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.CGB0_REVISION));
        assertFalse(new GameboyConfiguration(rom).isCgb0Revision());
    }
}
