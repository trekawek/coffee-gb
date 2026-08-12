package eu.rekawek.coffeegb.ui.menu.artwork;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PngArgbDecoderTest {

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'
    };

    @Test
    public void decodesRgbRgbaAndStraightAlpha() throws Exception {
        byte[] rgb = png(2, 1, 8, 2, 0, 0, 0,
                new byte[]{0, 1, 2, 3, 4, 5, 6}, 1, List.of());
        assertArrayEquals(new int[]{0xff010203, 0xff040506},
                PngArgbDecoder.decode(new ByteArrayInputStream(rgb)).copyPixels());

        byte[] rgba = png(2, 1, 8, 6, 0, 0, 0,
                new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 9}, 1, List.of());
        assertArrayEquals(new int[]{0x04010203, 0x09050607},
                PngArgbDecoder.decode(new ByteArrayInputStream(rgba)).copyPixels());
    }

    @Test
    public void decodesFiltersZeroThroughFourAndSplitIdatChunks() throws Exception {
        int width = 2;
        int channels = 3;
        byte[][] rows = {
                {(byte) 10, 20, 30, 40, 50, 60},
                {(byte) 11, 22, 33, 44, 55, 66},
                {(byte) 12, 24, 36, 48, 60, 72},
                {(byte) 13, 26, 39, 52, 65, 78},
                {(byte) 14, 28, 42, 56, 70, 84}
        };
        byte[] scanlines = encodeRows(rows, channels, new int[]{0, 1, 2, 3, 4});
        MenuArgbFrame frame = PngArgbDecoder.decode(new ByteArrayInputStream(
                png(width, rows.length, 8, 2, 0, 0, 0, scanlines, 3, List.of())));
        int[] expected = new int[width * rows.length];
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < width; column++) {
                int offset = column * channels;
                expected[row * width + column] = 0xff000000
                        | ((rows[row][offset] & 0xff) << 16)
                        | ((rows[row][offset + 1] & 0xff) << 8)
                        | (rows[row][offset + 2] & 0xff);
            }
        }
        assertArrayEquals(expected, frame.copyPixels());
    }

    @Test
    public void ignoresValidAncillaryChunksAndChecksEveryCrc() throws Exception {
        byte[] valid = png(1, 1, 8, 2, 0, 0, 0,
                new byte[]{0, 1, 2, 3}, 1,
                List.of(new Chunk("tEXt", "note\0fixture".getBytes(StandardCharsets.ISO_8859_1))));
        assertEquals(0xff010203,
                PngArgbDecoder.decode(new ByteArrayInputStream(valid)).copyPixels()[0]);

        byte[] badCrc = valid.clone();
        badCrc[badCrc.length - 1] ^= 1;
        assertRejects(badCrc);
        byte[] badSignature = valid.clone();
        badSignature[0] = 0;
        assertRejects(badSignature);
    }

    @Test
    public void rejectsOrderDuplicatesAndUnknownCriticalChunks() throws Exception {
        byte[] ihdr = ihdr(1, 1, 8, 2, 0, 0, 0);
        byte[] idat = zlib(new byte[]{0, 1, 2, 3});
        assertRejects(chunks(new Chunk("IDAT", idat), new Chunk("IHDR", ihdr), new Chunk("IEND", new byte[0])));
        assertRejects(chunks(new Chunk("IHDR", ihdr), new Chunk("IHDR", ihdr),
                new Chunk("IDAT", idat), new Chunk("IEND", new byte[0])));
        assertRejects(chunks(new Chunk("IHDR", ihdr), new Chunk("ABCD", new byte[0]),
                new Chunk("IDAT", idat), new Chunk("IEND", new byte[0])));
        assertRejects(chunks(new Chunk("IHDR", ihdr), new Chunk("IDAT", idat),
                new Chunk("tEXt", new byte[]{1}), new Chunk("IDAT", idat),
                new Chunk("IEND", new byte[0])));
        assertRejects(chunks(new Chunk("IHDR", ihdr), new Chunk("IEND", new byte[0])));
        assertRejects(chunks(new Chunk("IHDR", ihdr), new Chunk("IDAT", idat)));
    }

    @Test
    public void rejectsTransparencyChunkAndLowercaseReservedChunkByte() throws Exception {
        byte[] ihdr = ihdr(1, 1, 8, 2, 0, 0, 0);
        byte[] idat = zlib(new byte[]{0, 1, 2, 3});
        assertRejects(chunks(new Chunk("IHDR", ihdr), new Chunk("tRNS", new byte[]{0, 1}),
                new Chunk("IDAT", idat), new Chunk("IEND", new byte[0])));
        assertRejects(chunks(new Chunk("IHDR", ihdr), new Chunk("abxC", new byte[0]),
                new Chunk("IDAT", idat), new Chunk("IEND", new byte[0])));
    }

    @Test
    public void rejectsUnsupportedFormatsFiltersAndDimensions() throws Exception {
        assertRejects(png(1, 1, 8, 0, 0, 0, 0, new byte[]{0, 1}, 1, List.of()));
        assertRejects(png(1, 1, 16, 2, 0, 0, 0, new byte[]{0, 1, 2, 3}, 1, List.of()));
        assertRejects(png(1, 1, 8, 3, 0, 0, 0, new byte[]{0, 1}, 1, List.of()));
        assertRejects(png(1, 1, 8, 2, 1, 0, 0, new byte[]{0, 1, 2, 3}, 1, List.of()));
        assertRejects(png(1, 1, 8, 2, 0, 1, 0, new byte[]{0, 1, 2, 3}, 1, List.of()));
        assertRejects(png(1, 1, 8, 2, 0, 0, 1, new byte[]{0, 1, 2, 3}, 1, List.of()));
        assertRejects(png(4097, 1, 8, 2, 0, 0, 0, new byte[0], 1, List.of()));
        assertRejects(png(4096, 4096, 8, 6, 0, 0, 0, new byte[0], 1, List.of()));
    }

    @Test
    public void rejectsTruncationWrongScanlineSizeAndInvalidFilter() throws Exception {
        byte[] valid = png(1, 1, 8, 2, 0, 0, 0,
                new byte[]{0, 1, 2, 3}, 1, List.of());
        assertRejects(Arrays.copyOf(valid, valid.length - 1));
        assertRejects(png(1, 1, 8, 2, 0, 0, 0, new byte[]{0, 1, 2}, 1, List.of()));
        assertRejects(png(1, 1, 8, 2, 0, 0, 0, new byte[]{5, 1, 2, 3}, 1, List.of()));
        assertRejects(png(1, 1, 8, 2, 0, 0, 0, new byte[]{0, 1, 2, 3, 4}, 1, List.of()));
    }

    @Test
    public void rejectsTrailingCompressedInflatedAndOversizedData() throws Exception {
        byte[] ihdr = ihdr(1, 1, 8, 2, 0, 0, 0);
        byte[] compressed = zlib(new byte[]{0, 1, 2, 3});
        byte[] compressedTrailing = Arrays.copyOf(compressed, compressed.length + 1);
        compressedTrailing[compressed.length] = 0x55;
        System.arraycopy(compressed, 0, compressedTrailing, 0, compressed.length);
        assertRejects(chunks(new Chunk("IHDR", ihdr), new Chunk("IDAT", compressedTrailing),
                new Chunk("IEND", new byte[0])));
        assertRejects(png(1, 1, 8, 2, 0, 0, 0,
                new byte[]{0, 1, 2, 3, 4}, 1, List.of()));

        ByteArrayOutputStream oversized = new ByteArrayOutputStream();
        oversized.write(SIGNATURE);
        writeChunk(oversized, new Chunk("IHDR", ihdr));
        DataOutputStream output = new DataOutputStream(oversized);
        output.writeInt(64 * 1024 * 1024 + 1);
        output.write("IDAT".getBytes(StandardCharsets.US_ASCII));
        output.flush();
        assertRejects(oversized.toByteArray());
    }

    @Test
    public void rejectsTooManyChunksAndCumulativeEncodedBytes() throws Exception {
        List<Chunk> chunks = new ArrayList<>();
        for (int index = 0; index < 1025; index++) {
            chunks.add(new Chunk(ancillaryType(index), new byte[0]));
        }
        assertRejects(png(1, 1, 8, 2, 0, 0, 0,
                new byte[]{0, 1, 2, 3}, 1, chunks));

        assertRejects(oversizedEncodedPng(32 * 1024 * 1024));
    }

    private static byte[] png(int width, int height, int bitDepth, int colorType,
                              int compression, int filter, int interlace, byte[] scanlines,
                              int idatParts, List<Chunk> ancillary) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        chunks.add(new Chunk("IHDR", ihdr(width, height, bitDepth, colorType,
                compression, filter, interlace)));
        chunks.addAll(ancillary);
        byte[] compressed = zlib(scanlines);
        int partSize = Math.max(1, (compressed.length + idatParts - 1) / idatParts);
        for (int offset = 0; offset < compressed.length; offset += partSize) {
            chunks.add(new Chunk("IDAT", Arrays.copyOfRange(compressed, offset,
                    Math.min(compressed.length, offset + partSize))));
        }
        chunks.add(new Chunk("IEND", new byte[0]));
        return chunks(chunks.toArray(new Chunk[0]));
    }

    private static byte[] chunks(Chunk... chunks) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(SIGNATURE);
        for (Chunk chunk : chunks) {
            writeChunk(output, chunk);
        }
        return output.toByteArray();
    }

    private static byte[] chunkBytes(Chunk chunk) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeChunk(output, chunk);
        return output.toByteArray();
    }

    private static String ancillaryType(int index) {
        return "a"
                + (char) ('A' + index / (26 * 26))
                + (char) ('A' + (index / 26) % 26)
                + (char) ('A' + index % 26);
    }

    private static InputStream oversizedEncodedPng(int ancillarySize) throws IOException {
        List<InputStream> parts = new ArrayList<>();
        parts.add(new ByteArrayInputStream(chunks(
                new Chunk("IHDR", ihdr(1, 1, 8, 2, 0, 0, 0)))));
        parts.add(new ZeroChunkInputStream("tEXt", ancillarySize));
        parts.add(new ZeroChunkInputStream("zTXt", ancillarySize));
        parts.add(new ByteArrayInputStream(chunkBytes(new Chunk("IDAT", zlib(
                new byte[]{0, 1, 2, 3})))));
        parts.add(new ByteArrayInputStream(chunkBytes(new Chunk("IEND", new byte[0]))));
        return new SequenceInputStream(Collections.enumeration(parts));
    }

    private static void writeChunk(ByteArrayOutputStream output, Chunk chunk) throws IOException {
        byte[] type = chunk.type().getBytes(StandardCharsets.US_ASCII);
        if (type.length != 4) {
            throw new IllegalArgumentException("PNG chunk type must have four bytes");
        }
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(chunk.data().length);
        data.write(type);
        data.write(chunk.data());
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(chunk.data());
        data.writeInt((int) crc.getValue());
        data.flush();
    }

    private static byte[] ihdr(int width, int height, int bitDepth, int colorType,
                               int compression, int filter, int interlace) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(width);
        data.writeInt(height);
        data.writeByte(bitDepth);
        data.writeByte(colorType);
        data.writeByte(compression);
        data.writeByte(filter);
        data.writeByte(interlace);
        data.flush();
        return output.toByteArray();
    }

    private static byte[] zlib(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(bytes);
        }
        return output.toByteArray();
    }

    private static byte[] encodeRows(byte[][] rows, int channels, int[] filters) {
        int rowBytes = rows[0].length;
        byte[] result = new byte[(rowBytes + 1) * rows.length];
        int offset = 0;
        for (int row = 0; row < rows.length; row++) {
            int filter = filters[row];
            result[offset++] = (byte) filter;
            for (int column = 0; column < rowBytes; column++) {
                int raw = rows[row][column] & 0xff;
                int left = column >= channels ? rows[row][column - channels] & 0xff : 0;
                int above = row > 0 ? rows[row - 1][column] & 0xff : 0;
                int aboveLeft = row > 0 && column >= channels
                        ? rows[row - 1][column - channels] & 0xff : 0;
                int prediction = switch (filter) {
                    case 0 -> 0;
                    case 1 -> left;
                    case 2 -> above;
                    case 3 -> (left + above) / 2;
                    case 4 -> paeth(left, above, aboveLeft);
                    default -> throw new IllegalArgumentException("filter");
                };
                result[offset++] = (byte) (raw - prediction);
            }
        }
        return result;
    }

    private static int paeth(int left, int above, int aboveLeft) {
        int predictor = left + above - aboveLeft;
        int leftDistance = Math.abs(predictor - left);
        int aboveDistance = Math.abs(predictor - above);
        int aboveLeftDistance = Math.abs(predictor - aboveLeft);
        if (leftDistance <= aboveDistance && leftDistance <= aboveLeftDistance) {
            return left;
        }
        if (aboveDistance <= aboveLeftDistance) {
            return above;
        }
        return aboveLeft;
    }

    private static void assertRejects(byte[] bytes) {
        assertRejects(new ByteArrayInputStream(bytes));
    }

    private static void assertRejects(InputStream input) {
        try {
            PngArgbDecoder.decode(input);
            fail("Expected malformed PNG rejection");
        } catch (IOException expected) {
            // Expected malformed fixture.
        }
    }

    private static final class ZeroChunkInputStream extends InputStream {

        private final byte[] header;
        private final byte[] crc;
        private long zeroBytes;
        private int headerOffset;
        private int crcOffset;

        private ZeroChunkInputStream(String type, int length) throws IOException {
            byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
            header = new byte[8];
            DataOutputStream output = new DataOutputStream(new ByteArrayOutputStreamAdapter(header));
            output.writeInt(length);
            output.write(typeBytes);
            output.flush();

            CRC32 checksum = new CRC32();
            checksum.update(typeBytes);
            byte[] zeros = new byte[8192];
            int remaining = length;
            while (remaining > 0) {
                int count = Math.min(remaining, zeros.length);
                checksum.update(zeros, 0, count);
                remaining -= count;
            }
            ByteArrayOutputStream crcOutput = new ByteArrayOutputStream(4);
            DataOutputStream crcData = new DataOutputStream(crcOutput);
            crcData.writeInt((int) checksum.getValue());
            crcData.flush();
            crc = crcOutput.toByteArray();
            zeroBytes = length;
        }

        @Override
        public int read() {
            if (headerOffset < header.length) {
                return header[headerOffset++] & 0xff;
            }
            if (zeroBytes > 0) {
                zeroBytes--;
                return 0;
            }
            if (crcOffset < crc.length) {
                return crc[crcOffset++] & 0xff;
            }
            return -1;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (destination == null) {
                throw new NullPointerException("destination");
            }
            if (offset < 0 || length < 0 || offset > destination.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) {
                return 0;
            }
            int count = 0;
            if (headerOffset < header.length) {
                int copied = Math.min(length, header.length - headerOffset);
                System.arraycopy(header, headerOffset, destination, offset, copied);
                headerOffset += copied;
                count += copied;
            }
            if (count < length && zeroBytes > 0) {
                int copied = (int) Math.min((long) (length - count), zeroBytes);
                Arrays.fill(destination, offset + count, offset + count + copied, (byte) 0);
                zeroBytes -= copied;
                count += copied;
            }
            if (count < length && crcOffset < crc.length) {
                int copied = Math.min(length - count, crc.length - crcOffset);
                System.arraycopy(crc, crcOffset, destination, offset + count, copied);
                crcOffset += copied;
                count += copied;
            }
            return count == 0 ? -1 : count;
        }
    }

    private static final class ByteArrayOutputStreamAdapter extends java.io.OutputStream {

        private final byte[] destination;
        private int offset;

        private ByteArrayOutputStreamAdapter(byte[] destination) {
            this.destination = destination;
        }

        @Override
        public void write(int value) {
            destination[offset++] = (byte) value;
        }

        @Override
        public void write(byte[] bytes, int sourceOffset, int length) {
            System.arraycopy(bytes, sourceOffset, destination, offset, length);
            offset += length;
        }
    }

    private record Chunk(String type, byte[] data) {
    }
}
