package eu.rekawek.coffeegb.core.hardware;

import eu.rekawek.coffeegb.core.GameboyType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HardwareProfileRegistryTest {

    @Test
    public void registryHasPermanentCanonicalIdsInDeterministicOrder() {
        assertEquals(List.of("dmg", "cgb", "cgb0", "sgb"), HardwareProfileRegistry.supportedIds());
        assertSame(HardwareProfileRegistry.DMG, HardwareProfileRegistry.resolve("dmg"));
        assertSame(HardwareProfileRegistry.CGB, HardwareProfileRegistry.resolve("cgb"));
        assertSame(HardwareProfileRegistry.CGB0, HardwareProfileRegistry.resolve("cgb0"));
        assertSame(HardwareProfileRegistry.SGB, HardwareProfileRegistry.resolve("sgb"));
        assertThrows(UnsupportedOperationException.class,
                () -> HardwareProfileRegistry.supportedProfiles().add(HardwareProfileRegistry.DMG));
    }

    @Test
    public void settingsAcceptOnlyTheFiniteLegacyAliases() {
        assertSame(HardwareProfileRegistry.DMG, HardwareProfileRegistry.resolveSetting("DMG"));
        assertSame(HardwareProfileRegistry.CGB, HardwareProfileRegistry.resolveSetting("CGB"));
        assertSame(HardwareProfileRegistry.CGB0, HardwareProfileRegistry.resolveSetting("CGB0"));
        assertSame(HardwareProfileRegistry.SGB, HardwareProfileRegistry.resolveSetting("SGB"));

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> HardwareProfileRegistry.resolveSetting("Game Boy Color (CGB)"));
        assertTrue(unknown.getMessage().contains("[dmg, cgb, cgb0, sgb]"));
        assertThrows(IllegalArgumentException.class, () -> HardwareProfileRegistry.resolve("CGB"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> HardwareProfileRegistry.resolve(null)).getMessage()
                .contains("[dmg, cgb, cgb0, sgb]"));
    }

    @Test
    public void deprecatedGameboyTypeIsABidirectionalCompatibilityAdapter() {
        assertSame(HardwareProfileRegistry.DMG, GameboyType.DMG.toHardwareProfile());
        assertSame(HardwareProfileRegistry.CGB, GameboyType.CGB.toHardwareProfile());
        assertSame(HardwareProfileRegistry.SGB, GameboyType.SGB.toHardwareProfile());
        assertEquals(GameboyType.CGB, GameboyType.fromHardwareProfile(HardwareProfileRegistry.CGB0));
    }

    @Test
    public void capabilitiesAndBootPolicyLockCurrentFourProfileBehavior() {
        assertEquals(HardwareProfile.Family.DMG, HardwareProfileRegistry.DMG.family());
        assertEquals(HardwareProfile.Family.CGB, HardwareProfileRegistry.CGB.family());
        assertEquals(HardwareProfile.Family.CGB, HardwareProfileRegistry.CGB0.family());
        assertEquals(HardwareProfile.Family.SGB, HardwareProfileRegistry.SGB.family());
        assertTrue(HardwareProfileRegistry.CGB.capabilities().doubleSpeed());
        assertTrue(HardwareProfileRegistry.CGB.capabilities().infrared());
        assertTrue(HardwareProfileRegistry.SGB.capabilities().superGameboyCommands());
        assertTrue(HardwareProfileRegistry.SGB.capabilities().superGameboyBorder());
        assertEquals(10, HardwareProfileRegistry.CGB.bootSpec().authenticDivPreset());
        assertEquals(536, HardwareProfileRegistry.CGB0.bootSpec().authenticDivPreset());
        assertEquals(12, HardwareProfileRegistry.CGB.bootSpec().cgbBootHandoffTicks());
        assertEquals(0, HardwareProfileRegistry.CGB0.bootSpec().cgbBootHandoffTicks());
        assertSame(ClockSpec.LEGACY, HardwareProfileRegistry.SGB.clockSpec());
    }

    @Test
    public void registryRejectsLookalikeObjectsAndInvalidIds() {
        HardwareProfile lookalike = new HardwareProfile(
                "dmg", "Lookalike", HardwareProfile.Family.DMG, "dmg",
                new HardwareCapabilities(false, false, false, false, false, false, true),
                ClockSpec.LEGACY, HardwareProfileRegistry.DMG.bootSpec());
        assertThrows(IllegalArgumentException.class,
                () -> HardwareProfileRegistry.requireRegistered(lookalike));
        assertThrows(IllegalArgumentException.class,
                () -> new HardwareProfile("CGB", "Invalid", HardwareProfile.Family.CGB, "cgb",
                        HardwareProfileRegistry.CGB.capabilities(), ClockSpec.LEGACY,
                        HardwareProfileRegistry.CGB.bootSpec()));
        assertThrows(IllegalArgumentException.class,
                () -> new HardwareCapabilities(false, true, true, true,
                        false, false, true));
        assertThrows(IllegalArgumentException.class,
                () -> new HardwareProfileIdentity("CGB", ClockSpec.LEGACY));
        assertThrows(IllegalArgumentException.class,
                () -> new HardwareProfile(
                        "bad-boot", "Bad boot", HardwareProfile.Family.DMG, "bad-boot",
                        HardwareProfileRegistry.DMG.capabilities(), ClockSpec.LEGACY,
                        HardwareProfileRegistry.CGB.bootSpec()));
    }
}
