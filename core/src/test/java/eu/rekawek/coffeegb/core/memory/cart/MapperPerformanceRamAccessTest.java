package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccess;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccessProvider;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery;
import eu.rekawek.coffeegb.core.memory.cart.type.*;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import org.junit.Test;

import static org.junit.Assert.*;

/** Cartridge RAM leases preserve the mapper's bus semantics, banking and save bookkeeping. */
public class MapperPerformanceRamAccessTest {
    private static final int[] KINDS = {0, 1, 2, 3, 5};

    @Test
    public void sustainedRamReadWriteLoopsKeepFullEpochsAcrossMappersAndCpuClocks() throws Exception {
        for (int kind : KINDS) {
            for (int topology = 0; topology < 4; topology++) {
                Pair pair = new Pair(kind, topology, 0x7e, 0x3c, 0x77, 0x18, 0xfb);
                pair.control(0x0000, 0x0a);
                assertTrue(pair.directBus.acquirePerformanceRomAccess().canAccessRam());
                for (int epoch = 0; epoch < 600; epoch++) {
                    int elapsed = pair.run(54);
                    assertEquals("mapper " + kind + " topology " + topology, 54, elapsed);
                    pair.complete(elapsed);
                }
                assertTrue(pair.directBus.dataReads > 500);
                assertTrue(pair.directBus.dataWrites > 500);
                assertEquals(0, pair.direct.getPerformanceEpochCartWindowFenceAttemptCount());
                pair.assertEquivalent("sustained mapper " + kind + '/' + topology);
                pair.directMapper.flushRam();
                pair.scalarMapper.flushRam();
                assertTrue("RAM dirty bookkeeping was bypassed", pair.directBattery.saves > 0);
                assertState("battery", pair.scalarBattery.captureState(), pair.directBattery.captureState());
            }
        }
    }

    @Test
    public void disabledRamAndBankChangesRetainCanonicalMapperSemantics() throws Exception {
        for (int kind : KINDS) {
            Pair pair = new Pair(kind, 1, 0x7e, 0x3c, 0x77, 0x18, 0xfb);
            for (int bank = 0; bank < 4; bank++) {
                pair.control(0x0000, 0x0a);
                if (kind == 1) pair.control(0x6000, 1);
                if (kind == 1 || kind == 3 || kind == 5) pair.control(0x4000, bank);
                for (int epoch = 0; epoch < 30; epoch++) {
                    assertEquals(54, pair.run(54));
                    pair.complete(54);
                }
                pair.control(0x0000, 0);
                assertEquals(54, pair.run(54));
                pair.complete(54);
                pair.assertEquivalent("disabled/banked " + kind + '/' + bank);
            }
        }
    }

    @Test
    public void ramDataInstructionsPreserveEveryPartialEpochTail() throws Exception {
        for (int kind : KINDS) {
            for (int topology = 0; topology < 4; topology++) {
                Pair pair = new Pair(kind, topology, 0x7e, 0x34, 0x77, 0x18, 0xfb);
                pair.control(0x0000, 0x0a);
                for (int tail = 1; tail <= 54; tail++) {
                    assertEquals(tail, pair.run(tail));
                    pair.complete(tail);
                    assertState("partial RAM CPU " + kind + '/' + topology + '/' + tail,
                            pair.scalar.captureState(), pair.direct.captureState());
                    assertEquals(pair.scalarBus.dataReads, pair.directBus.dataReads);
                    assertEquals(pair.scalarBus.dataWrites, pair.directBus.dataWrites);
                }
                pair.assertEquivalent("partial RAM mapper " + kind + '/' + topology);
            }
        }
    }

    @Test
    public void mbc2NibbleMaskAndMirroringSurviveEpochWritesAndRestore() throws Exception {
        Pair pair = new Pair(2, 1, 0x3e, 0xab, 0x77, 0xfa, 0x00, 0xb0, 0x18, 0xf8);
        pair.control(0x0000, 0x0a);
        assertEquals(32, pair.run(32));
        pair.complete(32);
        assertEquals(0xfb, pair.directMapper.getByte(0xa000));
        assertEquals(0xfb, pair.directMapper.getByte(0xb000));
        assertEquals(0xfb, pair.direct.getRegisters().getA());
        var cpuState = pair.direct.captureState();
        var mapperState = pair.directMapper.captureState();
        assertEquals(54, pair.run(54));
        pair.complete(54);
        var uninterruptedCpu = pair.direct.captureState();
        var uninterruptedMapper = pair.directMapper.captureState();
        pair.direct.restoreState(cpuState);
        pair.directMapper.restoreState(mapperState);
        assertEquals(54, pair.run(54));
        pair.direct.replayPerformanceEpochJournal();
        assertState("MBC2 restored CPU", uninterruptedCpu, pair.direct.captureState());
        assertState("MBC2 restored mapper", uninterruptedMapper, pair.directMapper.captureState());
    }

