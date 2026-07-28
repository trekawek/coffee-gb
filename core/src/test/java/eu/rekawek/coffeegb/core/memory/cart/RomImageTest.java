package eu.rekawek.coffeegb.core.memory.cart;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RomImageTest {

    @Test
    public void retainsExactBytesEvenWhenCoreCorrectsWorkingHeaderChecksum() throws Exception {
        byte[] bytes = headerChecksumValidRom();
        bytes[0x14d] ^= 0x5a;
        byte exactChecksum = bytes[0x14d];

        Rom rom = new Rom(RomImage.memory(bytes, "bad-checksum.gb"));

        assertEquals(exactChecksum, rom.getImage().bytes()[0x14d]);
        assertFalse((rom.getRom()[0x14d] & 0xff) == (exactChecksum & 0xff));
        byte[] returned = rom.getImage().bytes();
        returned[0] = 0x77;
        assertEquals(0, rom.getImage().bytes()[0]);
    }

    @Test
    public void boundedReaderRejectsLyingAndOversizedStreams() {
        assertThrows(
                RomImage.RomSizeLimitException.class,
                () ->
                        RomImage.readBounded(
                                InputStream.nullInputStream(),
                                (long) RomImage.MAX_ROM_BYTES + 1));

        InputStream endless =
                new InputStream() {
                    private long remaining = (long) RomImage.MAX_ROM_BYTES + 1;

                    @Override
                    public int read(byte[] buffer, int offset, int length) {
                        if (remaining == 0) {
                            return -1;
                        }
                        int count = (int) Math.min(Math.min(length, 8192), remaining);
                        Arrays.fill(buffer, offset, offset + count, (byte) 0);
                        remaining -= count;
                        return count;
                    }

                    @Override
                    public int read() {
                        throw new AssertionError("bulk reads expected");
                    }
                };

        RomImage.RomSizeLimitException failure =
                assertThrows(
                        RomImage.RomSizeLimitException.class,
                        () -> RomImage.readBounded(endless, -1));
        assertEquals(RomImage.MAX_ROM_BYTES, failure.limitBytes());
        assertTrue(failure.observedBytes() > failure.limitBytes());
    }

    @Test
    public void headerInspectionIsFixedSizeAndReportsShapeWithoutParsingPayload()
            throws Exception {
        byte[] bytes = headerChecksumValidRom();
        CountingInput input = new CountingInput(bytes);

        RomHeaderInspector.Header header = RomHeaderInspector.inspect(input);

        assertEquals(RomHeaderInspector.HEADER_LENGTH, input.readCount);
        assertEquals("TEST", header.title());
        assertTrue(header.headerChecksumValid());
        assertTrue(header.hasCartridgeShape());
        assertFalse(header.nintendoLogoValid());
    }

    @Test
    public void readersMakeProgressWhenABrokenStreamReturnsZero() throws Exception {
        byte[] bytes = headerChecksumValidRom();

        assertArrayEquals(bytes, RomImage.readBounded(new ZeroFirstInput(bytes), bytes.length));
        assertEquals("TEST", RomHeaderInspector.inspect(new ZeroFirstInput(bytes)).title());
    }

    @Test
    public void cgbTitleExcludesManufacturerCode() throws Exception {
        byte[] bytes = headerChecksumValidRom();
        Arrays.fill(bytes, 0x134, 0x144, (byte) 0);
        System.arraycopy("COLOR TITLE".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0x134, 11);
        System.arraycopy("ABCD".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0x13f, 4);
        bytes[0x143] = (byte) 0x80;
        updateChecksum(bytes);

        RomHeaderInspector.Header header =
                RomHeaderInspector.inspect(RomImage.memory(bytes, "color.gbc"));

        assertEquals("COLOR TITLE", header.title());
        assertTrue(header.headerChecksumValid());
    }

    @Test
    public void rejectsTruncatedImagesAndHeadersDeterministically() {
        assertThrows(
                IOException.class,
                () -> RomImage.memory(new byte[RomHeaderInspector.HEADER_LENGTH - 1], "tiny.gb"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RomHeaderInspector.inspect(new byte[RomHeaderInspector.HEADER_LENGTH - 1]));
    }

    private static byte[] headerChecksumValidRom() {
        byte[] bytes = new byte[0x8000];
        byte[] title = {'T', 'E', 'S', 'T'};
        System.arraycopy(title, 0, bytes, 0x134, title.length);
        updateChecksum(bytes);
        return bytes;
    }

    private static void updateChecksum(byte[] bytes) {
        int checksum = 0;
        for (int address = 0x134; address <= 0x14c; address++) {
            checksum = (checksum - (bytes[address] & 0xff) - 1) & 0xff;
        }
        bytes[0x14d] = (byte) checksum;
    }

    private static class CountingInput extends InputStream {

        private final byte[] bytes;

        private int offset;

        private int readCount;

        private CountingInput(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) {
            if (offset == bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - offset);
            System.arraycopy(bytes, offset, target, targetOffset, count);
            offset += count;
            readCount += count;
            return count;
        }

        @Override
        public int read() {
            if (offset == bytes.length) {
                return -1;
            }
            readCount++;
            return bytes[offset++] & 0xff;
        }
    }

    private static final class ZeroFirstInput extends CountingInput {

        private boolean returnZero = true;

        private ZeroFirstInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) {
            if (returnZero) {
                returnZero = false;
                return 0;
            }
            return super.read(target, targetOffset, length);
        }
    }
}
