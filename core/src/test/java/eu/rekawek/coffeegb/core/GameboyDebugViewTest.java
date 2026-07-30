package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugAnchoredMemoryRequest;
import eu.rekawek.coffeegb.core.debug.DebugGraphicsHardwareMode;
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor;
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest;
import eu.rekawek.coffeegb.core.debug.DebugInspectionSection;
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
            assertEquals(gameboy.getSound().getDebugFrameSequencerStep(),
                    snapshot.apu().frameSequencerStep());
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
    public void inspectionCapturesRequestedPhysicalGraphicsAndAudioOnly() throws Exception {
        try (Gameboy gameboy = gameboy()) {
            gameboy.enableDebugRetirementTracking();
            gameboy.getGpu().getVideoRam0().setByte(0x8000, 0x12);
            gameboy.getAddressSpace().setByte(0xff40, 0);
            gameboy.getAddressSpace().setByte(0xfe00, 0x34);
            gameboy.getAddressSpace().setByte(0xff47, 0xe4);
            gameboy.getAddressSpace().setByte(0xff30, 0x56);
            var snapshot = gameboy.captureDebugSnapshot(9, 12, 0, 0, 0, true);
            var request = new DebugInspectionRequest(
                    List.of(), List.of(), EnumSet.allOf(DebugInspectionSection.class));

            var result = gameboy.inspectDebugMemory(snapshot, request);

            var graphics = result.graphics().orElseThrow();
            assertEquals(DebugGraphicsHardwareMode.DMG, graphics.hardwareMode());
            assertEquals(0x12, graphics.vramBank0().unsignedByteAt(0));
            assertEquals(0, graphics.vramBank1().length());
            assertEquals(0x34, graphics.oam().unsignedByteAt(0));
            assertEquals(0xe4, graphics.bgp());
            assertEquals(0, graphics.cgbBackgroundPalette().length());

            var audio = result.audio().orElseThrow();
            assertEquals(4, audio.channels().size());
            assertEquals(0x56, audio.waveRam().unsignedByteAt(0));
            assertEquals(0x77, audio.nr50());
            assertEquals(result.snapshot().apu().frameSequencerStep(),
                    audio.frameSequencerStep());
            assertFalse(result.trace().isPresent());
        }
    }

    @Test
    public void peripheralInspectionBypassesActiveCpuBusLocksWithoutMutatingStorage()
            throws Exception {
        try (Gameboy gameboy = gameboy()) {
            gameboy.enableDebugRetirementTracking();
            var bus = gameboy.getAddressSpace();
            int lcdc = bus.getByte(0xff40);
            bus.setByte(0xff40, 0);
            bus.setByte(0x8000, 0x12);
            bus.setByte(0xfe00, 0x34);
            for (int address = 0xff30; address <= 0xff3f; address++) {
                bus.setByte(address, 0x56);
            }
            bus.setByte(0xff1a, 0x80);
            bus.setByte(0xff1c, 0x20);
            bus.setByte(0xff1d, 0x00);
            bus.setByte(0xff1e, 0x80);
            bus.setByte(0xff40, lcdc);

            boolean locked = false;
            for (int tick = 0; tick < 2_000; tick++) {
                gameboy.tick();
                if (gameboy.getGpu().getMode() == Mode.PixelTransfer
                        && bus.getByte(0xff30) == 0xff) {
                    locked = true;
                    break;
                }
            }
            assertTrue("PPU and active CH3 did not reach their CPU-facing locks", locked);
            assertEquals(0xff, bus.getByte(0x8000));
            assertEquals(0xff, bus.getByte(0xfe00));

            var snapshot = gameboy.captureDebugSnapshot(9, 12, 0, 0, 0, true);
            var request = new DebugInspectionRequest(
                    List.of(), List.of(), EnumSet.allOf(DebugInspectionSection.class));
            var inspection = gameboy.inspectDebugMemory(snapshot, request);

            assertEquals(0x12,
                    inspection.graphics().orElseThrow().vramBank0().unsignedByteAt(0));
            assertEquals(0x34,
                    inspection.graphics().orElseThrow().oam().unsignedByteAt(0));
            assertEquals(0x56,
                    inspection.audio().orElseThrow().waveRam().unsignedByteAt(0));
            assertTrue(inspection.audio().orElseThrow().channels().get(2).enabled());
            assertEquals(Mode.PixelTransfer, gameboy.getGpu().getMode());
            assertEquals(0xff, bus.getByte(0x8000));
            assertEquals(0xff, bus.getByte(0xfe00));
            assertEquals(0xff, bus.getByte(0xff30));

            bus.setByte(0xff40, 0);
            bus.setByte(0xff1a, 0);
            assertEquals(0x12, bus.getByte(0x8000));
            assertEquals(0x34, bus.getByte(0xfe00));
            assertEquals(0x56, bus.getByte(0xff30));
        }
    }

    @Test
    public void cgbGraphicsInspectionCopiesBothBanksAndRgb555PaletteStorage() throws Exception {
        try (Gameboy gameboy = cgbGameboy()) {
            gameboy.getGpu().getVideoRam0().setByte(0x8000, 0x12);
            gameboy.getGpu().getVideoRam1().setByte(0x8000, 0x34);
            gameboy.getAddressSpace().setByte(0xff40, 0);
            gameboy.getAddressSpace().setByte(0xff4f, 1);
            gameboy.getAddressSpace().setByte(0xff68, 0);
            gameboy.getAddressSpace().setByte(0xff69, 0x78);
            gameboy.getAddressSpace().setByte(0xff68, 1);
            gameboy.getAddressSpace().setByte(0xff69, 0x56);
            var snapshot = gameboy.captureDebugSnapshot(9, 12, 0, 0, 0, true);
            var request = new DebugInspectionRequest(
                    List.of(), List.of(), EnumSet.of(DebugInspectionSection.GRAPHICS));

            var graphics = gameboy.inspectDebugMemory(snapshot, request).graphics().orElseThrow();

            assertEquals(DebugGraphicsHardwareMode.CGB_NATIVE, graphics.hardwareMode());
            assertEquals(1, graphics.selectedVramBank());
            assertEquals(0x12, graphics.vramBank0().unsignedByteAt(0));
            assertEquals(0x34, graphics.vramBank1().unsignedByteAt(0));
            assertEquals(0x78, graphics.cgbBackgroundPalette().unsignedByteAt(0));
            assertEquals(0x56, graphics.cgbBackgroundPalette().unsignedByteAt(1));
            assertEquals(0x41, graphics.bgPaletteIndex());
        }
    }

    @Test
    public void cgbCompatibilityInspectionRetainsPhysicalBanksAndPalettes() throws Exception {
        try (Gameboy gameboy = cgbCompatibilityGameboy()) {
            gameboy.getGpu().getVideoRam0().setByte(0x8000, 0x12);
            gameboy.getGpu().getVideoRam1().setByte(0x8000, 0x34);
            gameboy.getAddressSpace().setByte(0xff40, 0);
            gameboy.getAddressSpace().setByte(0xff4f, 1);
            gameboy.getAddressSpace().setByte(0xff68, 0);
            gameboy.getAddressSpace().setByte(0xff69, 0x78);
            gameboy.getAddressSpace().setByte(0xff68, 1);
            gameboy.getAddressSpace().setByte(0xff69, 0x56);
            gameboy.getAddressSpace().setByte(0xff6a, 0);
            gameboy.getAddressSpace().setByte(0xff6b, 0x9a);
            gameboy.getAddressSpace().setByte(0xff6a, 1);
            gameboy.getAddressSpace().setByte(0xff6b, 0xbc);
            var snapshot = gameboy.captureDebugSnapshot(9, 12, 0, 0, 0, true);
            var request = new DebugInspectionRequest(
                    List.of(), List.of(), EnumSet.of(DebugInspectionSection.GRAPHICS));

            var graphics = gameboy.inspectDebugMemory(snapshot, request).graphics().orElseThrow();

            assertTrue(gameboy.getGpu().isDmgCompatMode());
            assertEquals(DebugGraphicsHardwareMode.CGB_COMPATIBILITY, graphics.hardwareMode());
            assertEquals(0, graphics.selectedVramBank());
            assertEquals(0x12, graphics.vramBank0().unsignedByteAt(0));
            assertEquals(0x34, graphics.vramBank1().unsignedByteAt(0));
            assertEquals(0x78, graphics.cgbBackgroundPalette().unsignedByteAt(0));
            assertEquals(0x56, graphics.cgbBackgroundPalette().unsignedByteAt(1));
            assertEquals(0x9a, graphics.cgbObjectPalette().unsignedByteAt(0));
            assertEquals(0xbc, graphics.cgbObjectPalette().unsignedByteAt(1));
            assertEquals(0x41, graphics.bgPaletteIndex());
            assertEquals(0x41, graphics.objectPaletteIndex());
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

    private static Gameboy cgbGameboy() throws Exception {
        byte[] rom = testRom();
        rom[0x143] = (byte) 0x80;
        return new Gameboy.GameboyConfiguration(new Rom(rom))
                .setHardwareProfile(HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy cgbCompatibilityGameboy() throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(testRom()))
                .setHardwareProfile(HardwareProfileRegistry.CGB)
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
