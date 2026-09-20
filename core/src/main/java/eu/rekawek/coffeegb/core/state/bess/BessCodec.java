package eu.rekawek.coffeegb.core.state.bess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reader and writer for BESS 1.1. Buffer pointers are absolute file offsets, so the reader also
 * accepts BESS data appended to another emulator's native state.
 *
 * @see <a href="https://github.com/LIJI32/SameBoy/blob/master/BESS.md">BESS specification</a>
 */
public final class BessCodec {

    public static final int MAX_FILE_SIZE = 64 * 1024 * 1024;

    // Register replay is a small command sequence, not a bulk memory transport. Bound the
    // object count before expanding a hostile MBC block into individual writes.
    private static final int MAX_MBC_WRITES = 4096;

    private static final int CORE_SIZE = 0xd0;

    private static final Map<String, Integer> EXTENSION_SIZES = Map.of(
            "XOAM", 0x60, "RTC ", 0x30, "HUC3", 0x11, "TPP1", 0x11, "MBC7", 0x0a);

    private BessCodec() {
    }

    public static BessState read(byte[] file) throws IOException {
        require(file != null && file.length >= 8, "Missing BESS footer");
        require(file.length <= MAX_FILE_SIZE, "BESS state is too large");
        int footer = file.length - 8;
        require(identifier(file, footer + 4).equals("BESS"), "Missing BESS footer");
        long firstBlock = unsignedInt(file, footer);
        require(firstBlock <= footer - 8L, "Invalid first BESS block offset");

        String name = null;
        byte[] info = null;
        BessState.Core core = null;
        BessState.Sgb sgb = null;
        long[] copiedMemoryBytes = {0};
        List<BessState.MbcWrite> writes = new ArrayList<>();
        Map<String, byte[]> extensions = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int position = (int) firstBlock;
        while (position < footer) {
            require(position <= footer - 8, "Truncated BESS block header");
            String id = identifier(file, position);
            long length = unsignedInt(file, position + 4);
            int start = position + 8;
            require(length <= footer - (long) start, "Invalid length of BESS " + id + " block");
            int size = (int) length;
            position = start + size;

            boolean known = id.equals("NAME") || id.equals("INFO") || id.equals("CORE")
                    || id.equals("MBC ") || id.equals("SGB ") || id.equals("END ")
                    || EXTENSION_SIZES.containsKey(id);
            if (!known) {
                // Unknown blocks may occur anywhere, including before CORE.
                continue;
            }
            require(seen.add(id), "Duplicate BESS " + id + " block");
            switch (id) {
                case "NAME" -> {
                    require(core == null && info == null, "BESS NAME must precede INFO and CORE");
                    name = new String(file, start, size, StandardCharsets.US_ASCII);
                }
                case "INFO" -> {
                    require(core == null, "BESS INFO must precede CORE");
                    require(size == 0x12, "Invalid BESS INFO length");
                    info = Arrays.copyOfRange(file, start, position);
                }
                case "CORE" -> core = readCore(file, start, size, copiedMemoryBytes);
                case "SGB " -> {
                    require(core != null, "BESS SGB must follow CORE");
                    require(core.model().charAt(0) == 'S', "BESS SGB block requires an SGB model");
                    require(size >= 0x39, "Invalid BESS SGB length");
                    byte[][] buffers = readBuffers(file, start, copiedMemoryBytes);
                    int multiplayer = file[start + 0x38] & 0xff;
                    validateMultiplayer(multiplayer);
                    sgb = new BessState.Sgb(buffers[0], buffers[1], buffers[2], buffers[3],
                            buffers[4], buffers[5], buffers[6], multiplayer);
                }
                case "END " -> {
                    require(core != null, "Missing BESS CORE block");
                    require(size == 0, "BESS END block must be empty");
                    return new BessState(name, info, core, writes, extensions, sgb);
                }
                default -> {
                    require(core != null, "BESS " + id + " must follow CORE");
                    if (id.equals("MBC ")) {
                        require(size % 3 == 0, "Invalid BESS MBC length");
                        require(size / 3 <= MAX_MBC_WRITES, "BESS MBC block has too many writes");
                        for (int offset = start; offset < position; offset += 3) {
                            int address = unsignedShort(file, offset);
                            validateMbcAddress(address);
                            writes.add(new BessState.MbcWrite(address, file[offset + 2] & 0xff));
                        }
                    } else {
                        require(size == EXTENSION_SIZES.get(id), "Invalid BESS " + id + " length");
                        extensions.put(id, Arrays.copyOfRange(file, start, position));
                    }
                }
            }
        }
        throw new IOException("Missing BESS END block");
    }

