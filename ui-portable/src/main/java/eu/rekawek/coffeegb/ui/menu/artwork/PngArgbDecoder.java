package eu.rekawek.coffeegb.ui.menu.artwork;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Package-private, stateless PNG decoder for the portable Proposal 3 raster boundary. */
final class PngArgbDecoder {

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'
    };
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 16_777_216L;
    private static final long MAX_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_CHUNKS = 1024;

    private PngArgbDecoder() {
    }

    static MenuArgbFrame decode(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream stream = new DataInputStream(input);
        byte[] signature = new byte[SIGNATURE.length];
        stream.readFully(signature);
        if (!java.util.Arrays.equals(SIGNATURE, signature)) {
            throw new IOException("Invalid PNG signature");
        }

        PngHeader header = null;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Set<String> seenNonIdatChunks = new HashSet<>();
        boolean firstChunk = true;
        boolean idatSeen = false;
        boolean iendSeen = false;
        long encodedBytes = SIGNATURE.length;
        int chunkCount = 0;

        while (!iendSeen) {
            int length = stream.readInt();
            if (length < 0 || length > MAX_BYTES) {
                throw new IOException("PNG chunk exceeds the supported size");
            }
            if (++chunkCount > MAX_CHUNKS) {
                throw new IOException("PNG contains too many chunks");
            }
            long encodedChunkBytes = 12L + length;
            if (encodedChunkBytes > MAX_BYTES - encodedBytes) {
                throw new IOException("PNG encoded data exceeds the supported size");
            }
            encodedBytes += encodedChunkBytes;
            byte[] type = new byte[4];
            stream.readFully(type);
            validateChunkType(type);
            String chunkType = new String(type, java.nio.charset.StandardCharsets.US_ASCII);

            if (firstChunk && !"IHDR".equals(chunkType)) {
                throw new IOException("IHDR must be the first PNG chunk");
            }
            firstChunk = false;
            if (idatSeen && !"IDAT".equals(chunkType) && !"IEND".equals(chunkType)) {
                throw new IOException("IDAT chunks must be consecutive");
            }
            if (!"IDAT".equals(chunkType) && !seenNonIdatChunks.add(chunkType)) {
                throw new IOException("Duplicate PNG chunk: " + chunkType);
            }

            byte[] data = new byte[length];
            stream.readFully(data);
            long expectedCrc = Integer.toUnsignedLong(stream.readInt());
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(data);
            if (crc.getValue() != expectedCrc) {
                throw new IOException("PNG chunk CRC mismatch: " + chunkType);
            }
            if (isCritical(type) && !isKnownCritical(chunkType)) {
                throw new IOException("Unknown critical PNG chunk: " + chunkType);
            }

            switch (chunkType) {
                case "IHDR" -> header = parseHeader(header, data);
                case "PLTE" -> validatePalette(header, idatSeen, data);
                case "IDAT" -> {
                    idatSeen = true;
                    if ((long) compressed.size() + data.length > MAX_BYTES) {
                        throw new IOException("PNG compressed data exceeds the supported size");
                    }
                    compressed.write(data);
                }
                case "tRNS" -> throw new IOException("PNG tRNS transparency is unsupported");
                case "IEND" -> {
                    if (!idatSeen || data.length != 0) {
                        throw new IOException("IEND must terminate a PNG after IDAT");
                    }
                    iendSeen = true;
                }
                default -> {
                    // Valid ancillary chunks are intentionally ignored after CRC validation.
                }
            }
        }

        if (header == null || !idatSeen || !iendSeen) {
            throw new IOException("PNG is missing IHDR, IDAT, or IEND");
        }
        if (stream.read() != -1) {
            throw new IOException("PNG has trailing data after IEND");
        }

        byte[] scanlines = inflateExact(compressed.toByteArray(), header.inflatedSize);
        return decodeScanlines(header, scanlines);
    }

    private static PngHeader parseHeader(PngHeader previous, byte[] data) throws IOException {
        if (previous != null || data.length != 13) {
            throw new IOException("PNG must contain exactly one 13-byte IHDR");
        }
        DataInputStream header = new DataInputStream(new java.io.ByteArrayInputStream(data));
        int width = header.readInt();
        int height = header.readInt();
        int bitDepth = header.readUnsignedByte();
        int colorType = header.readUnsignedByte();
        int compressionMethod = header.readUnsignedByte();
        int filterMethod = header.readUnsignedByte();
        int interlaceMethod = header.readUnsignedByte();
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("PNG dimensions exceed the supported limits");
        }
        if (bitDepth != 8 || (colorType != 2 && colorType != 6)
                || compressionMethod != 0 || filterMethod != 0 || interlaceMethod != 0) {
            throw new IOException("Unsupported PNG pixel format or method");
        }
        int channels = colorType == 2 ? 3 : 4;
        long pixels = (long) width * height;
        long rowBytes = (long) width * channels;
        long inflatedSize = (rowBytes + 1) * height;
        if (pixels > MAX_PIXELS || inflatedSize > MAX_BYTES || rowBytes > Integer.MAX_VALUE) {
            throw new IOException("PNG decoded data exceeds the supported limits");
        }
        return new PngHeader(width, height, colorType, channels, (int) rowBytes, (int) inflatedSize);
    }

    private static void validatePalette(PngHeader header, boolean idatSeen, byte[] data) throws IOException {
        if (idatSeen || data.length == 0 || data.length > 768 || data.length % 3 != 0) {
            throw new IOException("Invalid PLTE placement or size");
        }
        if (header == null) {
            throw new IOException("PLTE appeared before IHDR");
        }
    }

    private static byte[] inflateExact(byte[] compressed, int expectedSize) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] result = new byte[expectedSize];
            int position = 0;
            while (!inflater.finished()) {
                int count;
                if (position < result.length) {
                    count = inflate(inflater, result, position, result.length - position);
                } else {
                    byte[] probe = new byte[1];
                    count = inflate(inflater, probe, 0, 1);
                    if (count > 0) {
                        throw new IOException("PNG inflated data exceeds the IHDR scanline size");
                    }
                }
                if (count > 0) {
                    position += count;
                    continue;
                }
                if (inflater.needsDictionary()) {
                    throw new IOException("PNG inflater requires an unsupported dictionary");
                }
                if (inflater.needsInput()) {
                    throw new IOException("Truncated PNG compressed data");
                }
                throw new IOException("PNG inflater made no progress");
            }
            if (position != expectedSize) {
                throw new IOException("PNG inflated data is shorter than the IHDR scanline size");
            }
            if (inflater.getRemaining() != 0) {
                throw new IOException("PNG compressed data has trailing bytes");
            }
            return result;
        } catch (DataFormatException e) {
            throw new IOException("Invalid PNG compressed data", e);
        } finally {
            inflater.end();
        }
    }

    private static int inflate(Inflater inflater, byte[] output, int offset, int length)
            throws DataFormatException {
        return inflater.inflate(output, offset, length);
    }

    private static MenuArgbFrame decodeScanlines(PngHeader header, byte[] scanlines) throws IOException {
        int expectedSize = (header.rowBytes + 1) * header.height;
        if (scanlines.length != expectedSize) {
            throw new IOException("PNG scanline size does not match IHDR");
        }
        int[] pixels = new int[header.width * header.height];
        byte[] previous = new byte[header.rowBytes];
        byte[] current = new byte[header.rowBytes];
        int inputOffset = 0;
        int pixelOffset = 0;
        for (int row = 0; row < header.height; row++) {
            int filter = scanlines[inputOffset++] & 0xff;
            if (filter > 4) {
                throw new IOException("Unsupported PNG filter: " + filter);
            }
            for (int column = 0; column < header.rowBytes; column++) {
                int filtered = scanlines[inputOffset++] & 0xff;
                int left = column >= header.channels ? current[column - header.channels] & 0xff : 0;
                int above = previous[column] & 0xff;
                int aboveLeft = column >= header.channels ? previous[column - header.channels] & 0xff : 0;
                int value;
                switch (filter) {
                    case 0 -> value = filtered;
                    case 1 -> value = filtered + left;
                    case 2 -> value = filtered + above;
                    case 3 -> value = filtered + ((left + above) / 2);
                    case 4 -> value = filtered + paeth(left, above, aboveLeft);
                    default -> throw new IOException("Unsupported PNG filter: " + filter);
                }
                current[column] = (byte) value;
            }
            for (int column = 0; column < header.width; column++) {
                int offset = column * header.channels;
                int red = current[offset] & 0xff;
                int green = current[offset + 1] & 0xff;
                int blue = current[offset + 2] & 0xff;
                int alpha = header.channels == 4 ? current[offset + 3] & 0xff : 0xff;
                pixels[pixelOffset++] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
            byte[] swap = previous;
            previous = current;
            current = swap;
        }
        return MenuArgbFrame.trusted(header.width, header.height, pixels);
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

    private static void validateChunkType(byte[] type) throws IOException {
        for (byte value : type) {
            int character = value & 0xff;
            if (!((character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z'))) {
                throw new IOException("Invalid PNG chunk type");
            }
        }
        int reserved = type[2] & 0xff;
        if (reserved < 'A' || reserved > 'Z') {
            throw new IOException("PNG chunk type reserved byte must be uppercase");
        }
    }

    private static boolean isCritical(byte[] type) {
        return (type[0] & 0x20) == 0;
    }

    private static boolean isKnownCritical(String type) {
        return "IHDR".equals(type) || "PLTE".equals(type)
                || "IDAT".equals(type) || "IEND".equals(type);
    }

    private record PngHeader(
            int width,
            int height,
            int colorType,
            int channels,
            int rowBytes,
            int inflatedSize) {
    }
}
