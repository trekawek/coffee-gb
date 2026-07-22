package eu.rekawek.coffeegb.core.memory.cart;

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

    private final int ramSize;

    private final int[] rom;

    /** Exact bytes loaded from disk, before compatibility fixes are applied to the emulated copy. */
    private final byte[] sourceData;

    private final GameboyColorFlag gameboyColorFlag;

    private final boolean superGameboyFlag;

    private final CartridgeProperties cartridgeProperties;

    public Rom(File romFile) throws IOException {
        this(loadFile(romFile), romFile);
    }

    public Rom(byte[] romByteArray) throws IOException {
        this(romByteArray, null);
    }

    public Rom(byte[] romByteArray, File romFile) throws IOException {
        sourceData = romByteArray.clone();
        rom = new int[romByteArray.length];
        for (int i = 0; i < romByteArray.length; i++) {
            rom[i] = romByteArray[i] & 0xFF;
        }

        cartridgeProperties = CartridgeProperties.detect(rom);
        int[] header = cartridgeProperties.getHeader(rom);
        if (!cartridgeProperties.getProfiles().isEmpty()) {
            LOG.info("Cartridge compatibility profiles: {}", cartridgeProperties.getProfiles());
        }

        // Correct an invalid header checksum (0x14D) so the authentic boot ROM does not lock
        // up. The real DMG/CGB boot ROM verifies the checksum over 0x134-0x14C and hangs on
        // the logo screen if it is wrong; some homebrew/PD dumps ship a bad one (e.g.
        // Dimensionless Sample, #76 - it renders past a SKIP boot but hangs the boot ROM).
        // BGB, SameBoy and real flashcarts silently fix it; only touches already-invalid ROMs.
        if (!cartridgeProperties.has(CartridgeProperties.Feature.SCRAMBLED_SACHEN_HEADER)
                && rom.length > 0x014D) {
            int headerChecksum = 0;
            for (int a = 0x0134; a <= 0x014C; a++) {
                headerChecksum = (headerChecksum - rom[a] - 1) & 0xFF;
            }
            if (rom[0x014D] != headerChecksum) {
                LOG.warn("Correcting invalid header checksum {} -> {}",
                        Integer.toHexString(rom[0x014D]), Integer.toHexString(headerChecksum));
                rom[0x014D] = headerChecksum;
            }
        }

        title = getTitle(header);
        CartridgeType type;
        try {
            type = CartridgeType.getById(header[0x0147]);
        } catch (IllegalArgumentException e) {
            // Unknown/custom mapper byte. Some are known unlicensed carts we handle
            // deliberately as MBC5; the rest fall back to MBC5 banking rather than
            // refusing to load (issues #58, #71).
            if (cartridgeProperties.has(CartridgeProperties.Feature.POCKET_VOICE)) {
                // The Pocket Voice V2.0 voice recorder (type 0xBE) is MBC5-compatible for
                // everything the Game Boy can observe: it banks 32x16 KB normally and its
                // full UI (record screen, built-in sample library) is reachable. The voice
                // chip is controlled by write-only commands (0x6000 = command, 0x7000 =
                // strobe); reads of that range return ordinary ROM-window bytes, so nothing
                // ever polls the chip and the cart never stalls. The audio itself lives
                // inside an external analog voice IC (own mic in, own speaker out) and never
                // crosses the cartridge bus, so recording/playback cannot be reproduced from
                // a ROM dump - see issue #71.
                LOG.info("Pocket Voice cartridge detected; handling as MBC5 (external voice chip not emulated)");
            } else {
                LOG.warn("Unsupported cartridge type {}, falling back to MBC5",
                        Integer.toHexString(header[0x0147]));
            }
            type = CartridgeType.getById(0x19);
        }
        cartridgeType = type;
        LOG.debug("Cartridge {}, type: {}", title, cartridgeType);
        GameboyColorFlag colorFlag = GameboyColorFlag.getFlag(header[0x0143]);
        if (cartridgeProperties.has(CartridgeProperties.Feature.FORCE_DMG)) {
            colorFlag = GameboyColorFlag.NON_CGB;
        }
        gameboyColorFlag = colorFlag;
        superGameboyFlag = header[0x0146] == 0x03;
        romBanks = cartridgeProperties.has(CartridgeProperties.Feature.SCRAMBLED_SACHEN_HEADER)
                ? Math.max(2, (rom.length + 0x3fff) / 0x4000)
                : getRomBanks(header[0x0148], rom.length);
        int ramSize = getRamSize(header[0x0149]);
        if (ramSize == 0 && cartridgeType.isRam()) {
            LOG.warn("RAM bank is defined to 0. Overriding to 1.");
            ramSize = 0x2000;
        }
        this.ramSize = ramSize;
        this.ramBanks = (ramSize + 0x1fff) / 0x2000;
        LOG.debug("ROM banks: {}, RAM banks: {}", romBanks, this.ramBanks);
        this.romFile = romFile;
    }

    public int getRomBanks() {
        return romBanks;
    }

    public int getRamBanks() {
        return ramBanks;
    }

    public int getRamSize() {
        return ramSize;
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

    public byte[] getSourceData() {
        return sourceData.clone();
    }

    public GameboyColorFlag getGameboyColorFlag() {
        return gameboyColorFlag;
    }

    public boolean isSuperGameboyFlag() {
        return superGameboyFlag;
    }

    public CartridgeProperties getCartridgeProperties() {
        return cartridgeProperties;
    }

    private static String getTitle(int[] rom) {
        StringBuilder t = new StringBuilder();
        for (int i = 0x0134; i <= 0x0143; i++) {
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

    private static int getRomBanks(int id, int romLength) {
        int declaredBanks = switch (id) {
            case 0 -> 2;
            case 1 -> 4;
            case 2 -> 8;
            case 3 -> 16;
            case 4 -> 32;
            case 5 -> 64;
            case 6 -> 128;
            case 7 -> 256;
            case 8 -> 512;
            case 0x52 -> 72;
            case 0x53 -> 80;
            case 0x54 -> 96;
            // unlicensed carts (Sachen multicarts, some homebrew) store a non-standard
            // size byte; derive the bank count from the actual file size (16 KB per
            // bank) instead of refusing to load (issue #58)
            default -> 2;
        };
        // A few unlicensed carts put executable code over the standard header, while
        // others keep a smaller stock header despite selecting extra physical banks.
        // Never hide banks present in the image (Sonic 3D Blast 5, #186; Touch Boy,
        // #182), but retain the declared capacity for truncated dumps so missing banks
        // continue to read as FF.
        int physicalBanks = Math.max(2, (romLength + 0x3fff) / 0x4000);
        return Math.max(declaredBanks, physicalBanks);
    }

    private static int getRamSize(int id) {
        return switch (id) {
            case 0 -> 0;
            case 1 -> 0x0800;
            case 2 -> 0x2000;
            case 3 -> 0x8000;
            case 4 -> 0x20000;
            case 5 -> 0x10000;
            // Unlicensed cartridges sometimes place executable code across the standard
            // header fields. In that case 0x0149 is an instruction byte rather than a RAM
            // size declaration (Sonic 3D Blast 5 uses JR NZ, 0x20). Treat an unknown value
            // as no declared RAM, just as physical hardware does; mapper-specific detection
            // can still provide RAM when the cartridge is known to have it.
            default -> 0;
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
