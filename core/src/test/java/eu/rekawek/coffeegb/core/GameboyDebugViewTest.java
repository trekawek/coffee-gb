package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugAnchoredMemoryRequest;
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor;
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest;
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
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
    public void inspectionCopiesPcStackAndExplicitRangesAgainstOneSnapshot() throws Exception {
        byte[] rom = testRom();
        rom[0x100] = 0x3e;
        rom[0x101] = 0x42;
        try (Gameboy gameboy = gameboy(rom)) {
            gameboy.enableDebugRetirementTracking();
            gameboy.getAddressSpace().setByte(0xfffc, 0x12);
            gameboy.getAddressSpace().setByte(0xfffd, 0x34);
            gameboy.getAddressSpace().setByte(0xc000, 0x56);
            var snapshot = gameboy.captureDebugSnapshot(9, 12, 0, 0, 0, true);
            var request = new DebugInspectionRequest(
                    List.of(
                            new DebugAnchoredMemoryRequest(
                                    DebugInspectionAnchor.PROGRAM_COUNTER, 0, 3),
                            new DebugAnchoredMemoryRequest(
                                    DebugInspectionAnchor.STACK_POINTER, -2, 2)),
                    List.of(new DebugMemoryRequest(
                            DebugAddressSpace.WORK_RAM, 0xc000, 1)));

            var result = gameboy.inspectDebugMemory(snapshot, request);

            assertSame(snapshot, result.snapshot());
            assertSame(request, result.request());
            assertEquals(0x3e, result.anchoredBlocks().get(0).unsignedByteAt(0));
            assertEquals(0x42, result.anchoredBlocks().get(0).unsignedByteAt(1));
            assertEquals(DebugAddressSpace.ROM,
                    result.anchoredBlocks().get(0).addressSpace());
            assertEquals(0x12, result.anchoredBlocks().get(1).unsignedByteAt(0));
            assertEquals(0x34, result.anchoredBlocks().get(1).unsignedByteAt(1));
            assertEquals(0x56, result.memoryBlocks().get(0).unsignedByteAt(0));

            var mixedUnsafeRequest = new DebugInspectionRequest(
                    List.of(),
                    List.of(
                            new DebugMemoryRequest(DebugAddressSpace.WORK_RAM, 0xc000, 1),
                            new DebugMemoryRequest(DebugAddressSpace.IO_REGISTERS, 0xff0f, 1)));
            assertThrows(UnsupportedOperationException.class,
                    () -> gameboy.inspectDebugMemory(snapshot, mixedUnsafeRequest));
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
