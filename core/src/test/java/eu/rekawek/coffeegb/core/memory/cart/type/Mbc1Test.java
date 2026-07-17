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
        Mbc1 mapper = mapper(mbc1Rom("WORK MASTER 1.00", 0x80000));

        mapper.setByte(0xa123, 0x5a);

        assertEquals(0x5a, mapper.getByte(0xa123));
    }

    @Test
    public void regularMbc1RamRemainsDisabledAtPowerOn() throws IOException {
        Mbc1 mapper = mapper(mbc1Rom("REGULAR CART", 0x80000));

        mapper.setByte(0xa123, 0x5a);

        assertEquals(0xff, mapper.getByte(0xa123));
    }

    @Test
    public void workMasterCanStillDisableAndEnableRam() throws IOException {
        Mbc1 mapper = mapper(mbc1Rom("WORK MASTER 1.00", 0x80000));
        mapper.setByte(0xa123, 0x5a);

        mapper.setByte(0x0000, 0x00);
        assertEquals(0xff, mapper.getByte(0xa123));
        mapper.setByte(0x0000, 0x0a);
        assertEquals(0x5a, mapper.getByte(0xa123));
    }

    @Test
    public void hongKongPokemonRedKeepsItsWideRomBankAfterUpperRegisterWrites()
            throws IOException {
        Mbc1 mapper = mapper(pokemonRedRom());

        mapper.setByte(0x2000, 0x21);
        assertEquals(0x21, mapper.getByte(0x4000));

        mapper.setByte(0x2000, 0x10);
        mapper.setByte(0x4000, 2);
        assertEquals(0x10, mapper.getByte(0x4000));

        mapper.setByte(0x2000, 0x21);
        assertEquals(0x21, mapper.getByte(0x4000));
    }

    @Test
    public void upperRegisterStillDisablesWideBankDetectionForRegularMbc1Roms()
            throws IOException {
        Mbc1 mapper = mapper(bankMarkedRom());

        mapper.setByte(0x2000, 0x21);
        assertEquals(0x21, mapper.getByte(0x4000));

        mapper.setByte(0x4000, 2);
        assertEquals(1, mapper.getByte(0x4000));
    }

    private static Mbc1 mapper(byte[] data) throws IOException {
        return new Mbc1(new Rom(data), Battery.NULL_BATTERY);
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

    private static byte[] pokemonRedRom() {
        byte[] data = bankMarkedRom();
        byte[] title = "POCKETMON BE".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x134, title.length);
        data[0x14e] = (byte) 0x9c;
        data[0x14f] = (byte) 0x8c;
        return data;
    }

    private static byte[] bankMarkedRom() {
        byte[] data = new byte[64 * 0x4000];
        for (int bank = 0; bank < 64; bank++) {
            data[bank * 0x4000] = (byte) bank;
        }
        data[0x147] = 0x03; // MBC1 + RAM + battery
        data[0x148] = 0x05; // 1 MiB / 64 ROM banks
        data[0x149] = 0x03; // 32 KiB / four RAM banks
        return data;
    }
}
