package eu.rekawek.coffeegb.memory.cart;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.GameboyOptions;
import eu.rekawek.coffeegb.memory.BootRom;
import eu.rekawek.coffeegb.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.memory.cart.battery.FileBattery;
import eu.rekawek.coffeegb.memory.cart.type.Mbc1;
import eu.rekawek.coffeegb.memory.cart.type.Mbc2;
import eu.rekawek.coffeegb.memory.cart.type.Mbc3;
import eu.rekawek.coffeegb.memory.cart.type.Mbc5;
import eu.rekawek.coffeegb.memory.cart.type.Rom;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Cartridge implements AddressSpace {

    public enum GameboyTypeFlag {
        UNIVERSAL, CGB, NON_CGB;

        private static GameboyTypeFlag getFlag(int value) {
            if (value == 0x80) {
                return UNIVERSAL;
            } else if (value == 0xc0) {
                return CGB;
            } else {
                return NON_CGB;
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(Cartridge.class);

    private final AddressSpace addressSpace;

    private final GameboyTypeFlag gameboyType;

    private final boolean gbc;

    private final String title;

    private int dmgBoostrap;

    public Cartridge(GameboyOptions options) throws IOException {
        File file = options.getRomFile();
        int[] rom = loadFile(file);
        CartridgeType type = CartridgeType.getById(rom[0x0147]);
        title = getTitle(rom);
        LOG.debug("Cartridge {}, type: {}", title, type);
        gameboyType = GameboyTypeFlag.getFlag(rom[0x0143]);
        int romBanks = getRomBanks(rom[0x0148]);
        int ramBanks = getRamBanks(rom[0x0149]);
        if (ramBanks == 0 && type.isRam()) {
            LOG.warn("RAM bank is defined to 0. Overriding to 1.");
            ramBanks = 1;
        }
        LOG.debug("ROM banks: {}, RAM banks: {}", romBanks, ramBanks);

        Battery battery = Battery.NULL_BATTERY;
        if (type.isBattery() && options.isSupportBatterySaves()) {
            battery = new FileBattery(file.getParentFile(), FilenameUtils.removeExtension(file.getName()));
        }

        if (type.isMbc1()) {
            addressSpace = new Mbc1(rom, type, battery, romBanks, ramBanks);
        } else if (type.isMbc2()) {
            addressSpace = new Mbc2(rom, type, battery, romBanks);
        } else if (type.isMbc3()) {
            addressSpace = new Mbc3(rom, type, battery, romBanks, ramBanks);
        } else if (type.isMbc5()) {
            addressSpace = new Mbc5(rom, type, battery, romBanks, ramBanks);
        } else {
            addressSpace = new Rom(rom, type, romBanks, ramBanks);
        }

        dmgBoostrap = options.isUsingBootstrap() ? 0 : 1;
        if (options.isForceCgb()) {
            gbc = true;
        } else if (gameboyType == Cartridge.GameboyTypeFlag.NON_CGB) {
            gbc = false;
        } else if (gameboyType == Cartridge.GameboyTypeFlag.CGB) {
            gbc = true;
        } else { // UNIVERSAL
            gbc = !options.isForceDmg();
        }
    }

    private String getTitle(int[] rom) {
        StringBuilder t = new StringBuilder();
        for (int i = 0x0134; i < 0x0143; i++) {
            char c = (char) rom[i];
            if (c == 0) {
                break;
            }
            t.append(c);
        }
        return t.toString();
    }

    public String getTitle() {
        return title;
    }

    public boolean isGbc() {
        return gbc;
    }

    @Override
    public boolean accepts(int address) {
        return addressSpace.accepts(address) || address == 0xff50;
    }

    @Override
    public void setByte(int address, int value) {
        if (address == 0xff50) {
            dmgBoostrap = 1;
        } else {
            addressSpace.setByte(address, value);
        }
    }

    @Override
    public int getByte(int address) {
        if (dmgBoostrap == 0 && !gbc && (address >= 0x0000 && address < 0x0100)) {
            return BootRom.GAMEBOY_CLASSIC[address];
        } else if (dmgBoostrap == 0 && gbc && address >= 0x000 && address < 0x0100) {
            return BootRom.GAMEBOY_COLOR[address];
        } else if (dmgBoostrap == 0 && gbc && address >= 0x200 && address < 0x0900) {
            return BootRom.GAMEBOY_COLOR[address - 0x0100];
        } else if (address == 0xff50) {
            return 0xff;
        } else {
            return addressSpace.getByte(address);
        }
    }

    private static int[] loadFile(File file) throws IOException {
        String ext = FilenameUtils.getExtension(file.getName());
        try (InputStream is = new FileInputStream(file)) {
            if ("zip".equalsIgnoreCase(ext)) {
                try (ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        String entryExt = FilenameUtils.getExtension(name);
                        if (Stream.of("gb", "gbc", "rom").anyMatch(e -> e.equalsIgnoreCase(entryExt))) {
                            return load(zis, (int) entry.getSize());
                        }
                        zis.closeEntry();
                    }
                }
                throw new IllegalArgumentException("Can't find ROM file inside the zip.");
            } else {
                return load(is, (int) file.length());
            }
        }
    }

    private static int[] load(InputStream is, int length) throws IOException {
        byte[] byteArray = IOUtils.toByteArray(is, length);
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

            case 7:
                return 256;

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
