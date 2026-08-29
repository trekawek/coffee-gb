package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameboyLinkTimingPhaseTest {

    @Test
    public void identicalMachinesTrackTheSameLinkTimingPhase() throws Exception {
        try (Gameboy first = newGameboy(HardwareProfileRegistry.DMG);
             Gameboy second = newGameboy(HardwareProfileRegistry.DMG)) {
            assertTrue(first.hasSameLinkTimingPhase(first));
            assertTrue(first.hasSameLinkTimingPhase(second));
            assertFalse(first.hasSameLinkTimingPhase(null));

            first.tick();
            assertFalse(first.hasSameLinkTimingPhase(second));
            second.tick();
            assertTrue(first.hasSameLinkTimingPhase(second));

            int differentA = (first.getCpu().getRegisters().getA() + 1) & 0xff;
            second.getCpu().getRegisters().setA(differentA);
            assertFalse(first.hasSameLinkTimingPhase(second));
            first.getCpu().getRegisters().setA(differentA);
            assertTrue(first.hasSameLinkTimingPhase(second));

            first.getAddressSpace().setByte(0xff06, 0x5a);
            assertFalse(first.hasSameLinkTimingPhase(second));
            second.getAddressSpace().setByte(0xff06, 0x5a);
            assertTrue(first.hasSameLinkTimingPhase(second));

            first.getAddressSpace().setByte(0xff01, 0xa5);
            assertFalse(first.hasSameLinkTimingPhase(second));
            second.getAddressSpace().setByte(0xff01, 0xa5);
            assertTrue(first.hasSameLinkTimingPhase(second));
        }
    }

    @Test
    public void exactHardwareProfileIsPartOfTheTimingIdentity() throws Exception {
        try (Gameboy dmg = newGameboy(HardwareProfileRegistry.DMG);
             Gameboy cgb = newGameboy(HardwareProfileRegistry.CGB)) {
            assertFalse(dmg.hasSameLinkTimingPhase(cgb));
        }
    }

    @Test
    public void gameboyDelegatesRawActiveTransferRoles() throws Exception {
        try (Gameboy gameboy = newGameboy(HardwareProfileRegistry.DMG)) {
            assertFalse(gameboy.isInternalClockTransferActive());
            assertFalse(gameboy.isExternalClockTransferActive());

            gameboy.getAddressSpace().setByte(0xff02, 0x81);
            assertTrue(gameboy.isInternalClockTransferActive());
            assertFalse(gameboy.isExternalClockTransferActive());

            gameboy.getAddressSpace().setByte(0xff02, 0x80);
            assertFalse(gameboy.isInternalClockTransferActive());
            assertTrue(gameboy.isExternalClockTransferActive());
        }
    }

    private static Gameboy newGameboy(HardwareProfile profile) throws Exception {
        byte[] rom = new byte[0x8000];
        rom[0x0147] = 0;
        return new Gameboy.GameboyConfiguration(new Rom(rom))
                .setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
    }
}