    @Test
    public void mbc3RtcSelectionRevokesRamLeaseAndControlWriteEndsEpoch() throws Exception {
        Pair pair = new Pair(3, 0, 0x3e, 0x08, 0xea, 0x00, 0x40, 0xfa, 0x00, 0xa0);
        pair.control(0x0000, 0x0a);
        assertTrue(pair.directBus.acquirePerformanceRomAccess().canAccessRam());
        int elapsed = pair.run(54);
        assertTrue(elapsed > 0 && elapsed < 54);
        assertTrue("mapper control must terminate before changing the borrowed window",
                pair.direct.hasPerformanceEpochJournal());
        pair.complete(elapsed);
        assertFalse(pair.directBus.acquirePerformanceRomAccess().canAccessRam());
        assertEquals(0, pair.directBus.dataReads);
        pair.assertEquivalent("MBC3 RTC selected by CPU");

        // The strict physical-DMG owner must stop before touching the observable RTC byte.
        elapsed = pair.direct.runSgbPerformanceEpoch(54);
        pair.complete(elapsed);
        assertTrue(pair.direct.getPerformanceEpochCartWindowFenceAttemptCount() > 0);
        assertEquals("RTC byte was incorrectly treated as SRAM", 0, pair.directBus.dataReads);
        pair.assertEquivalent("MBC3 RTC read fence");
        pair.control(0x0000, 0);
        assertTrue("disabled RTC selection is an inert open bus",
                pair.directBus.acquirePerformanceRomAccess().canAccessRam());
    }

    @Test
    public void capabilityTracksRestoreAndNeverAllowsExecutableRam() throws Exception {
        Pair pair = new Pair(3, 1, 0x00);
        pair.control(0x0000, 0x0a);
        var ramState = pair.directMapper.captureState();
        PerformanceRomAccess lease = pair.directBus.acquirePerformanceRomAccess();
        assertTrue(lease.canAccessRam());
        pair.directMapper.setByte(0x4000, 0x08);
        assertFalse(lease.canAccessRam());
        pair.directMapper.restoreState(ramState);
        assertTrue(lease.canAccessRam());
        pair.direct.getRegisters().setPC(0xa000);
        assertEquals("only the pre-fetch phase may advance", 3, pair.run(54));
        assertEquals("SRAM execution was speculatively read", 0, pair.directBus.dataReads);
        assertEquals(0xa000, pair.direct.getRegisters().getPC());
    }

    @Test
    public void subclassesDebugObserversAndDeviceWindowsRemainOptIn() throws Exception {
        Rom rom = rom(3);
        MemoryController[] subclasses = {
                new BasicRom(rom) {}, new Mbc1(rom, Battery.NULL_BATTERY) {},
                new Mbc2(rom, Battery.NULL_BATTERY) {},
                new Mbc3(rom, Battery.NULL_BATTERY, () -> 0L) {},
                new Mbc5(rom, Battery.NULL_BATTERY) {}
        };
        for (MemoryController mapper : subclasses) {
            assertFalse(mapper.isPerformanceRamAccessSafe());
            assertFalse(new MapperPerformanceRomAccess(mapper).canAccessRam());
        }
        for (int kind : new int[]{1, 3, 5}) {
            MemoryController mapper = mapper(kind, rom(kind), Battery.NULL_BATTERY);
            mapper.setDebugHooks(new TestDebugHooks());
            assertFalse("observed mapper " + kind, mapper.isPerformanceRamAccessSafe());
        }
        Mbc7 eeprom = new Mbc7(rom(5), Battery.NULL_BATTERY);
        assertFalse(eeprom.isPerformanceRamAccessSafe());
        assertFalse(eeprom.acquirePerformanceRomAccess().canAccessRam());
    }

    private static final class Pair {
        final RecordingBattery directBattery = new RecordingBattery();
        final RecordingBattery scalarBattery = new RecordingBattery();
        final MemoryController directMapper;
        final MemoryController scalarMapper;
        final Bus directBus;
        final Bus scalarBus;
        final Cpu direct;
        final Cpu scalar;
        final InterruptManager directInterrupts;
        final InterruptManager scalarInterrupts;
        final int topology;

        Pair(int kind, int topology, int... program) throws Exception {
            this.topology = topology;
            directMapper = mapper(kind, rom(kind, program), directBattery);
            scalarMapper = mapper(kind, rom(kind, program), scalarBattery);
            directBus = new Bus(directMapper);
            scalarBus = new Bus(scalarMapper);
            directInterrupts = new InterruptManager(topology != 0);
            scalarInterrupts = new InterruptManager(topology != 0);
            direct = new Cpu(directBus, directInterrupts, null, speed(topology), new Display(false));
            scalar = new Cpu(scalarBus, scalarInterrupts, null, speed(topology), new Display(false));
            for (Cpu cpu : new Cpu[]{direct, scalar}) {
                cpu.getRegisters().setPC(0x100);
                cpu.getRegisters().setHL(0xa000);
            }
        }

