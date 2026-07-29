package eu.rekawek.coffeegb.core.memory.cart;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Rom {

    private static final Logger LOG = LoggerFactory.getLogger(Rom.class);

    static final int MAX_ARCHIVE_ENTRIES = 4096;

    static final long MAX_ARCHIVE_CONTAINER_BYTES = 128L * 1024 * 1024;

    static final long MAX_ARCHIVE_UNCOMPRESSED_BYTES = 256L * 1024 * 1024;

    /**
     * Defense-in-depth limit for Commons Compress's supported decoder memory (including
     * LZMA/LZMA2) and archive-statistics checks. It is not a complete metadata-allocation bound:
     * Commons Compress 1.28 may allocate count-sized 7z header structures before validating its
     * statistics. The unified snapshot loader isolates 7z parsing in a bounded helper process;
     * this direct-file compatibility path still parses it in-process.
     */
    static final int MAX_SEVEN_Z_MEMORY_KIB = 64 * 1024;

    private static final int ZIP_END_MIN_SIZE = 22;

    private static final int ZIP64_END_MIN_SIZE = 56;

    private static final int ZIP64_LOCATOR_SIZE = 20;

    private static final int ZIP_CENTRAL_HEADER_SIZE = 46;

    private static final int ZIP_END_SIGNATURE = 0x06054b50;

    private static final int ZIP64_END_SIGNATURE = 0x06064b50;

    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;

    private static final int ZIP_CENTRAL_HEADER_SIGNATURE = 0x02014b50;

    private static final int ZIP_CENTRAL_DIGITAL_SIGNATURE = 0x05054b50;

    private final String title;

    private final File romFile;

    private final RomImage image;

    private final CartridgeType cartridgeType;

    private final int romBanks;

    private final int ramBanks;

    private final int ramSize;

    private final int[] rom;

    private final GameboyColorFlag gameboyColorFlag;

    private final boolean superGameboyFlag;

    private final CartridgeProperties cartridgeProperties;

    /**
     * Legacy file-opening compatibility path.
     *
     * <p>Archive loading reopens the path, and direct 7z loading parses metadata in-process.
     * Security-sensitive callers must use the unified snapshotting open service rather than
     * treating this constructor as a TOCTOU-safe trust boundary.
     */
    public Rom(File romFile) throws IOException {
        this(loadFile(romFile), romFile);
    }

    public Rom(byte[] romByteArray) throws IOException {
        this(RomImage.memory(romByteArray, "memory-rom"), null);
    }

    /**
     * Legacy direct-file constructor.
     *
     * <p>Archive callers must use {@link #Rom(RomImage)} so the selected entry remains part of
     * the identity rather than being conflated with its container.
     */
    public Rom(byte[] romByteArray, File romFile) throws IOException {
        this(
                romFile == null
                        ? RomImage.memory(romByteArray, "memory-rom")
                        : new RomImage(RomOrigin.directFile(romFile.toPath()), romByteArray),
                romFile);
    }

    public Rom(RomImage image) throws IOException {
        this(image, null);
    }

    private Rom(RomImage image, File originalFile) throws IOException {
        this.image = Objects.requireNonNull(image, "image");
        byte[] romByteArray = image.copyBytesForParser();
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
        this.romFile =
                originalFile != null
                        ? originalFile
                        : image.origin().containerPath().map(java.nio.file.Path::toFile).orElse(null);
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

    public RomOrigin getOrigin() {
        return image.origin();
    }

    public RomImage getImage() {
        return image;
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

    private static RomImage loadFile(File file) throws IOException {
        String ext = FilenameUtils.getExtension(file.getName());
        if ("7z".equalsIgnoreCase(ext)) {
            validateArchiveContainer(file);
            try (SevenZFile sevenZFile =
                    SevenZFile.builder()
                            .setFile(file)
                            .setMaxMemoryLimitKiB(MAX_SEVEN_Z_MEMORY_KIB)
                            .get()) {
                SevenZArchiveEntry selected = null;
                String selectedName = null;
                int candidateCount = 0;
                int entryCount = 0;
                long totalSize = 0;
                for (SevenZArchiveEntry entry : sevenZFile.getEntries()) {
                    entryCount = checkedEntryCount(entryCount);
                    totalSize = checkedArchiveSize(totalSize, entry.getSize(), entry.isDirectory());
                    if (!entry.isDirectory() && isRomFile(entry.getName())) {
                        candidateCount++;
                        if (selected == null) {
                            selected = entry;
                            selectedName = entry.getName();
                        }
                    }
                }
                if (selected != null) {
                    RomOrigin origin =
                            RomOrigin.archiveEntry(
                                    file.toPath(),
                                    selectedName,
                                    candidateCount == 1);
                    byte[] selectedBytes;
                    try (InputStream input = sevenZFile.getInputStream(selected)) {
                        selectedBytes = RomImage.readBounded(input, selected.getSize());
                    }
                    return new RomImage(origin, selectedBytes);
                }
            }
            throw new IllegalArgumentException("Can't find ROM file inside the 7z.");
        }
        if ("zip".equalsIgnoreCase(ext)) {
            validateArchiveContainer(file);
            preflightZip(file.toPath());
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry selected = null;
                int candidateCount = 0;
                int entryCount = 0;
                long totalSize = 0;
                for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                    ZipEntry entry = entries.nextElement();
                    entryCount = checkedEntryCount(entryCount);
                    totalSize = checkedArchiveSize(totalSize, entry.getSize(), entry.isDirectory());
                    if (!entry.isDirectory() && isRomFile(entry.getName())) {
                        candidateCount++;
                        if (selected == null) {
                            selected = entry;
                        }
                    }
                }
                if (selected != null) {
                    RomOrigin origin =
                            RomOrigin.archiveEntry(
                                    file.toPath(),
                                    selected.getName(),
                                    candidateCount == 1);
                    byte[] selectedBytes;
                    try (InputStream input = zip.getInputStream(selected)) {
                        selectedBytes = RomImage.readBounded(input, selected.getSize());
                    }
                    return new RomImage(origin, selectedBytes);
                }
            }
            throw new IllegalArgumentException("Can't find ROM file inside the zip.");
        } else {
            try (InputStream is = Files.newInputStream(file.toPath())) {
                return new RomImage(
                        RomOrigin.directFile(file.toPath()),
                        RomImage.readBounded(is, file.length()));
            }
        }
    }

    private static void validateArchiveContainer(File file) throws IOException {
        long size = Files.size(file.toPath());
        if (size > MAX_ARCHIVE_CONTAINER_BYTES) {
            throw new IOException(
                    "Archive exceeds the "
                            + MAX_ARCHIVE_CONTAINER_BYTES
                            + "-byte compressed-size safety limit");
        }
    }

    /**
     * Inventories bounded ZIP metadata before {@link ZipFile} can allocate its internal central
     * directory structures. Both classic and ZIP64 end records are supported. The later ZipFile
     * pass remains authoritative for decompression and CRC validation.
     */
    static void preflightZip(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ZipDirectoryMetadata metadata = readZipDirectoryMetadata(channel);
            if (metadata.entryCount() > MAX_ARCHIVE_ENTRIES) {
                throw tooManyArchiveEntries();
            }
            inspectZipCentralDirectory(channel, metadata);
        }
    }

    private static ZipDirectoryMetadata readZipDirectoryMetadata(FileChannel channel)
            throws IOException {
        long fileSize = channel.size();
        int tailSize =
                (int)
                        Math.min(
                                fileSize,
                                (long) ZIP_END_MIN_SIZE + Character.MAX_VALUE);
        if (tailSize < ZIP_END_MIN_SIZE) {
            throw invalidZip("end-of-central-directory record is missing");
        }
        long tailOffset = fileSize - tailSize;
        ByteBuffer tail = readFully(channel, tailOffset, tailSize);
        int endIndex = -1;
        for (int candidate = tailSize - ZIP_END_MIN_SIZE; candidate >= 0; candidate--) {
            if (tail.getInt(candidate) == ZIP_END_SIGNATURE
                    && candidate
                                    + ZIP_END_MIN_SIZE
                                    + unsignedShort(tail, candidate + 20)
                            == tailSize) {
                endIndex = candidate;
                break;
            }
        }
        if (endIndex < 0) {
            throw invalidZip("end-of-central-directory record is missing");
        }

        int diskNumber = unsignedShort(tail, endIndex + 4);
        int centralDirectoryDisk = unsignedShort(tail, endIndex + 6);
        int entriesOnDisk = unsignedShort(tail, endIndex + 8);
        int entryCount = unsignedShort(tail, endIndex + 10);
        long centralSize = unsignedInt(tail, endIndex + 12);
        long centralOffset = unsignedInt(tail, endIndex + 16);
        long endOffset = tailOffset + endIndex;
        boolean zip64 =
                entriesOnDisk == 0xffff
                        || entryCount == 0xffff
                        || centralSize == 0xffff_ffffL
                        || centralOffset == 0xffff_ffffL;

        if (!zip64) {
            if (diskNumber != 0
                    || centralDirectoryDisk != 0
                    || entriesOnDisk != entryCount) {
                throw invalidZip("multi-disk archives are not supported");
            }
            long actualCentralOffset = endOffset - centralSize;
            long archivePrefix = actualCentralOffset - centralOffset;
            if (actualCentralOffset < 0 || archivePrefix < 0) {
                throw invalidZip("central-directory offset is outside the archive");
            }
            return checkedZipDirectoryMetadata(
                    entryCount, actualCentralOffset, centralSize, endOffset);
        }

        return readZip64DirectoryMetadata(channel, endOffset);
    }

    private static ZipDirectoryMetadata readZip64DirectoryMetadata(
            FileChannel channel, long classicEndOffset) throws IOException {
        long locatorOffset = classicEndOffset - ZIP64_LOCATOR_SIZE;
        ByteBuffer locator = readFully(channel, locatorOffset, ZIP64_LOCATOR_SIZE);
        if (locator.getInt(0) != ZIP64_LOCATOR_SIGNATURE) {
            throw invalidZip("ZIP64 locator is missing");
        }
        if (unsignedInt(locator, 4) != 0 || unsignedInt(locator, 16) != 1) {
            throw invalidZip("multi-disk ZIP64 archives are not supported");
        }
        long recordedEndOffset = unsignedLong(locator, 8, "ZIP64 end-record offset");

        Zip64EndRecord endRecord =
                tryReadZip64EndRecord(channel, recordedEndOffset, locatorOffset);
        if (endRecord == null) {
            // A prepended executable changes physical offsets while ZIP offsets remain relative
            // to the archive start. The overwhelmingly common ZIP64 record has no extensible
            // sector, so locate that form immediately before its locator.
            endRecord =
                    tryReadZip64EndRecord(
                            channel, locatorOffset - ZIP64_END_MIN_SIZE, locatorOffset);
        }
        if (endRecord == null) {
            throw invalidZip("ZIP64 end-of-central-directory record is invalid");
        }
        long archivePrefix = endRecord.actualOffset() - recordedEndOffset;
        long actualCentralOffset =
                checkedAdd(
                        archivePrefix,
                        endRecord.centralOffset(),
                        "ZIP64 central-directory offset");
        return checkedZipDirectoryMetadata(
                endRecord.entryCount(),
                actualCentralOffset,
                endRecord.centralSize(),
                endRecord.actualOffset());
    }

    private static Zip64EndRecord tryReadZip64EndRecord(
            FileChannel channel, long offset, long locatorOffset) throws IOException {
        if (offset < 0 || offset > channel.size() - ZIP64_END_MIN_SIZE) {
            return null;
        }
        ByteBuffer fixed = readFully(channel, offset, ZIP64_END_MIN_SIZE);
        if (fixed.getInt(0) != ZIP64_END_SIGNATURE) {
            return null;
        }
        long recordBodySize = unsignedLong(fixed, 4, "ZIP64 end-record size");
        if (recordBodySize < 44) {
            return null;
        }
        long recordEnd = checkedAdd(offset, 12, "ZIP64 end-record boundary");
        recordEnd = checkedAdd(recordEnd, recordBodySize, "ZIP64 end-record boundary");
        if (recordEnd != locatorOffset
                || unsignedInt(fixed, 16) != 0
                || unsignedInt(fixed, 20) != 0) {
            return null;
        }
        long entriesOnDisk = unsignedLong(fixed, 24, "ZIP64 entries-on-disk count");
        long entryCount = unsignedLong(fixed, 32, "ZIP64 entry count");
        if (entriesOnDisk != entryCount) {
            throw invalidZip("multi-disk ZIP64 archives are not supported");
        }
        return new Zip64EndRecord(
                offset,
                entryCount,
                unsignedLong(fixed, 40, "ZIP64 central-directory size"),
                unsignedLong(fixed, 48, "ZIP64 central-directory offset"));
    }

    private static ZipDirectoryMetadata checkedZipDirectoryMetadata(
            long entryCount, long centralOffset, long centralSize, long boundary)
            throws IOException {
        if (entryCount < 0 || centralOffset < 0 || centralSize < 0) {
            throw invalidZip("central-directory metadata is negative");
        }
        long centralEnd =
                checkedAdd(centralOffset, centralSize, "central-directory boundary");
        if (centralEnd > boundary) {
            throw invalidZip("central directory is outside the archive");
        }
        return new ZipDirectoryMetadata(entryCount, centralOffset, centralSize);
    }

    private static void inspectZipCentralDirectory(
            FileChannel channel, ZipDirectoryMetadata metadata) throws IOException {
        long position = metadata.centralOffset();
        long centralEnd =
                checkedAdd(
                        metadata.centralOffset(),
                        metadata.centralSize(),
                        "central-directory boundary");
        long totalSize = 0;
        for (long index = 0; index < metadata.entryCount(); index++) {
            ByteBuffer header = readFully(channel, position, ZIP_CENTRAL_HEADER_SIZE);
            if (header.getInt(0) != ZIP_CENTRAL_HEADER_SIGNATURE) {
                throw invalidZip("central-directory entry signature is invalid");
            }
            long uncompressedSize = unsignedInt(header, 24);
            int fileNameLength = unsignedShort(header, 28);
            int extraLength = unsignedShort(header, 30);
            int commentLength = unsignedShort(header, 32);
            long recordSize =
                    ZIP_CENTRAL_HEADER_SIZE
                            + (long) fileNameLength
                            + extraLength
                            + commentLength;
            long nextPosition =
                    checkedAdd(position, recordSize, "central-directory entry boundary");
            if (nextPosition > centralEnd) {
                throw invalidZip("central-directory entry extends beyond its declared size");
            }

            boolean directory = false;
            if (fileNameLength > 0) {
                ByteBuffer lastNameByte =
                        readFully(
                                channel,
                                position + ZIP_CENTRAL_HEADER_SIZE + fileNameLength - 1L,
                                1);
                directory = lastNameByte.get(0) == '/';
            }
            if (uncompressedSize == 0xffff_ffffL) {
                ByteBuffer extra =
                        readFully(
                                channel,
                                position + ZIP_CENTRAL_HEADER_SIZE + fileNameLength,
                                extraLength);
                uncompressedSize = readZip64UncompressedSize(extra);
            }
            totalSize = checkedArchiveSize(totalSize, uncompressedSize, directory);
            position = nextPosition;
        }

        if (position < centralEnd) {
            ByteBuffer signature = readFully(channel, position, 6);
            if (signature.getInt(0) != ZIP_CENTRAL_DIGITAL_SIGNATURE) {
                throw invalidZip("central directory contains unexpected trailing metadata");
            }
            long nextPosition =
                    checkedAdd(
                            position,
                            6L + unsignedShort(signature, 4),
                            "central-directory digital-signature boundary");
            if (nextPosition > centralEnd) {
                throw invalidZip("central-directory digital signature is truncated");
            }
            position = nextPosition;
        }
        if (position != centralEnd) {
            throw invalidZip("central directory contains repeated trailing metadata");
        }
    }

    private static long readZip64UncompressedSize(ByteBuffer extra) throws IOException {
        int position = 0;
        while (position <= extra.limit() - 4) {
            int headerId = unsignedShort(extra, position);
            int dataSize = unsignedShort(extra, position + 2);
            position += 4;
            if (dataSize > extra.limit() - position) {
                throw invalidZip("ZIP extra field is truncated");
            }
            if (headerId == 0x0001) {
                if (dataSize < Long.BYTES) {
                    throw invalidZip("ZIP64 uncompressed size is missing");
                }
                return unsignedLong(extra, position, "ZIP64 uncompressed size");
            }
            position += dataSize;
        }
        throw invalidZip("ZIP64 uncompressed size is missing");
    }

    private static ByteBuffer readFully(FileChannel channel, long offset, int size)
            throws IOException {
        if (offset < 0 || size < 0 || offset > channel.size() - size) {
            throw invalidZip("metadata points outside the archive");
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset + buffer.position());
            if (read <= 0) {
                throw invalidZip("archive metadata is truncated");
            }
        }
        return buffer.flip();
    }

    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static long unsignedInt(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static long unsignedLong(ByteBuffer buffer, int offset, String field)
            throws IOException {
        long value = buffer.getLong(offset);
        if (value < 0) {
            throw invalidZip(field + " exceeds the supported range");
        }
        return value;
    }

    private static long checkedAdd(long left, long right, String field) throws IOException {
        if (right < 0 || left > Long.MAX_VALUE - right) {
            throw invalidZip(field + " overflows");
        }
        return left + right;
    }

    private static IOException invalidZip(String detail) {
        return new IOException("Invalid ZIP archive: " + detail);
    }

    private static IOException tooManyArchiveEntries() {
        return new IOException(
                "Archive exceeds the " + MAX_ARCHIVE_ENTRIES + "-entry safety limit");
    }

    private static int checkedEntryCount(int priorCount) throws IOException {
        if (priorCount >= MAX_ARCHIVE_ENTRIES) {
            throw tooManyArchiveEntries();
        }
        return priorCount + 1;
    }

    static long checkedArchiveSize(long priorSize, long entrySize, boolean directory)
            throws IOException {
        if (directory) {
            return priorSize;
        }
        if (entrySize < 0) {
            throw new IOException("Archive entry has an unknown uncompressed size");
        }
        if (entrySize > MAX_ARCHIVE_UNCOMPRESSED_BYTES - priorSize) {
            throw new IOException(
                    "Archive exceeds the "
                            + MAX_ARCHIVE_UNCOMPRESSED_BYTES
                            + "-byte uncompressed-size safety limit");
        }
        return priorSize + entrySize;
    }

    private static boolean isRomFile(String name) {
        String ext = FilenameUtils.getExtension(name);
        return Stream.of("gb", "gbc", "rom").anyMatch(e -> e.equalsIgnoreCase(ext));
    }

    private record ZipDirectoryMetadata(
            long entryCount, long centralOffset, long centralSize) {
    }

    private record Zip64EndRecord(
            long actualOffset, long entryCount, long centralSize, long centralOffset) {
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
