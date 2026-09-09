package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.Bios;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import static org.junit.Assert.*;

public class GameboySkippedBootRegistersTest {
    @Test
    public void fixedIoRegistersMatchACompletedBootAcrossBundledProfiles() throws Exception {
        for (HardwareProfile profile : HardwareProfileRegistry.supportedProfiles()) {
            if (!Bios.hasBundledBootRom(profile)) continue;
            for (boolean color : new boolean[]{false, true}) {
                if (color && !profile.capabilities().cgbMode()) continue;
                Rom rom = new Rom(romImage(color));
                try (Gameboy skip = configuration(rom, profile, BootstrapMode.SKIP).build();
                     Gameboy boot = configuration(rom, profile, BootstrapMode.FAST_FORWARD).build()) {
                    // FAST_FORWARD stops during the final LDH (FF50),A. Finish that write,
                    // without fetching or executing any cartridge instruction.
                    for (int tick = 0; !boot.isBootstrapReady() && tick < 8; tick++) boot.tick();
                    assertTrue(boot.isBootstrapReady());
                    assertEquals(0x100, boot.getCpu().getRegisters().getPC());
                    assertEquals(boot.captureDebugSnapshot(0, 0, 0, 0, 0, false).registers(),
                            skip.captureDebugSnapshot(0, 0, 0, 0, 0, false).registers());
                    for (int address = 0xff00; address < 0xff80; address++) {
                        // These values depend on the boot duration; SKIP retains its timing grid.
                        if (address == 0xff04 || address == 0xff41 || address == 0xff44) continue;
                        assertEquals(profile.id() + " color=" + color + " register "
                                        + Integer.toHexString(address),
                                boot.getAddressSpace().getByte(address), skip.getAddressSpace().getByte(address));
                    }
                    assertEquals(boot.getAddressSpace().getByte(0xff41) & 0xf8,
                            skip.getAddressSpace().getByte(0xff41) & 0xf8);
                    assertEquals(boot.getAddressSpace().getByte(0xffff), skip.getAddressSpace().getByte(0xffff));
                    if (profile.capabilities().cgbMode()) {
                        assertPalettesEqual(boot, skip);
                        assertFalse(skip.getHdma().hasActiveOrPendingTransfer());
                    }
                }
            }
        }
    }

    @Test
    public void cgbCompatibilityCpuRegistersFollowTheNintendoLicenseeAndTitle() throws Exception {
        for (int licensee : new int[]{0x01, 0x33, 0x08}) {
            byte[] image = romImage(false);
            byte[] title = "TETRIS".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(title, 0, image, 0x134, title.length);
            image[0x14b] = (byte) licensee;
            image[0x144] = '0';
            image[0x145] = '1';
            updateHeaderChecksum(image);
            Rom rom = new Rom(image);
            try (Gameboy skip = configuration(rom, HardwareProfileRegistry.CGB, BootstrapMode.SKIP).build();
                 Gameboy boot = configuration(rom, HardwareProfileRegistry.CGB, BootstrapMode.FAST_FORWARD).build()) {
                for (int tick = 0; !boot.isBootstrapReady() && tick < 8; tick++) boot.tick();
                assertTrue(boot.isBootstrapReady());
                assertEquals(boot.captureDebugSnapshot(0, 0, 0, 0, 0, false).registers(),
                        skip.captureDebugSnapshot(0, 0, 0, 0, 0, false).registers());
            }
        }
    }

    @Test
    public void skippedBootDoesNotRunTheChimeOrStartDmaAndSurvivesStateRestore() throws Exception {
        for (HardwareProfile profile : HardwareProfileRegistry.supportedProfiles()) {
            try (Gameboy gameboy = configuration(new Rom(romImage(profile.capabilities().cgbMode())),
                    profile, BootstrapMode.SKIP).build()) {
                AddressSpace bus = gameboy.getAddressSpace();
                assertEquals(0xbf, bus.getByte(0xff11));
                assertEquals(0xf3, bus.getByte(0xff12));
                assertEquals(profile.capabilities().superGameboyCommands() ? 0xf0 : 0xf1, bus.getByte(0xff26));
                assertEquals(0xfc, bus.getByte(0xff47));
                var state = gameboy.captureStateWithoutTimeSource();
                bus.setByte(0xff26, 0);
                bus.setByte(0xff47, 0);
                gameboy.restoreState(state);
                assertEquals(0xf3, bus.getByte(0xff12));
                assertEquals(0xfc, bus.getByte(0xff47));
                // A faded envelope has zero digital amplitude even while NR52 says CH1 is on.
                for (int tick = 0; tick < 4096; tick++) {
                    gameboy.tick();
                    if (profile.capabilities().cgbMode()) assertEquals(0, bus.getByte(0xff76));
                }
                assertFalse(gameboy.getHdma().hasActiveOrPendingTransfer());
            }
        }
    }

    private static void assertPalettesEqual(Gameboy expected, Gameboy actual) {
        for (int indexRegister : new int[]{0xff68, 0xff6a}) {
            int index = actual.getAddressSpace().getByte(indexRegister);
            for (int i = 0; i < 64; i++) {
                expected.getAddressSpace().setByte(indexRegister, i);
                actual.getAddressSpace().setByte(indexRegister, i);
                assertEquals("palette byte " + i, expected.getAddressSpace().getByte(indexRegister + 1),
                        actual.getAddressSpace().getByte(indexRegister + 1));
            }
            actual.getAddressSpace().setByte(indexRegister, index);
        }
    }

    private static GameboyConfiguration configuration(Rom rom, HardwareProfile profile, BootstrapMode mode) {
        return new GameboyConfiguration(rom).setHardwareProfile(profile).setBootstrapMode(mode)
                .setSupportBatterySave(false);
    }

    private static byte[] romImage(boolean color) {
        byte[] image = new byte[0x8000];
        int[] logo = {
                0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
                0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
                0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
                0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
                0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
                0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e,
        };
        for (int index = 0; index < logo.length; index++) {
            image[0x104 + index] = (byte) logo[index];
        }
        image[0x100] = (byte) 0xc3; // JP 0100, keep the post-boot fixture alive
        image[0x101] = 0x00;
        image[0x102] = 0x01;
        image[0x143] = color ? (byte) 0x80 : 0;
        image[0x147] = 0x00;
        image[0x148] = 0x00;
        image[0x149] = 0x00;
        updateHeaderChecksum(image);
        return image;
    }

    private static void updateHeaderChecksum(byte[] image) {
        int checksum = 0;
        for (int address = 0x134; address <= 0x14c; address++) {
            checksum = (checksum - (image[address] & 0xff) - 1) & 0xff;
        }
        image[0x14d] = (byte) checksum;
    }

}
