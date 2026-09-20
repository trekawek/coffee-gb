package eu.rekawek.coffeegb.core.state.bess;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GameboyBessTest {

    @Test
    public void cgbRoundTripPreservesPhysicalBanksPalettesAndWriteOnlySoundRegisters() throws Exception {
        try (var source = machine(true); var restored = machine(true)) {
            var bus = source.getAddressSpace();
            bus.setByte(0xff40, 0);
            bus.setByte(0xc123, 0x52);
            for (int bank = 1; bank < 8; bank++) {
                bus.setByte(0xff70, bank);
                bus.setByte(0xd123, 0x20 + bank);
            }
            for (int bank = 0; bank < 2; bank++) {
                bus.setByte(0xff4f, bank);
                bus.setByte(0x8123, 0x40 + bank);
            }
            bus.setByte(0xff68, 0x80);
            bus.setByte(0xff6a, 0x80);
            for (int i = 0; i < 64; i++) {
                bus.setByte(0xff69, i);
                bus.setByte(0xff6b, 63 - i);
            }
            bus.setByte(0xfe12, 0x65);
            bus.setByte(0xffa2, 0x98);
            bus.setByte(0xff13, 0x45);
            bus.setByte(0xff14, 0x83);
            bus.setByte(0xff1a, 0);
            for (int i = 0; i < 16; i++) bus.setByte(0xff30 + i, 0x80 + i);

            BessState saved = BessCodec.read(BessCodec.write(source.captureBessState()));
            assertEquals(0x45, saved.core().io()[0x13] & 0xff);
            restored.restoreBessState(saved);
            BessState loaded = restored.captureBessState();
            assertArrayEquals(saved.core().ram(), loaded.core().ram());
            assertArrayEquals(saved.core().vram(), loaded.core().vram());
            assertArrayEquals(saved.core().oam(), loaded.core().oam());
            assertArrayEquals(saved.core().hram(), loaded.core().hram());
            assertArrayEquals(saved.core().bgPalettes(), loaded.core().bgPalettes());
            assertArrayEquals(saved.core().objPalettes(), loaded.core().objPalettes());
            assertEquals(7, restored.getAddressSpace().getByte(0xff70) & 7);
            assertEquals(1, restored.getAddressSpace().getByte(0xff4f) & 1);
            assertEquals(0x45, loaded.core().io()[0x13] & 0xff);
            assertEquals(0, restored.getAddressSpace().getByte(0xff26) & 15);
            assertArrayEquals(Arrays.copyOfRange(saved.core().io(), 0x30, 0x40),
                    Arrays.copyOfRange(loaded.core().io(), 0x30, 0x40));
        }
    }

    @Test
    public void shortBuffersClearPhysicalBanksAndOversizedBuffersAreTruncated() throws Exception {
        try (var source = machine(true); var restored = machine(true)) {
            var original = source.captureBessState();
            var c = original.core();
            byte[] oam = new byte[0x200];
            Arrays.fill(oam, (byte) 0x45);
            var shortState = new BessState("fixture", original.info(), new BessState.Core(
                    c.model(), c.pc(), c.af(), c.bc(), c.de(), c.hl(), c.sp(), c.ime(), c.ie(),
                    c.executionState(), c.io(), new byte[]{42}, new byte[]{69}, c.mbcRam(),
                    oam, new byte[]{23}, new byte[]{12}, new byte[]{34}), List.of(), Map.of());
            restored.restoreBessState(shortState);
            var result = restored.captureBessState().core();
            assertEquals(42, result.ram()[0]);
            assertEquals(69, result.vram()[0]);
            assertEquals(23, result.hram()[0]);
            assertEquals(0, result.ram()[0x7123]);
            assertEquals(0, result.vram()[0x2123]);
            assertEquals(12, result.bgPalettes()[0]);
            assertEquals(0, result.bgPalettes()[63]);
            assertEquals(34, result.objPalettes()[0]);
            assertEquals(0xa0, result.oam().length);
            assertEquals(0x45, result.oam()[0x9f]);
        }
    }

    @Test
    public void importDoesNotTriggerDmaOrApuAndRestoresDividerAndBootLatch() throws Exception {
        try (var source = machine(true); var restored = machine(true)) {
            var state = source.captureBessState();
            byte[] io = state.core().io();
            io[4] = 0x12;
            io[0x46] = (byte) 0xc0;
            io[0x51] = (byte) 0xc0;
            io[0x52] = 0;
            io[0x53] = 0;
            io[0x54] = 0;
            io[0x55] = 0;
            io[0x14] = io[0x19] = io[0x1e] = io[0x23] = (byte) 0xff;
            io[0x4d] = (byte) 0x80;
            io[0x50] = 0;
            restored.restoreBessState(state);
            assertEquals(0x12, restored.getAddressSpace().getByte(0xff04));
            assertEquals(0, restored.getAddressSpace().getByte(0xff26) & 15);
            assertEquals(0xc0, restored.getAddressSpace().getByte(0xff46));
            assertEquals(0x80, restored.getAddressSpace().getByte(0xff55));
            assertEquals(0x80, restored.getAddressSpace().getByte(0xff4d) & 0x80);
            assertEquals(0xfe, restored.getAddressSpace().getByte(0xff50));
            assertEquals(0x31, restored.getAddressSpace().getByte(0));
            // Saving immediately needs no DMA drain; the CPU is still at exactly this PC.
            assertEquals(state.core().pc(), restored.captureBessState().core().pc());
        }
    }

    @Test
    public void exportCompletesAnInstructionBeforeCapturingItsPcAndEffects() throws Exception {
        byte[] rom = image(false);
        rom[0x100] = (byte) 0xea; // LD (C000), A
        rom[0x101] = 0;
        rom[0x102] = (byte) 0xc0;
        try (var gameboy = new Gameboy.GameboyConfiguration(new Rom(rom))
                .setSupportBatterySave(false).build()) {
            gameboy.getCpu().getRegisters().setA(0x69);
            gameboy.runTicks(4);
            assertFalse(gameboy.getCpu().isBessBoundary());
            var saved = gameboy.captureBessState();
            assertEquals(0x103, saved.core().pc());
            assertEquals(0x69, saved.core().ram()[0] & 0xff);
        }
    }

    @Test
    public void rejectsDifferentRomAndHardwareBeforeImport() throws Exception {
        try (var dmg = machine(false); var cgb = machine(true)) {
            var state = dmg.captureBessState();
            assertThrows(IllegalArgumentException.class, () -> cgb.restoreBessState(state));
            state.info()[0] = 42;
            assertThrows(IllegalArgumentException.class, () -> dmg.restoreBessState(state));
        }
    }

    @Test
    public void haltedCpuAndImeSurviveImport() throws Exception {
        byte[] rom = image(false);
        rom[0x100] = (byte) 0x76;
        var configuration = new Gameboy.GameboyConfiguration(new Rom(rom)).setSupportBatterySave(false);
        try (var source = configuration.build(); var restored = configuration.build()) {
            source.runTicks(8);
            var state = source.captureBessState();
            assertEquals(1, state.core().executionState());
            restored.restoreBessState(state);
            assertEquals(Cpu.State.HALTED, restored.getCpu().getState());
            assertEquals(state.core().ime(), restored.captureBessState().core().ime());
        }
    }

    @Test
    public void stoppedCpuStaysStoppedAfterImport() throws Exception {
        byte[] rom = image(false);
        rom[0x100] = 0x10;
        var configuration = new Gameboy.GameboyConfiguration(new Rom(rom)).setSupportBatterySave(false);
        try (var source = configuration.build(); var restored = configuration.build()) {
            source.runTicks(16);
            var state = source.captureBessState();
            assertEquals(2, state.core().executionState());
            restored.restoreBessState(state);
            restored.runTicks(32);
            assertEquals(Cpu.State.STOPPED, restored.getCpu().getState());
            assertEquals(state.core().pc(), restored.getCpu().getRegisters().getPC());
        }
    }

    @Test
    public void dmgCompatibilityRetainsAllCgbPhysicalMemoryAndUsesNormalSpeed() throws Exception {
        var configuration = new Gameboy.GameboyConfiguration(new Rom(image(false)))
                .setHardwareProfile(HardwareProfileRegistry.CGB).setSupportBatterySave(false);
        try (var source = configuration.build(); var restored = configuration.build()) {
            source.getGpu().getVideoRam1().setByte(0x8234, 0x42);
            var state = source.captureBessState();
            assertEquals(4, state.core().io()[0x4c]);
            assertEquals(0x8000, state.core().ram().length);
            assertEquals(0x4000, state.core().vram().length);
            restored.restoreBessState(state);
            var result = restored.captureBessState();
            assertEquals(4, result.core().io()[0x4c]);
            assertArrayEquals(state.core().ram(), result.core().ram());
            assertArrayEquals(state.core().vram(), result.core().vram());
            assertEquals(0xff, restored.getAddressSpace().getByte(0xff4d));
        }
    }

    @Test
    public void activeDmgWaveChannelExportsPhysicalWaveRamWithoutCpuReadGates() throws Exception {
        try (var source = machine(false); var restored = machine(false)) {
            var bus = source.getAddressSpace();
            bus.setByte(0xff1a, 0);
            for (int i = 0; i < 16; i++) bus.setByte(0xff30 + i, 0x40 + i);
            bus.setByte(0xff1a, 0x80);
            bus.setByte(0xff1d, 0xfc);
            bus.setByte(0xff1e, 0x87);
            source.runTicks(32);
            var saved = source.captureBessState();
            for (int i = 0; i < 16; i++) assertEquals(0x40 + i, saved.core().io()[0x30 + i]);
            restored.restoreBessState(saved);
            for (int i = 0; i < 16; i++) assertEquals(0x40 + i,
                    restored.getAddressSpace().getByte(0xff30 + i));
        }
    }

    @Test
    public void sgbBuffersAndMultiplayerSurvivePortableAndNativeRoundTrips() throws Exception {
        var configuration = new Gameboy.GameboyConfiguration(new Rom(image(false)))
                .setHardwareProfile(HardwareProfileRegistry.SGB).setSupportBatterySave(false);
        try (var source = configuration.build(); var restored = configuration.build();
                var nativeRestored = configuration.build()) {
            for (var machine : new Gameboy[]{source, restored, nativeRestored}) {
                machine.init(EventBus.NULL_EVENT_BUS, SerialEndpoint.NULL_ENDPOINT, null);
            }
            var base = source.captureBessState();
            assertEquals("SN  ", base.core().model());
            byte[] tiles = new byte[0x2000];
            Arrays.fill(tiles, (byte) 0x55);
            byte[] tilemap = new byte[0x800];
            for (int i = 1; i < tilemap.length; i += 2) tilemap[i] = 0x10;
            byte[] borderPalettes = bessColors(0x80);
            byte[] active = bessColors(0x20);
            for (int i = 1; i < 4; i++) {
                active[i * 8] = active[0];
                active[i * 8 + 1] = active[1];
            }
            byte[] ramPalettes = bessColors(0x1000);
            byte[] attributeMap = new byte[0x168];
            for (int i = 0; i < attributeMap.length; i++) attributeMap[i] = (byte) (i & 3);
            byte[] attributeFiles = new byte[0xfd2];
            for (int i = 0; i < attributeFiles.length; i++) attributeFiles[i] = (byte) i;
            var sgb = new BessState.Sgb(tiles, tilemap, borderPalettes, active, ramPalettes,
                    attributeMap, attributeFiles, 0x43);
            var input = new BessState(base.name(), base.info(), base.core(), base.mbcWrites(),
                    base.extensions(), sgb);
            restored.restoreBessState(BessCodec.read(BessCodec.write(input)));
            nativeRestored.restoreState(restored.captureState());
            var output = nativeRestored.captureBessState().sgb();
            assertArrayEquals(tiles, output.borderTiles());
            assertArrayEquals(tilemap, output.borderTilemap());
            assertArrayEquals(borderPalettes, output.borderPalettes());
            assertArrayEquals(active, output.activePalettes());
            assertArrayEquals(ramPalettes, output.ramPalettes());
            assertArrayEquals(attributeMap, output.attributeMap());
            assertArrayEquals(attributeFiles, output.attributeFiles());
            assertEquals(0x43, output.multiplayerStatus());
            assertEquals(4, nativeRestored.getSgbMultiplayerStatus().playerCount());
            assertEquals(3, nativeRestored.getSgbMultiplayerStatus().selectedPlayer());
        }
    }

    @Test
    public void missingSgbBlockDisablesCommandsAndFlagSurvivesNativeState() throws Exception {
        var configuration = new Gameboy.GameboyConfiguration(new Rom(image(false)))
                .setHardwareProfile(HardwareProfileRegistry.SGB).setSupportBatterySave(false);
        try (var source = configuration.build(); var restored = configuration.build();
                var nativeRestored = configuration.build()) {
            var base = source.captureBessState();
            var noCommands = new BessState(base.name(), base.info(), base.core(), base.mbcWrites(),
                    base.extensions());
            restored.restoreBessState(noCommands);
            nativeRestored.restoreState(restored.captureState());
            requestFourSgbPlayers(source);
            assertEquals(4, source.getSgbMultiplayerStatus().playerCount());
            requestFourSgbPlayers(nativeRestored);
            assertEquals(1, nativeRestored.getSgbMultiplayerStatus().playerCount());
            assertNull(nativeRestored.captureBessState().sgb());
        }
    }

    private static byte[] bessColors(int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) result[i] = (byte) (i & 0x7f);
        return result;
    }

    private static void requestFourSgbPlayers(Gameboy machine) {
        byte[] packet = new byte[16];
        packet[0] = (byte) ((0x11 << 3) | 1);
        packet[1] = 3;
        var bus = machine.getAddressSpace();
        bus.setByte(0xff00, 0x30);
        bus.setByte(0xff00, 0);
        bus.setByte(0xff00, 0x30);
        for (byte value : packet) {
            for (int bit = 0; bit < 8; bit++) {
                bus.setByte(0xff00, (value & (1 << bit)) == 0 ? 0x20 : 0x10);
                bus.setByte(0xff00, 0x30);
            }
        }
        bus.setByte(0xff00, 0x20);
        bus.setByte(0xff00, 0x30);
    }

    private static Gameboy machine(boolean color) throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(image(color)))
                .setHardwareProfile(color ? HardwareProfileRegistry.CGB : HardwareProfileRegistry.DMG)
                .setSupportBatterySave(false).build();
    }

    private static byte[] image(boolean color) {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0;
        image[0x102] = 1;
        image[0x143] = color ? (byte) 0x80 : 0;
        return image;
    }
}
