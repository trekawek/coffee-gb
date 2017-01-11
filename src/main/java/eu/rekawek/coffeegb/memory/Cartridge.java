package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class Cartridge implements AddressSpace {

    private static final Logger LOG = LoggerFactory.getLogger(Cartridge.class);

    private final int[] rom;

    private final int[] switchableRam;

    public Cartridge(File file) throws IOException {
        this.rom = loadFile(file);
        this.switchableRam = new int[0x2000];
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) ||
               (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0xa000 && address < 0xc000) {
            switchableRam[address - 0xa000] = value;
        } else {
            LOG.warn("Can't write {} to ROM {}", value, address);
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x0100) {
            return BootRom.GAMEBOY_CLASSIC[address];
        } else if (address >= 0xa000 && address < 0xc000) {
            return switchableRam[address - 0xa000];
        } else {
            return rom[address];
        }
    }

    private static int[] loadFile(File file) throws IOException {
        byte[] byteArray;
        try (InputStream is = new FileInputStream(file)) {
            byteArray = IOUtils.toByteArray(is);
        }
        int[] intArray = new int[byteArray.length];
        for (int i = 0; i < byteArray.length; i++) {
            intArray[i] = byteArray[i] & 0xff;
        }
        return intArray;
    }
}
