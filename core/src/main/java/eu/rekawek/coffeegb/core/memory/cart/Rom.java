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

    private final GameboyColorFlag gameboyColorFlag;

    private final boolean legacySpeedSwitchRequired;

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

        // Correct an invalid header checksum (0x14D) so the authentic boot ROM does not lock
        // up. The real DMG/CGB boot ROM verifies the checksum over 0x134-0x14C and hangs on
        // the logo screen if it is wrong; some homebrew/PD dumps ship a bad one (e.g.
        // Dimensionless Sample, #76 - it renders past a SKIP boot but hangs the boot ROM).
        // BGB, SameBoy and real flashcarts silently fix it; only touches already-invalid ROMs.
        if (rom.length > 0x014D) {
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

        title = getTitle(rom);
        CartridgeType type;
        try {
            type = CartridgeType.getById(rom[0x0147]);
        } catch (IllegalArgumentException e) {
            // Unknown/custom mapper byte. Some are known unlicensed carts we handle
            // deliberately as MBC5; the rest fall back to MBC5 banking rather than
            // refusing to load (issues #58, #71).
            if (isPocketVoice(rom, title)) {
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
                        Integer.toHexString(rom[0x0147]));
            }
            type = CartridgeType.getById(0x19);
        }
        cartridgeType = type;
        LOG.debug("Cartridge {}, type: {}", title, cartridgeType);
        GameboyColorFlag colorFlag = GameboyColorFlag.getFlag(rom[0x0143]);
        boolean legacySpeedSwitchRequired = false;
        if (isDmgOnlyCrazyCastleTrainer(rom, title)) {
            // This trainer advertises CGB compatibility but only initializes the DMG
            // BGP/OBP registers. Native CGB startup therefore renders its menu entirely
            // white and also leaves HRAM in a state the trainer does not expect.
            LOG.info("DMG-only Crazy Castle 3 trainer detected; ignoring its CGB flag");
            colorFlag = GameboyColorFlag.NON_CGB;
        } else if (isNamcoGallery2Trainer(rom, title)) {
            // This old emulator-oriented trainer keeps the original DMG header, but its
            // launch stub writes KEY1 and executes STOP before handing off to the game.
            // Accept that speed switch as a narrowly scoped extension while retaining
            // DMG rendering; treating the whole image as native CGB corrupts its colours.
            LOG.info("Namco Gallery Vol.2 trainer detected; enabling its CGB speed-switch extension");
            legacySpeedSwitchRequired = true;
        }
        gameboyColorFlag = colorFlag;
        this.legacySpeedSwitchRequired = legacySpeedSwitchRequired;
        superGameboyFlag = rom[0x0146] == 0x03;
        romBanks = getRomBanks(rom[0x0148], rom.length);
        int ramSize = getRamSize(rom[0x0149]);
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

    public GameboyColorFlag getGameboyColorFlag() {
        return gameboyColorFlag;
    }

    public boolean isLegacySpeedSwitchRequired() {
        return legacySpeedSwitchRequired;
    }

    public boolean isSuperGameboyFlag() {
        return superGameboyFlag;
    }

    /**
     * The Lightforce +1 trainer for The Adventures of the Smurfs uses tile 0x0A as
     * its blank background without uploading that tile. The CGB boot ROM leaves logo
     * pixels there, while the skip-boot environment the trainer was authored for starts
     * it at zero. Match the injected trainer code rather than the filename so renamed
     * copies receive the same compatibility handling.
     */
    public boolean requiresBlankCgbBootTile() {
        int[] trainerSignature = {
                0x7d, 0xea, 0xa1, 0xc0, 0xe6, 0x03, 0x6f, 0x01,
                0xe0, 0x01, 0xcb, 0x25, 0xcb, 0x25, 0x09, 0xe9
        };
        int signatureOffset = 0xddea4;
        if (rom.length != 0x100000 || rom[0x0143] != 0xc0 || rom[0x0147] != 0x1b) {
            return false;
        }
        for (int i = 0; i < trainerSignature.length; i++) {
            if (rom[signatureOffset + i] != trainerSignature[i]) {
                return false;
            }
        }
        return true;
    }

    // The Pocket Voice V2.0 voice recorder uses cartridge-type byte 0xBE and stamps its name
    // in the header title ("Pocket Voice2.0"); match both so an unrelated cart that happens to
    // reuse 0xBE is not mislabelled.
    private static boolean isPocketVoice(int[] rom, String title) {
        return rom[0x0147] == 0xBE && title.startsWith("Pocket Voice");
    }

    private static boolean isDmgOnlyCrazyCastleTrainer(int[] rom, String title) {
        int[] entryStub = {0xf5, 0x3e, 0x03, 0xea, 0x00, 0x21, 0xf1, 0xc3, 0x00, 0x68};
        if (rom.length != 0x100000 || !"BUGS CC3 CRACK".equals(title)
                || rom[0x0143] != 0x80 || rom[0x0147] != 0x19) {
            return false;
        }
        for (int i = 0; i < entryStub.length; i++) {
            if (rom[0x00e0 + i] != entryStub[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNamcoGallery2Trainer(int[] rom, String title) {
        int[] speedSwitchStub = {0x3e, 0x01, 0xe0, 0x4d, 0x10, 0x00, 0x00, 0x00,
                0xcd, 0x0d, 0x71, 0xfa, 0xf0, 0xdf, 0xc3, 0x50, 0x01};
        if (rom.length != 0x80000 || !"GALLERY2 +4".equals(title)
                || rom[0x0143] != 0x00 || rom[0x0147] != 0x01) {
            return false;
        }
        for (int i = 0; i < speedSwitchStub.length; i++) {
            if (rom[0x3f0fc + i] != speedSwitchStub[i]) {
                return false;
            }
        }
        return true;
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