    private static BessState.Core readCore(byte[] file, int start, int size, long[] copiedMemoryBytes)
            throws IOException {
        require(size >= CORE_SIZE, "Truncated BESS CORE block");
        require(unsignedShort(file, start) == 1, "Unsupported BESS major version");
        // Newer minor versions and appended CORE fields remain forward compatible.
        String model = identifier(file, start + 4);
        validateModel(model);
        int ime = file[start + 0x14] & 0xff;
        int executionState = file[start + 0x16] & 0xff;
        require(ime <= 1, "Invalid BESS IME value");
        require(executionState <= 2, "Invalid BESS execution state");

        byte[][] buffers = readBuffers(file, start + 0x98, copiedMemoryBytes);
        return new BessState.Core(model, unsignedShort(file, start + 8),
                unsignedShort(file, start + 0x0a), unsignedShort(file, start + 0x0c),
                unsignedShort(file, start + 0x0e), unsignedShort(file, start + 0x10),
                unsignedShort(file, start + 0x12), ime != 0, file[start + 0x15] & 0xff,
                executionState, Arrays.copyOfRange(file, start + 0x18, start + 0x98),
                buffers[0], buffers[1], buffers[2], buffers[3], buffers[4], buffers[5], buffers[6]);
    }

    private static byte[][] readBuffers(byte[] file, int start, long[] copiedMemoryBytes)
            throws IOException {
        byte[][] buffers = new byte[7][];
        for (int i = 0; i < buffers.length; i++) {
            int descriptor = start + i * 8;
            long length = unsignedInt(file, descriptor);
            long offset = unsignedInt(file, descriptor + 4);
            copiedMemoryBytes[0] += length;
            require(copiedMemoryBytes[0] <= MAX_FILE_SIZE, "BESS memory buffers are too large");
            if (length == 0) {
                // An absent buffer's pointer is unused, and may have any value.
                buffers[i] = new byte[0];
            } else {
                require(offset <= file.length && length <= file.length - offset,
                        "BESS memory buffer is outside the file");
                buffers[i] = Arrays.copyOfRange(file, (int) offset, (int) (offset + length));
            }
        }
        return buffers;
    }

