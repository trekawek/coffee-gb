package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Mode;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import org.junit.Test;

import static org.junit.Assert.*;

/** The owned source interior is independent of CPU speed and never publishes VRAM early. */
public class HdmaOwnedDataPerformanceTest {
    @Test
    public void hblankAndGeneralDmaMatchEverySourcePhaseAndPartialBudget() throws Exception {
        for (int speed : new int[]{1, 2}) {
            for (int topology = 0; topology < 3; topology++) {
                for (int source : new int[]{0xc800, 0x4000}) {
                    Fixture scalar = new Fixture(speed, topology, source);
                    Fixture direct = new Fixture(speed, topology, source);
                    for (int prefix = 0; prefix < 31; prefix++) {
                        var checkpoint = scalar.hdma.captureState();
                        for (int tail = 1; tail <= 31 - prefix; tail++) {
                            scalar.hdma.restoreState(checkpoint);
                            direct.hdma.restoreState(checkpoint);
                            scalar.memory.sourceReads = direct.memory.sourceReads = 0;
                            int horizon = direct.hdma.performanceNativeCgbOwnedDataSpanLimit(tail, direct.lease);
                            assertEquals("owned interior " + speed + '/' + topology + '/' + prefix, tail, horizon);
                            assertEquals("preflight speculated a source read", 0, direct.memory.sourceReads);
                            long generation = direct.hdma.getPpuBusGeneration();
                            for (int tick = 0; tick < tail; tick++) scalar.tick();
                            direct.hdma.advancePerformanceNativeCgbOwnedDataTrusted(tail, direct.lease);
                            assertEquals(tail, direct.hdma.getPpuBusGeneration() - generation);
                            assertEquals("each source slot must read once", scalar.memory.sourceReads,
                                    direct.memory.sourceReads);
                            assertState("owned data state", scalar.hdma.captureState(), direct.hdma.captureState());
                            assertEquals("atomic destination commit escaped its scalar tick", 0,
                                    direct.memory.bytes[0x8000]);
                            assertNull(direct.hdma.consumeSourceBusSample());
                        }
                        scalar.hdma.restoreState(checkpoint);
                        scalar.tick();
                    }
                    assertEquals(0, direct.hdma.performanceNativeCgbOwnedDataSpanLimit(1, direct.lease));
                    assertTrue(scalar.tick());
                    assertTrue(direct.tick());
                    assertState("scalar destination commit", scalar.hdma.captureState(), direct.hdma.captureState());
                    assertArrayEquals(scalar.memory.bytes, direct.memory.bytes);
                    assertEquals(0x50, direct.memory.bytes[0x8000]);
                }
            }
        }
    }

    @Test
    public void startupAndDeviceSourcesNeverEnterTheOwnedInterior() {
        Fixture sourceDevice = new Fixture(1, 1, 0xa000);
        assertFalse(sourceDevice.hdma.isPerformanceNativeCgbOwnedDataStructurallyStable());
        assertEquals(0, sourceDevice.hdma.performanceNativeCgbOwnedDataSpanLimit(31, sourceDevice.lease));
        Fixture rom = new Fixture(1, 1, 0x4000);
        assertTrue(rom.hdma.requiresPerformanceNativeCgbOwnedRomAccess());
        assertEquals(0, rom.hdma.performanceNativeCgbOwnedDataSpanLimit(31, null));
        Fixture halted = new Fixture(1, 0, 0xc800);
        halted.hdma.onCpuHaltState(true);
        assertFalse(halted.hdma.isPerformanceNativeCgbOwnedDataStructurallyStable());
    }

    private static final class Fixture {
        final Memory memory = new Memory();
        final Hdma hdma;
        final PerformanceRomAccess lease;

        Fixture(int speed, int topology, int source) {
            hdma = new Hdma(memory, new SpeedMode(true) {
                @Override public int getSpeedMode() { return speed; }
            });
            for (int i = 0; i < 16; i++) memory.bytes[source + i] = 0x50 + i;
            hdma.setByte(0xff51, source >>> 8);
            hdma.setByte(0xff52, source & 0xf0);
            hdma.setByte(0xff53, 0);
            hdma.setByte(0xff54, 0);
            hdma.onLcdSwitch(topology != 2);
            hdma.onGpuTiming(topology == 2 ? 0 : 1, topology == 2 ? 0 : 300);
            hdma.onGpuUpdate(Mode.HBlank);
            hdma.setByte(0xff55, topology == 0 ? 0x80 : 0);
            assertFalse("startup must remain scalar", hdma.isPerformanceNativeCgbOwnedDataStructurallyStable());
            hdma.resolveCpuRequest(false, false);
            while (((Hdma.HdmaState) hdma.captureState()).tick() < 0) tick();
            lease = source < 0x8000 ? new PerformanceRomAccess() {
                @Override public int physicalOffset(int address) {
                    return address >= 0 && address < 0x8000 ? address : -1;
                }
                @Override public int readPhysicalByte(int offset) { return memory.getByte(offset); }
            } : null;
        }

        boolean tick() {
            boolean complete = hdma.tick();
            hdma.consumeSourceBusSample();
            hdma.advanceHblankRequest();
            return complete;
        }
    }

    private static final class Memory implements AddressSpace {
        final int[] bytes = new int[65536];
        int sourceReads;
        @Override public boolean accepts(int address) { return true; }
        @Override public int getByte(int address) { sourceReads++; return bytes[address]; }
        @Override public void setByte(int address, int value) { bytes[address] = value; }
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
