package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class NtNewTest {

    @Test
    public void changesFromA16KiBWindowToIndependent8KiBWindows() throws IOException {
        NtNew mapper = new NtNew(new Rom(romWith8kPageMarkers()), Battery.NULL_BATTERY);

        assertEquals(0, mapper.getByte(0x0000));
        assertEquals(2, mapper.getByte(0x4000));
        assertEquals(3, mapper.getByte(0x6000));

        mapper.setByte(0x2000, 0x10);
        assertEquals(0x20, mapper.getByte(0x4000));
        assertEquals(0x21, mapper.getByte(0x6000));

        mapper.setByte(0x1400, 0x55);
        mapper.setByte(0x2000, 0x04);
        mapper.setByte(0x2400, 0x05);
        assertEquals(0x04, mapper.getByte(0x4000));
        assertEquals(0x05, mapper.getByte(0x6000));
    }

    @Test
    public void restoresTheIndependent8KiBWindowState() throws IOException {
        NtNew mapper = new NtNew(new Rom(romWith8kPageMarkers()), Battery.NULL_BATTERY);
        mapper.setByte(0x1400, 0x55);
        mapper.setByte(0x2000, 0x0a);
        mapper.setByte(0x2400, 0x0b);
        ComponentState<MemoryController> state = mapper.captureState();

        mapper.setByte(0x2000, 0x12);
        mapper.setByte(0x2400, 0x13);
        mapper.restoreState(state);

        assertEquals(0x0a, mapper.getByte(0x4000));
        assertEquals(0x0b, mapper.getByte(0x6000));
    }

    private static byte[] romWith8kPageMarkers() {
        byte[] data = new byte[64 * 0x2000];
        for (int page = 0; page < 64; page++) {
            Arrays.fill(data, page * 0x2000, (page + 1) * 0x2000, (byte) page);
        }
        data[0x0147] = 0x01;
        data[0x0148] = 0x04;
        return data;
    }
}
