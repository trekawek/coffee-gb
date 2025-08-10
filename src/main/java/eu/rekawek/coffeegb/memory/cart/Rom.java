package eu.rekawek.coffeegb.memory.cart;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Rom {

    private static final Logger LOG = LoggerFactory.getLogger(Rom.class);

    private final String title;

    private final File romFile;

    private final CartridgeType cartridgeType;

    private final int romBanks;

    private final int ramBanks;

    private final int[] rom;

    private final GameboyColorFlag gameboyColorFlag;

    private final boolean superGameboyFlag;

    public Rom(File romFile) throws IOException {
        this(loadFile(romFile), romFile);
    }

    public Rom(byte[] romByteArray) throws IOException {
        this(romByteArray, null);
    }

    public Rom(byte[] romByteArray, File romFile) throws IOException {
        rom = new int[romByteArray.length];
        for (int i = 0; i < romByteArray.length; i++) {
            rom[i] = romByteArray[i] & 0xFF;
        }

        cartridgeType = CartridgeType.getById(rom[0x0147]);
        title = getTitle(rom);
        LOG.debug("Cartridge {}, type: {}", title, cartridgeType);
        gameboyColorFlag = GameboyColorFlag.getFlag(rom[0x0143]);
        superGameboyFlag = rom[0x0146] == 0x03;
        romBanks = getRomBanks(rom[0x0148]);
        int ramBanks = getRamBanks(rom[0x0149]);
        if (ramBanks == 0 && cartridgeType.isRam()) {
            LOG.warn("RAM bank is defined to 0. Overriding to 1.");
            ramBanks = 1;
        }
        this.ramBanks = ramBanks;
        LOG.debug("ROM banks: {}, RAM banks: {}", romBanks, ramBanks);
        this.romFile = romFile;
    }

    public int getRomBanks() {
        return romBanks;
    }

    public int getRamBanks() {
        return ramBanks;
    }

    public CartridgeType getType() {
        return cartridgeType;
    }

    public String getTitle() {
        return title;
    }

    public File getFile() {
        return romFile;
    }

    public int[] getRom() {
        return rom;
    }

    public GameboyColorFlag getGameboyColorFlag() {
        return gameboyColorFlag;
    }

    public boolean isSuperGameboyFlag() {
        return superGameboyFlag;
    }

    private static String getTitle(int[] rom) {
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

    private static byte[] loadFile(File file) throws IOException {
        String ext = FilenameUtils.getExtension(file.getName());
        try (InputStream is = Files.newInputStream(file.toPath())) {
            if ("zip".equalsIgnoreCase(ext)) {
                try (ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        String entryExt = FilenameUtils.getExtension(name);
                        if (Stream.of("gb", "gbc", "rom").anyMatch(e -> e.equalsIgnoreCase(entryExt))) {
                            return IOUtils.toByteArray(zis, (int) entry.getSize());
                        }
                        zis.closeEntry();
                    }
                }
                throw new IllegalArgumentException("Can't find ROM file inside the zip.");
            } else {
                return IOUtils.toByteArray(is, (int) file.length());
            }
        }
    }

    private static int getRomBanks(int id) {
        return switch (id) {
            case 0 -> 2;
            case 1 -> 4;
            case 2 -> 8;
            case 3 -> 16;
            case 4 -> 32;
            case 5 -> 64;
            case 6 -> 128;
            case 7 -> 256;
            case 0x52 -> 72;
            case 0x53 -> 80;
            case 0x54 -> 96;
            default -> throw new IllegalArgumentException("Unsupported ROM size: " + Integer.toHexString(id));
        };
    }

    private static int getRamBanks(int id) {
        return switch (id) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 1;
            case 3 -> 4;
            case 4 -> 16;
            default -> throw new IllegalArgumentException("Unsupported RAM size: " + Integer.toHexString(id));
        };
    }

    public enum GameboyColorFlag {
        UNIVERSAL, CGB, NON_CGB;

        private static GameboyColorFlag getFlag(int value) {
            if (value == 0x80) {
                return UNIVERSAL;
            } else if (value == 0xc0) {
                return CGB;
            } else {
                return NON_CGB;
            }
        }
    }
}
