package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class GameboyDebugViewTest {

    @Test
    public void snapshotCombinesCallerMetadataWithOneScalarMachineView() throws Exception {
        try (Gameboy gameboy = gameboy()) {
            gameboy.enableDebugRetirementTracking();
            for (int i = 0; i < 4; i++) {
                gameboy.tick();
            }

            var snapshot = gameboy.captureDebugSnapshot(7, 11, 101, 3, 17, true);

            assertEquals(7, snapshot.sessionGeneration());
            assertEquals(11, snapshot.sequence());
            assertEquals(101, snapshot.masterTick());
            assertEquals(3, snapshot.frame());
            assertEquals(17, snapshot.framePosition());
            assertEquals(gameboy.getCpu().getRegisters().getPC(), snapshot.registers().pc());
            assertEquals(gameboy.getDebugRetirementSequence(),
                    snapshot.execution().retiredInstructions());
            assertEquals(-1, snapshot.apu().frameSequencerStep());
            assertFalse(snapshot.mapper().mapperId().isBlank());
            assertEquals(-1, snapshot.mapper().romBank());
            assertEquals(-1, snapshot.mapper().ramBank());
        }
    }

    @Test
    public void memoryViewAllowsOnlyExplicitPureRegions() throws Exception {
        try (Gameboy gameboy = gameboy()) {
            gameboy.getAddressSpace().setByte(0xc000, 0x12);
            gameboy.getAddressSpace().setByte(0xc001, 0x34);
            gameboy.getAddressSpace().setByte(0xff80, 0x56);

            var workRam = gameboy.readDebugMemory(
                    new DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0xc000, 2));
            assertEquals(0x12, workRam.unsignedByteAt(0));
            assertEquals(0x34, workRam.unsignedByteAt(1));
            assertEquals(0x12, gameboy.readDebugMemory(
                    new DebugMemoryRequest(DebugAddressSpace.WORK_RAM, 0xe000, 1))
                    .unsignedByteAt(0));
            assertEquals(0x56, gameboy.readDebugMemory(
                    new DebugMemoryRequest(DebugAddressSpace.HIGH_RAM, 0xff80, 1))
                    .unsignedByteAt(0));
            assertEquals(0x5a, gameboy.readDebugMemory(
                    new DebugMemoryRequest(DebugAddressSpace.ROM, 0x0200, 1))
                    .unsignedByteAt(0));
            assertEquals(0xff, gameboy.readDebugMemory(
                    new DebugMemoryRequest(DebugAddressSpace.ROM, 0x8000, 1))
                    .unsignedByteAt(0));

            assertThrows(IllegalArgumentException.class, () -> gameboy.readDebugMemory(
                    new DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0xfdff, 2)));
            assertThrows(IllegalArgumentException.class, () -> gameboy.readDebugMemory(
                    new DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0xff0f, 1)));
            assertThrows(UnsupportedOperationException.class, () -> gameboy.readDebugMemory(
                    new DebugMemoryRequest(DebugAddressSpace.IO_REGISTERS, 0xff0f, 1)));
        }
    }

    @Test
    public void romViewUsesTheParserCorrectedImageRatherThanClaimingExactSourceBytes()
            throws Exception {
        byte[] rom = testRom();
        int correctedChecksum = headerChecksum(rom);
        rom[0x014d] = (byte) (correctedChecksum ^ 0xff);
        int sourceChecksum = rom[0x014d] & 0xff;

        try (Gameboy gameboy = gameboy(rom)) {
            assertNotEquals(correctedChecksum, sourceChecksum);
            assertEquals(correctedChecksum, gameboy.readDebugMemory(
                    new DebugMemoryRequest(DebugAddressSpace.ROM, 0x014d, 1))
                    .unsignedByteAt(0));
            assertEquals(sourceChecksum, rom[0x014d] & 0xff);
        }
    }

    private static Gameboy gameboy() throws Exception {
        return gameboy(testRom());
    }

    private static Gameboy gameboy(byte[] rom) throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(rom))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
    }

    private static byte[] testRom() {
        byte[] rom = new byte[0x8000];
        rom[0x147] = 0;
        rom[0x200] = 0x5a;
        return rom;
    }

    private static int headerChecksum(byte[] rom) {
        int checksum = 0;
        for (int address = 0x0134; address <= 0x014c; address++) {
            checksum = (checksum - (rom[address] & 0xff) - 1) & 0xff;
        }
        return checksum;
    }
}