        void control(int address, int value) {
            directMapper.setByte(address, value);
            scalarMapper.setByte(address, value);
        }

        int run(int ticks) {
            return switch (topology) {
                case 0 -> direct.runPhysicalDmgPerformanceEpoch(ticks);
                case 1 -> direct.runNativeCgbNormalSpeedPerformanceEpoch(ticks);
                case 2 -> direct.runNativeCgbPerformanceEpoch(ticks);
                case 3 -> direct.runCgbCompatibilityPerformanceEpoch(ticks);
                default -> throw new AssertionError();
            };
        }

        void complete(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                if (directMapper.isClocked()) directMapper.tick();
                if (scalarMapper.isClocked()) scalarMapper.tick();
                scalar.tick();
            }
            direct.replayPerformanceEpochJournal();
        }

        void assertEquivalent(String label) throws Exception {
            assertState(label + " CPU", scalar.captureState(), direct.captureState());
            assertState(label + " interrupts", scalarInterrupts.captureState(), directInterrupts.captureState());
            assertState(label + " mapper", scalarMapper.captureState(), directMapper.captureState());
            assertEquals(label + " reads", scalarBus.dataReads, directBus.dataReads);
            assertEquals(label + " writes", scalarBus.dataWrites, directBus.dataWrites);
        }
    }

    private static SpeedMode speed(int topology) throws Exception {
        SpeedMode speed = new SpeedMode(topology != 0);
        if (topology == 2) {
            speed.setByte(0xff4d, 1);
            var onStop = SpeedMode.class.getDeclaredMethod("onStop");
            onStop.setAccessible(true);
            assertEquals(true, onStop.invoke(speed));
        } else if (topology == 3) {
            speed.setDmgCompat(true);
        }
        return speed;
    }

    private static final class Bus implements AddressSpace, PerformanceRomAccessProvider {
        final MemoryController mapper;
        final PerformanceRomAccess logical;
        final byte[] ram = new byte[65536];
        int dataReads;
        int dataWrites;

        Bus(MemoryController mapper) {
            this.mapper = mapper;
            logical = new MapperPerformanceRomAccess(mapper);
        }
        @Override public boolean accepts(int address) { return true; }
        @Override public int getByte(int address) {
            if (address >= 0xa000 && address < 0xc000) dataReads++;
            return mapper.accepts(address) ? mapper.getByte(address) : ram[address] & 0xff;
        }
        @Override public void setByte(int address, int value) {
            if (address >= 0xa000 && address < 0xc000) dataWrites++;
            if (mapper.accepts(address)) mapper.setByte(address, value);
            else ram[address] = (byte) value;
        }
        @Override public PerformanceRomAccess acquirePerformanceRomAccess() {
            if (mapper instanceof PerformanceRomAccessProvider provider) {
                PerformanceRomAccess physical = provider.acquirePerformanceRomAccess();
                if (physical != null) return physical;
            }
            return logical;
        }
    }

    private static final class RecordingBattery extends MemoryBattery {
        int saves;
        RecordingBattery() { super(new byte[0]); }
        @Override public void saveRam(int[] ram) { saves++; super.saveRam(ram); }
        @Override public void saveRamWithClock(int[] ram, long[] clock) {
            saves++;
            super.saveRamWithClock(ram, clock);
        }
    }

    private static Rom rom(int kind, int... program) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x147] = (byte) switch (kind) { case 0 -> 0x09; case 1 -> 3;
            case 2 -> 6; case 3 -> 0x10; case 5 -> 0x1b; default -> throw new AssertionError(); };
        image[0x149] = (byte) (kind == 2 ? 0 : 3);
        for (int index = 0; index < program.length; index++) image[0x100 + index] = (byte) program[index];
        return new Rom(image);
    }

    private static MemoryController mapper(int kind, Rom rom, Battery battery) {
        return switch (kind) { case 0 -> new BasicRom(rom, battery); case 1 -> new Mbc1(rom, battery);
            case 2 -> new Mbc2(rom, battery); case 3 -> new Mbc3(rom, battery, () -> 0L);
            case 5 -> new Mbc5(rom, battery); default -> throw new AssertionError(); };
    }

    private static void assertState(String path, Object expected, Object actual) throws Exception {
        if (expected == actual) return;
        assertNotNull(path, expected);
        assertNotNull(path, actual);
        assertEquals(path, expected.getClass(), actual.getClass());
        if (expected.getClass().isArray()) {
            assertEquals(path, Array.getLength(expected), Array.getLength(actual));
            for (int i = 0; i < Array.getLength(expected); i++) {
                assertState(path + '[' + i + ']', Array.get(expected, i), Array.get(actual, i));
            }
        } else if (expected.getClass().isRecord()) {
            for (RecordComponent component : expected.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertState(path + '.' + component.getName(), accessor.invoke(expected), accessor.invoke(actual));
            }
        } else assertEquals(path, expected, actual);
    }
}
