package eu.rekawek.coffeegb.core.memory.cart;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Bounded, side-effect-free cartridge-header inspection for untrusted candidate inputs. */
public final class RomHeaderInspector {

    public static final int HEADER_LENGTH = 0x150;

    private static final int TITLE_START = 0x134;

    private static final int TITLE_END = 0x143;

    private static final byte[] NINTENDO_LOGO = {
            (byte) 0xce, (byte) 0xed, 0x66, 0x66, (byte) 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, (byte) 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, (byte) 0x88, (byte) 0x89, 0x00, 0x0e,
            (byte) 0xdc, (byte) 0xcc, 0x6e, (byte) 0xe6, (byte) 0xdd, (byte) 0xdd,
            (byte) 0xd9, (byte) 0x99,
            (byte) 0xbb, (byte) 0xbb, 0x67, 0x63, 0x6e, 0x0e, (byte) 0xec,
            (byte) 0xcc, (byte) 0xdd, (byte) 0xdc, (byte) 0x99, (byte) 0x9f,
            (byte) 0xbb, (byte) 0xb9, 0x33, 0x3e
    };

    private RomHeaderInspector() {
    }

    public static Header inspect(RomImage image) {
        Objects.requireNonNull(image, "image");
        return inspectHeader(image.copyHeaderForInspector());
    }

    public static Header inspect(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "ROM header requires at least " + HEADER_LENGTH + " bytes");
        }
        return inspectHeader(Arrays.copyOf(bytes, HEADER_LENGTH));
    }

    /**
     * Reads exactly the fixed-size header and never consumes or allocates the rest of the input.
     */
    public static Header inspect(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] header = new byte[HEADER_LENGTH];
        int offset = 0;
        while (offset < header.length) {
            int read = input.read(header, offset, header.length - offset);
            if (read < 0) {
                throw new EOFException(
                        "ROM header is truncated at " + offset + " bytes");
            }
            if (read == 0) {
                int value = input.read();
                if (value < 0) {
                    throw new EOFException(
                            "ROM header is truncated at " + offset + " bytes");
                }
                header[offset++] = (byte) value;
            } else {
                offset += read;
            }
        }
        return inspectHeader(header);
    }

    private static Header inspectHeader(byte[] header) {
        int titleEnd = TITLE_END;
        int cgbFlag = header[0x143] & 0xff;
        if (cgbFlag == 0x80 || cgbFlag == 0xc0) {
            titleEnd = 0x13e;
        }
        int terminator = TITLE_START;
        while (terminator <= titleEnd && header[terminator] != 0) {
            terminator++;
        }
        String title =
                new String(
                                header,
                                TITLE_START,
                                terminator - TITLE_START,
                                StandardCharsets.US_ASCII)
                        .trim();

        int checksum = 0;
        for (int address = 0x134; address <= 0x14c; address++) {
            checksum = (checksum - (header[address] & 0xff) - 1) & 0xff;
        }
        boolean logoValid =
                Arrays.equals(
                        NINTENDO_LOGO,
                        Arrays.copyOfRange(header, 0x104, 0x104 + NINTENDO_LOGO.length));
        return new Header(
                title,
                cgbFlag,
                header[0x146] & 0xff,
                header[0x147] & 0xff,
                header[0x148] & 0xff,
                header[0x149] & 0xff,
                logoValid,
                checksum == (header[0x14d] & 0xff));
    }

    public record Header(
            String title,
            int cgbFlag,
            int sgbFlag,
            int cartridgeType,
            int romSizeCode,
            int ramSizeCode,
            boolean nintendoLogoValid,
            boolean headerChecksumValid) {

        /**
         * Conservative signal for trusting display metadata. It must not be used to reject a
         * bounded ROM: homebrew and unlicensed cartridges may intentionally violate both checks.
         */
        public boolean hasCartridgeShape() {
            return nintendoLogoValid || headerChecksumValid;
        }
    }
}
