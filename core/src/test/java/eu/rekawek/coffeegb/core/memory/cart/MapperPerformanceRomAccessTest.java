package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memory.PerformanceRomAccess;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MapperPerformanceRomAccessTest {

    @Test
    public void logicalReadsPreserveMapperSideEffectsExactlyOnce() {
        StatefulRomMapper mapper = new StatefulRomMapper();
        PerformanceRomAccess access = new MapperPerformanceRomAccess(mapper);

        assertEquals(-1, access.physicalOffset(0x1234));
        assertEquals(0xff, access.readPhysicalByte(0x1234));
        assertEquals(0x34, access.readCpuByte(0x1234));
        assertEquals(0x35, access.readCpuByte(0x1234));
        assertEquals(2, mapper.reads);
        assertEquals(-1, access.readCpuByte(-1));
        assertEquals(-1, access.readCpuByte(0x8000));
        assertEquals(2, mapper.reads);
    }

    private static final class StatefulRomMapper implements MemoryController {

        private int reads;

        @Override
        public boolean accepts(int address) {
            return address >= 0 && address < 0x8000;
        }

        @Override
        public void setByte(int address, int value) {
        }

        @Override
        public int getByte(int address) {
            return (address + reads++) & 0xff;
        }

        @Override
        public ComponentState<MemoryController> captureState() {
            return null;
        }

        @Override
        public void restoreState(ComponentState<MemoryController> state) {
        }
    }
}
