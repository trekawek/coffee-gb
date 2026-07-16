package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class Mbc1Test {

    @Test
    public void workMasterRamIsEnabledAtPowerOn() throws IOException {
        Mbc1 mapper = new Mbc1(new Rom(mbc1Rom("WORK MASTER 1.00", 0x80000)),
                Battery.NULL_BATTERY);

        mapper.setByte(0xa123, 0x5a);

        assertEquals(0x5a, mapper.getByte(0xa123));
    }

    @Test
    public void regularMbc1RamRemainsDisabledAtPowerOn() throws IOException {
        Mbc1 mapper = new Mbc1(new Rom(mbc1Rom("REGULAR CART", 0x80000)),
                Battery.NULL_BATTERY);

        mapper.setByte(0xa123, 0x5a);

        assertEquals(0xff, mapper.getByte(0xa123));
    }

    @Test
    public void workMasterCanStillDisableAndEnableRam() throws IOException {
        Mbc1 mapper = new Mbc1(new Rom(mbc1Rom("WORK MASTER 1.00", 0x80000)),
                Battery.NULL_BATTERY);
        mapper.setByte(0xa123, 0x5a);

        mapper.setByte(0x0000, 0x00);
        assertEquals(0xff, mapper.getByte(0xa123));
        mapper.setByte(0x0000, 0x0a);
        assertEquals(0x5a, mapper.getByte(0xa123));
    }

    private static byte[] mbc1Rom(String title, int size) {
        byte[] data = new byte[size];
        byte[] titleBytes = title.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(titleBytes, 0, data, 0x134, titleBytes.length);
        data[0x147] = 0x03; // MBC1+RAM+battery
        data[0x148] = 0x04; // 512 KiB ROM
        data[0x149] = 0x02; // 8 KiB RAM
        return data;
    }
}
