package eu.rekawek.coffeegb.core.memory.cart.battery;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class MemoryBatteryTest {

    @Test
    public void emptySaveDataIsExpandedToMapperRamSize() {
        MemoryBattery battery = new MemoryBattery(new byte[0]);
        int[] ram = {0xff, 0xff, 0xff, 0xff};

        battery.loadRam(ram);
        assertArrayEquals(new int[4], ram);

        ram[0] = 0x0a;
        battery.saveRam(ram);
    }

    @Test
    public void truncatedSaveDataPreservesAvailableBytesAndZeroFillsTheRest() {
        MemoryBattery battery = new MemoryBattery(new byte[]{1, 2});
        int[] ram = {0xff, 0xff, 0xff, 0xff};

        battery.loadRam(ram);

        assertArrayEquals(new int[]{1, 2, 0, 0}, ram);
    }

    @Test
    public void emptySaveDataIsExpandedToMapperRamAndClockSize() {
        MemoryBattery battery = new MemoryBattery(new byte[0]);
        int[] ram = {0xff, 0xff};
        long[] clock = {1, 2, 3};

        battery.loadRamWithClock(ram, clock);
        assertArrayEquals(new int[2], ram);
        assertArrayEquals(new long[3], clock);

        clock[0] = 42;
        battery.saveRamWithClock(ram, clock);
    }
}
