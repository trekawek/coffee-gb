package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BungEmsTest {

    @Test
    public void selectsRomBanksAndAppliesTheGameMask() throws IOException {
        MemoryController mapper = new BungEms(new Rom(bankMarkedRom("EMSMENU", 0x1b, 0x00)),
                Battery.NULL_BATTERY);

        assertEquals(0, mapper.getByte(0x0000));
        assertEquals(1, mapper.getByte(0x4000));

        mapper.setByte(0x2000, 3);
        assertEquals(3, mapper.getByte(0x4000));
        mapper.setByte(0x6000, 0xff);
        mapper.setByte(0x7000, 0xff);
        assertEquals(0, mapper.getByte(0x0000));
        assertEquals(3, mapper.getByte(0x4000));

        // Unlike MBC1, selecting bank zero is allowed.
        mapper.setByte(0x2000, 0);
        assertEquals(0, mapper.getByte(0x4000));

        // The menu latches the embedded game's base bank in configuration mode. The
        // mask relocates both ROM windows; later game bank writes remain relative to it.
        mapper.setByte(0x1000, 0xa5);
        mapper.setByte(0x2000, 8);
        mapper.setByte(0x7000, 0);
        mapper.setByte(0x1000, 0x98);
        mapper.setByte(0x2000, 1);

        assertEquals(8, mapper.getByte(0x0000));
        assertEquals(9, mapper.getByte(0x4000));
    }

    @Test
    public void selectsTheNinthRomBankBit() throws IOException {
        MemoryController mapper = new BungEms(
                new Rom(bankMarkedRom("EMSMENU", 0x1b, 0x00, 512)),
                Battery.NULL_BATTERY);

        mapper.setByte(0x2000, 1);
        mapper.setByte(0x3000, 1);

        assertEquals(1, mapper.getByte(0x4000));
        assertEquals(1, mapper.getByte(0x4001));
    }

    @Test
    public void exposesFourRamBanksAndHonoursTheEnableRegister() throws IOException {
        MemoryController mapper = new BungEms(new Rom(bankMarkedRom("EMSMENU", 0x1b, 0x00)),
                Battery.NULL_BATTERY);

        // Bung/EMS SRAM is enabled at power-on.
        mapper.setByte(0x4000, 3);
        mapper.setByte(0xa123, 0x5a);
        assertEquals(0x5a, mapper.getByte(0xa123));

        mapper.setByte(0x4000, 0);
        assertEquals(0xff, mapper.getByte(0xa123));

        mapper.setByte(0x0000, 0x00);
        mapper.setByte(0xa123, 0x33);
        assertEquals(0xff, mapper.getByte(0xa123));

        mapper.setByte(0x0000, 0x0a);
        assertEquals(0xff, mapper.getByte(0xa123));
        mapper.setByte(0xa123, 0x33);
        assertEquals(0x33, mapper.getByte(0xa123));
    }

    @Test
    public void mementoRoundTripRestoresMappingAndRam() throws IOException {
        MemoryController mapper = new BungEms(
                new Rom(bankMarkedRom("EMSMENU", 0x1b, 0x00, 512)),
                Battery.NULL_BATTERY);

        mapper.setByte(0x1000, 0xa5);
        mapper.setByte(0x2000, 0x80);
        mapper.setByte(0x7000, 0);
        mapper.setByte(0x3000, 1);
        mapper.setByte(0x2000, 5); // pending mask latch
        mapper.setByte(0x4000, 2);
        mapper.setByte(0xa000, 0x66);
        mapper.setByte(0x0000, 0x00); // save with RAM disabled and config mode active
        Memento<MemoryController> memento = mapper.saveToMemento();

        mapper.setByte(0x1000, 0x98);
        mapper.setByte(0x0000, 0x0a);
        mapper.setByte(0x3000, 0);
        mapper.setByte(0x2000, 3);
        mapper.setByte(0x4000, 0);
        mapper.setByte(0xa000, 0x33);
        mapper.restoreFromMemento(memento);

        assertEquals(0x80, mapper.getByte(0x0000));
        assertEquals(0x85, mapper.getByte(0x4000));
        assertEquals(1, mapper.getByte(0x4001));
        assertEquals(0xff, mapper.getByte(0xa000));

        mapper.setByte(0x0000, 0x0a);
        assertEquals(0x66, mapper.getByte(0xa000));
        mapper.setByte(0x7000, 0); // restored config mode commits the restored pending latch
        assertEquals(5, mapper.getByte(0x0000));
        assertEquals(5, mapper.getByte(0x4000));
        assertEquals(1, mapper.getByte(0x4001));
    }

    @Test
    public void allocatesA32KiBBatteryForDocumentedHeaders() throws IOException {
        byte[] data = bankMarkedRom("EMSMENU", 0x1b, 0x00);
        data[0x149] = 0; // the flashcart header may omit its physical SRAM size
        File romFile = Files.createTempFile("bung-ems", ".gb").toFile();
        File saveFile = Cartridge.getSaveName(romFile);
        try {
            Files.write(romFile.toPath(), data);
            Cartridge cartridge = new Cartridge(new Rom(romFile), true);
            cartridge.setByte(0xa000, 0x5a);
            cartridge.flushBattery();

            assertEquals(0x8000, saveFile.length());
        } finally {
            Files.deleteIfExists(saveFile.toPath());
            Files.deleteIfExists(romFile.toPath());
        }
    }

    @Test
    public void detectsDocumentedBungEmsHeaders() throws IOException {
        assertBungEms(bankMarkedRom("EMSMENU", 0x1b, 0x00));
        assertBungEms(bankMarkedRom("GB16M", 0x1b, 0x00));
        assertBungEms(bankMarkedRom("CART", 0xbe, 0x00));
        assertBungEms(bankMarkedRom("CART", 0x1b, 0xe1));
    }

    @Test
    public void doesNotTreatPocketVoiceAsBungEms() throws IOException {
        Cartridge cartridge = new Cartridge(
                new Rom(bankMarkedRom("Pocket Voice2.0", 0xbe, 0x00)),
                Battery.NULL_BATTERY);

        assertTrue(cartridge.getMemoryController() instanceof Mbc5);
    }

    private static void assertBungEms(byte[] data) throws IOException {
        Cartridge cartridge = new Cartridge(new Rom(data), Battery.NULL_BATTERY);
        assertTrue(cartridge.getMemoryController() instanceof BungEms);
    }

    private static byte[] bankMarkedRom(String title, int type, int region) {
        return bankMarkedRom(title, type, region, 16);
    }

    private static byte[] bankMarkedRom(String title, int type, int region, int banks) {
        byte[] data = new byte[banks * 0x4000];
        for (int bank = 0; bank < data.length / 0x4000; bank++) {
            data[bank * 0x4000] = (byte) bank;
            data[bank * 0x4000 + 1] = (byte) (bank >> 8);
        }
        byte[] titleBytes = title.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(titleBytes, 0, data, 0x134, titleBytes.length);
        data[0x134 + titleBytes.length] = 0;
        data[0x147] = (byte) type;
        data[0x148] = 0x03;
        data[0x149] = 0x03;
        data[0x14a] = (byte) region;
        return data;
    }
}
