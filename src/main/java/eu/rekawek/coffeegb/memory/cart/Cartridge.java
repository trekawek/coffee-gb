package eu.rekawek.coffeegb.memory.cart;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memory.BootRom;
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

    private final CartridgeType type;

    private final int romBanks;

    private final int ramBanks;

    private int dmgBoostrap;

    public Cartridge(File file) throws IOException {
        this.rom = loadFile(file);
        this.type = CartridgeType.getById(rom[0x0147]);
        LOG.info("Cartridge type: {}", type);
        this.romBanks = getRomBanks(rom[0x0148]);
        this.ramBanks = getRamBanks(rom[0x0149]);
        LOG.info("ROM banks: {}, RAM banks: {}", romBanks, ramBanks);
        this.switchableRam = new int[0x2000];
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) ||
               (address >= 0xa000 && address < 0xc000) ||
                address == 0xff50;
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0xa000 && address < 0xc000) {
            switchableRam[address - 0xa000] = value;
        } else if (address == 0xff50) {
            dmgBoostrap = value;
        } else {
            LOG.warn("Can't write {} to ROM {}", value, address);
        }
    }

    @Override
    public int getByte(int address) {
        if (dmgBoostrap == 0 && (address >= 0x0000 && address < 0x0100)) {
            return BootRom.GAMEBOY_CLASSIC[address];
        } else if (address >= 0xa000 && address < 0xc000) {
            return switchableRam[address - 0xa000];
        } else if (address == 0xff50) {
            return dmgBoostrap;
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

    private static int getRomBanks(int id) {
        switch (id) {
            case 0:
                return 2;

            case 1:
                return 4;

            case 2:
                return 8;

            case 3:
                return 16;

            case 4:
                return 32;

            case 5:
                return 64;

            case 6:
                return 128;

            case 0x52:
                return 72;

            case 0x53:
                return 80;

            case 0x54:
                return 96;

            default:
                throw new IllegalArgumentException("Unsupported ROM size: " + Integer.toHexString(id));
        }
    }

    private static int getRamBanks(int id) {
        switch (id) {
            case 0:
                return 0;

            case 1:
                return 1;

            case 2:
                return 1;

            case 3:
                return 4;

            case 4:
                return 16;

            default:
                throw new IllegalArgumentException("Unsupported RAM size: " + Integer.toHexString(id));
        }
    }
}