    public static byte[] write(BessState state) throws IOException {
        require(state != null && state.core() != null, "Missing BESS core state");
        BessState.Core core = state.core();
        validateModel(core.model());
        require(core.io() != null && core.io().length == 0x80, "BESS requires 128 IO registers");
        require(core.executionState() >= 0 && core.executionState() <= 2,
                "Invalid BESS execution state");
        require(state.info() == null || state.info().length == 0x12, "Invalid BESS INFO length");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer body = ByteBuffer.allocate(CORE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        body.putShort((short) 1).putShort((short) 1);
        body.put(core.model().getBytes(StandardCharsets.US_ASCII));
        for (int value : new int[]{core.pc(), core.af(), core.bc(), core.de(), core.hl(), core.sp()}) {
            require(value >= 0 && value <= 0xffff, "Invalid BESS CPU register value");
            body.putShort((short) value);
        }
        require(core.ie() >= 0 && core.ie() <= 0xff, "Invalid BESS IE value");
        body.put((byte) (core.ime() ? 1 : 0)).put((byte) core.ie());
        body.put((byte) core.executionState()).put((byte) 0).put(core.io());
        byte[][] buffers = {core.ram(), core.vram(), core.mbcRam(), core.oam(), core.hram(),
                core.bgPalettes(), core.objPalettes()};
        for (byte[] buffer : buffers) {
            require(buffer != null, "Missing BESS memory buffer");
            body.putInt(buffer.length).putInt(output.size());
            append(output, buffer);
        }

        ByteBuffer sgbBody = null;
        if (state.sgb() != null) {
            require(core.model().charAt(0) == 'S', "BESS SGB block requires an SGB model");
            BessState.Sgb sgb = state.sgb();
            validateMultiplayer(sgb.multiplayerStatus());
            sgbBody = ByteBuffer.allocate(0x39).order(ByteOrder.LITTLE_ENDIAN);
            byte[][] sgbBuffers = {sgb.borderTiles(), sgb.borderTilemap(), sgb.borderPalettes(),
                    sgb.activePalettes(), sgb.ramPalettes(), sgb.attributeMap(), sgb.attributeFiles()};
            for (byte[] buffer : sgbBuffers) {
                require(buffer != null, "Missing BESS SGB memory buffer");
                sgbBody.putInt(buffer.length).putInt(output.size());
                append(output, buffer);
            }
            sgbBody.put((byte) sgb.multiplayerStatus());
        }

        int firstBlock = output.size();
        if (state.name() != null) {
            writeBlock(output, "NAME", state.name().getBytes(StandardCharsets.US_ASCII));
        }
        if (state.info() != null) {
            writeBlock(output, "INFO", state.info());
        }
        writeBlock(output, "CORE", body.array());
        if (!state.mbcWrites().isEmpty()) {
            require(state.mbcWrites().size() <= MAX_MBC_WRITES, "BESS MBC block has too many writes");
            ByteBuffer mbc = ByteBuffer.allocate(state.mbcWrites().size() * 3)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (BessState.MbcWrite write : state.mbcWrites()) {
                validateMbcAddress(write.address());
                require(write.value() >= 0 && write.value() <= 0xff, "Invalid BESS MBC value");
                mbc.putShort((short) write.address()).put((byte) write.value());
            }
            writeBlock(output, "MBC ", mbc.array());
        }
        // Sort extension names for deterministic output, without changing MBC write order.
        for (String id : state.extensions().keySet().stream().sorted().toList()) {
            require(id != null && id.length() == 4 && id.chars().allMatch(c -> c >= 0x20 && c < 0x7f),
                    "Invalid BESS extension identifier");
            require(!Set.of("NAME", "INFO", "CORE", "MBC ", "END ", "SGB ").contains(id),
                    "Reserved BESS extension identifier: " + id);
            byte[] extension = state.extensions().get(id);
            require(extension != null, "Missing BESS extension payload");
            if (EXTENSION_SIZES.containsKey(id)) {
                require(extension.length == EXTENSION_SIZES.get(id), "Invalid BESS " + id + " length");
            }
            writeBlock(output, id, extension);
        }
        if (sgbBody != null) {
            writeBlock(output, "SGB ", sgbBody.array());
        }
        writeBlock(output, "END ", new byte[0]);
        ByteBuffer footer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        footer.putInt(firstBlock).put("BESS".getBytes(StandardCharsets.US_ASCII));
        append(output, footer.array());
        return output.toByteArray();
    }

    private static void validateModel(String model) throws IOException {
        require(model != null && model.length() == 4
                        && model.chars().allMatch(c -> c >= 0x20 && c < 0x7f)
                        && "GSC".indexOf(model.charAt(0)) >= 0 && model.charAt(3) == ' ',
                "Unsupported BESS model identifier");
    }

    private static void validateMbcAddress(int address) throws IOException {
        require(address >= 0 && (address <= 0x7fff || address >= 0xa000 && address <= 0xbfff),
                "Invalid BESS MBC register address");
    }

    private static void validateMultiplayer(int value) throws IOException {
        int players = value >>> 4;
        int player = value & 15;
        require((players == 1 || players == 2 || players == 4) && player < players,
                "Invalid BESS SGB multiplayer status");
    }

    private static void writeBlock(ByteArrayOutputStream output, String id, byte[] body) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(body.length);
        append(output, header.array());
        append(output, body);
    }

    private static void append(ByteArrayOutputStream output, byte[] bytes) throws IOException {
        require(bytes.length <= MAX_FILE_SIZE - output.size(), "BESS state is too large");
        output.writeBytes(bytes);
    }

    private static String identifier(byte[] data, int offset) {
        return new String(data, offset, 4, StandardCharsets.US_ASCII);
    }

    private static int unsignedShort(byte[] data, int offset) {
        return (data[offset] & 0xff) | (data[offset + 1] & 0xff) << 8;
    }

    private static long unsignedInt(byte[] data, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }
}
