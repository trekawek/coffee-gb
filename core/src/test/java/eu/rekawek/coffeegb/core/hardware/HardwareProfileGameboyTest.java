package eu.rekawek.coffeegb.core.hardware;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class HardwareProfileGameboyTest {

    @Test
    public void profileIsResolvedBeforeConstructionAndDrivesSkipBootState() throws Exception {
        for (HardwareProfile profile : HardwareProfileRegistry.supportedProfiles()) {
            GameboyConfiguration configuration = configuration(profile);
            assertSame(profile, configuration.getHardwareProfile());
            assertSame(profile.clockSpec(), configuration.getClockSpec());

            try (Gameboy gameboy = configuration.build()) {
                assertSame(profile, gameboy.getHardwareProfile());
                assertSame(profile.clockSpec(), gameboy.getClockSpec());
                assertEquals(profile.id(), gameboy.getHardwareProfileIdentity().profileId());
                assertEquals(profile.bootSpec().postBootAf(), gameboy.getCpu().getRegisters().getAF());
                assertEquals(profile.bootSpec().postBootBc(), gameboy.getCpu().getRegisters().getBC());
                assertEquals(profile.bootSpec().postBootDe(), gameboy.getCpu().getRegisters().getDE());
                assertEquals(profile.bootSpec().postBootHl(), gameboy.getCpu().getRegisters().getHL());
                assertEquals(0x0100, gameboy.getCpu().getRegisters().getPC());
            }
        }
    }

    @Test
    public void cgb0CompatibilityApisCanonicalizeWithoutContradiction() throws Exception {
        GameboyConfiguration configuration = configuration(HardwareProfileRegistry.CGB);
        configuration.setCgb0Revision(true);
        assertSame(HardwareProfileRegistry.CGB0, configuration.getHardwareProfile());
        assertEquals(GameboyType.CGB, configuration.getGameboyType());

        configuration.setGameboyType(GameboyType.CGB);
        assertSame(HardwareProfileRegistry.CGB0, configuration.getHardwareProfile());
        configuration.setCgb0Revision(false);
        assertSame(HardwareProfileRegistry.CGB, configuration.getHardwareProfile());

        configuration.setHardwareProfile(HardwareProfileRegistry.DMG);
        assertThrows(IllegalArgumentException.class, () -> configuration.setCgb0Revision(true));
    }

    @Test
    public void restoreReplayAndBootTemplateCopiesPreserveExactProfile() throws Exception {
        GameboyConfiguration configuration = configuration(HardwareProfileRegistry.CGB0);

        assertSame(HardwareProfileRegistry.CGB0, configuration.forRestore().getHardwareProfile());
        assertSame(HardwareProfileRegistry.CGB0,
                configuration.forStateHistoryReplay().getHardwareProfile());
        assertSame(HardwareProfileRegistry.CGB0,
                configuration.forBootTemplate().getHardwareProfile());
    }

    private static GameboyConfiguration configuration(HardwareProfile profile) throws Exception {
        return new GameboyConfiguration(new Rom(testRom()))
                .setHardwareProfile(profile)
                .setBootstrapMode(BootstrapMode.SKIP)
                .setSupportBatterySave(false);
    }

    private static byte[] testRom() {
        byte[] rom = new byte[0x8000];
        byte[] title = "PROFILE TEST".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, 0x0134, title.length);
        rom[0x0143] = (byte) 0x80;
        return rom;
    }
}
