package eu.rekawek.coffeegb.core.state.bess;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BessCodecTest {

    @Test
    public void readsIndependentlyConstructedAppendedState() throws IOException {
        // This layout deliberately differs from our writer: buffers live in a native prefix,
        // unknown blocks precede CORE, and the producer uses a newer minor version and CORE size.
        Fixture fixture = new Fixture();
        fixture.raw(ascii("NATIVE STATE FORMAT"));
        int ramOffset = fixture.size();
        byte[] ram = pattern(0x8000, 11);
        fixture.raw(ram);
        int paletteOffset = fixture.size();
        byte[] palette = pattern(64, 63);
        fixture.raw(palette);
        fixture.startBlocks();
        fixture.block("NEW!", new byte[]{3, 9});
        fixture.block("NAME", ascii("Another Emulator 7.3"));
        fixture.block("INFO", pattern(18, 22));
        byte[] core = Arrays.copyOf(core(), 0xe0);
        le(core).putShort(2, (short) 27);
        System.arraycopy(ascii("CCE "), 0, core, 4, 4);
        le(core).putInt(0x98, ram.length).putInt(0x9c, ramOffset);
        le(core).putInt(0xc0, palette.length).putInt(0xc4, paletteOffset);
        fixture.block("CORE", core);
        fixture.block("MBC ", new byte[]{0, 0x20, 6, 0, 0, 0x0a, 0, 0x20, 7});
        fixture.block("RTC ", pattern(48, 15));
        fixture.block("NEXT", new byte[]{1, 2, 3});
        fixture.block("END ", new byte[0]);

        BessState state = BessCodec.read(fixture.finish());
        assertEquals("Another Emulator 7.3", state.name());
        assertArrayEquals(pattern(18, 22), state.info());
        assertEquals("CCE ", state.core().model());
        assertEquals(0x4567, state.core().pc());
        assertEquals(0x89b0, state.core().af());
        assertEquals(0x2345, state.core().bc());
        assertEquals(0x6789, state.core().de());
        assertEquals(0xabcd, state.core().hl());
        assertEquals(0xfffe, state.core().sp());
        assertTrue(state.core().ime());
        assertEquals(0x1f, state.core().ie());
        assertEquals(1, state.core().executionState());
        assertArrayEquals(pattern(128, 3), state.core().io());
        assertArrayEquals(ram, state.core().ram());
        assertArrayEquals(palette, state.core().bgPalettes());
        assertEquals(List.of(new BessState.MbcWrite(0x2000, 6),
                new BessState.MbcWrite(0, 0x0a), new BessState.MbcWrite(0x2000, 7)), state.mbcWrites());
        assertEquals(1, state.extensions().size());
        assertArrayEquals(pattern(48, 15), state.extensions().get("RTC "));
    }

    @Test
    public void writesStandardLayoutWithAbsoluteMemoryPointers() throws IOException {
        BessState state = state();
        byte[] file = BessCodec.write(state);
        assertArrayEquals(ascii("BESS"), Arrays.copyOfRange(file, file.length - 4, file.length));
        int block = le(file).getInt(file.length - 8);
        assertEquals("NAME", id(file, block));
        block += 8 + le(file).getInt(block + 4);
        assertEquals("INFO", id(file, block));
        assertEquals(18, le(file).getInt(block + 4));
        block += 26;
        assertEquals("CORE", id(file, block));
        assertEquals(0xd0, le(file).getInt(block + 4));
        int core = block + 8;
        assertEquals(1, le(file).getShort(core));
        assertEquals(1, le(file).getShort(core + 2));
        assertEquals("CCE ", id(file, core + 4));
        assertEquals(0x1234, le(file).getShort(core + 8) & 0xffff);
        byte[][] expected = {state.core().ram(), state.core().vram(), state.core().mbcRam(),
                state.core().oam(), state.core().hram(), state.core().bgPalettes(), state.core().objPalettes()};
        for (int i = 0; i < expected.length; i++) {
            int size = le(file).getInt(core + 0x98 + i * 8);
            int offset = le(file).getInt(core + 0x9c + i * 8);
            assertEquals(expected[i].length, size);
            assertTrue(offset + size <= block);
            assertArrayEquals(expected[i], Arrays.copyOfRange(file, offset, offset + size));
        }
        assertEquals("END ", id(file, file.length - 16));
        assertEquals(0, le(file).getInt(file.length - 12));
    }

    @Test
    public void roundTripsEveryMemoryBufferAndMapperExtension() throws IOException {
        BessState expected = state();
        BessState actual = BessCodec.read(BessCodec.write(expected));
        assertEquals(expected.name(), actual.name());
        assertArrayEquals(expected.info(), actual.info());
        assertArrayEquals(expected.core().io(), actual.core().io());
        assertArrayEquals(expected.core().ram(), actual.core().ram());
        assertArrayEquals(expected.core().vram(), actual.core().vram());
        assertArrayEquals(expected.core().mbcRam(), actual.core().mbcRam());
        assertArrayEquals(expected.core().oam(), actual.core().oam());
        assertArrayEquals(expected.core().hram(), actual.core().hram());
        assertArrayEquals(expected.core().bgPalettes(), actual.core().bgPalettes());
        assertArrayEquals(expected.core().objPalettes(), actual.core().objPalettes());
        assertEquals(expected.mbcWrites(), actual.mbcWrites());
        expected.extensions().forEach((key, value) -> assertArrayEquals(value, actual.extensions().get(key)));
        assertArrayEquals(BessCodec.write(expected), BessCodec.write(actual));
    }

    @Test
    public void acceptsAbsentOptionalBlocksAndUnusedPointers() throws IOException {
        byte[] core = core();
        for (int i = 0; i < 7; i++) {
            le(core).putInt(0x9c + i * 8, -1);
        }
        BessState state = BessCodec.read(fileWithCore(core));
        assertNull(state.name());
        assertNull(state.info());
        assertTrue(state.mbcWrites().isEmpty());
        assertTrue(state.extensions().isEmpty());
        assertEquals(0, state.core().ram().length);
    }

    @Test
    public void allowsBuffersAfterEndAndKeepsNonstandardSizesForBridgeAdaptation() throws IOException {
        Fixture fixture = new Fixture();
        fixture.startBlocks();
        byte[] core = core();
        int bufferOffset = 8 + core.length + 8;
        le(core).putInt(0xa0, 13).putInt(0xa4, bufferOffset);
        fixture.block("CORE", core);
        fixture.block("END ", new byte[0]);
        fixture.raw(pattern(13, 7));
        assertArrayEquals(pattern(13, 7), BessCodec.read(fixture.finish()).core().vram());
    }

    @Test
    public void rejectsMissingOrMalformedFooter() throws IOException {
        expectFailure(new byte[0], "footer");
        byte[] file = fileWithCore(core());
        file[file.length - 1] = 0;
        expectFailure(file, "footer");
        file = fileWithCore(core());
        le(file).putInt(file.length - 8, -1);
        expectFailure(file, "offset");
    }

    @Test
    public void rejectsMissingCoreAndEnd() throws IOException {
        Fixture fixture = new Fixture();
        fixture.block("END ", new byte[0]);
        expectFailure(fixture.finish(), "CORE");
        fixture = new Fixture();
        fixture.block("CORE", core());
        expectFailure(fixture.finish(), "END");
    }

    @Test
    public void rejectsTruncatedAndUnsignedOverflowingLengths() throws IOException {
        byte[] file = fileWithCore(core());
        le(file).putInt(4, -1);
        expectFailure(file, "length");
        file = fileWithCore(core());
        le(file).putInt(4, file.length);
        expectFailure(file, "length");
        Fixture fixture = new Fixture();
        fixture.block("CORE", core());
        fixture.raw(new byte[3]);
        expectFailure(fixture.finish(), "header");
        fixture = new Fixture();
        fixture.block("CORE", new byte[0xcf]);
        fixture.block("END ", new byte[0]);
        expectFailure(fixture.finish(), "CORE");
    }

    @Test
    public void rejectsDuplicateCoreAndKnownBlocksInWrongOrder() throws IOException {
        Fixture fixture = new Fixture();
        fixture.block("CORE", core());
        fixture.block("CORE", core());
        fixture.block("END ", new byte[0]);
        expectFailure(fixture.finish(), "Duplicate");
        fixture = new Fixture();
        fixture.block("MBC ", new byte[0]);
        fixture.block("CORE", core());
        fixture.block("END ", new byte[0]);
        expectFailure(fixture.finish(), "follow CORE");
        fixture = new Fixture();
        fixture.block("CORE", core());
        fixture.block("INFO", new byte[18]);
        fixture.block("END ", new byte[0]);
        expectFailure(fixture.finish(), "precede CORE");
        fixture = new Fixture();
        fixture.block("INFO", new byte[18]);
        fixture.block("NAME", ascii("Late name"));
        fixture.block("CORE", core());
        fixture.block("END ", new byte[0]);
        expectFailure(fixture.finish(), "precede INFO");
    }

    @Test
    public void rejectsInvalidVersionsAndExecutionStates() throws IOException {
        byte[] core = core();
        le(core).putShort(0, (short) 2);
        expectFailure(fileWithCore(core), "major version");
        core = core();
        core[0x14] = 2;
        expectFailure(fileWithCore(core), "IME");
        core = core();
        core[0x16] = 3;
        expectFailure(fileWithCore(core), "execution state");
    }

    @Test
    public void rejectsOutOfBoundsExternalMemoryWithoutIntegerOverflow() throws IOException {
        byte[] core = core();
        le(core).putInt(0x98, 1).putInt(0x9c, -1);
        expectFailure(fileWithCore(core), "outside the file");
        core = core();
        le(core).putInt(0x98, -1).putInt(0x9c, 0);
        expectFailure(fileWithCore(core), "too large");
        core = core();
        le(core).putInt(0x98, 64).putInt(0x9c, 220);
        expectFailure(fileWithCore(core), "outside the file");
    }

    @Test
    public void rejectsInvalidMbcAddressesAndPartialWrites() throws IOException {
        for (int address : new int[]{0x8000, 0x9fff, 0xc000, 0xffff}) {
            expectFailure(withBlock("MBC ", new byte[]{(byte) address, (byte) (address >> 8), 1}),
                    "address");
        }
        expectFailure(withBlock("MBC ", new byte[2]), "length");
        expectFailure(withBlock("MBC ", new byte[4097 * 3]), "too many writes");
    }

    @Test
    public void acceptsEveryValidMbcAddressBoundaryInOrder() throws IOException {
        byte[] writes = {0, 0, 1, (byte) 0xff, 0x7f, 2, 0, (byte) 0xa0, 3,
                (byte) 0xff, (byte) 0xbf, 4};
        assertEquals(List.of(new BessState.MbcWrite(0, 1), new BessState.MbcWrite(0x7fff, 2),
                new BessState.MbcWrite(0xa000, 3), new BessState.MbcWrite(0xbfff, 4)),
                BessCodec.read(withBlock("MBC ", writes)).mbcWrites());
    }

    @Test
    public void rejectsMalformedOptionalBlockLengthsAndNonemptyEnd() throws IOException {
        for (String id : List.of("RTC ", "XOAM", "HUC3", "TPP1", "MBC7")) {
            expectFailure(withBlock(id, new byte[1]), "length");
        }
        expectFailure(withBlock("END ", new byte[1]), "empty");
    }

    @Test
    public void rejectsReservedExtensionBlocksOnWrite() throws IOException {
        BessState state = state();
        BessState invalid = new BessState(state.name(), state.info(), state.core(), state.mbcWrites(),
                Map.of("CORE", new byte[0]));
        IOException failure = assertThrows(IOException.class, () -> BessCodec.write(invalid));
        assertTrue(failure.getMessage().contains("Reserved"));
    }

    @Test
    public void readsForeignSgbBuffersAndAllowsPartialAndExtendedBlocks() throws IOException {
        Fixture fixture = new Fixture();
        byte[] tiles = pattern(0x2000, 4);
        fixture.raw(tiles);
        byte[] palettes = pattern(0x20, 15);
        fixture.raw(palettes);
        fixture.startBlocks();
        byte[] core = core();
        System.arraycopy(ascii("S2  "), 0, core, 4, 4);
        fixture.block("CORE", core);
        byte[] sgb = new byte[0x40]; // Future extension bytes follow the v1.1 body.
        le(sgb).putInt(0, tiles.length).putInt(4, 0);
        le(sgb).putInt(0x18, palettes.length).putInt(0x1c, tiles.length);
        sgb[0x38] = 0x42;
        fixture.block("SGB ", sgb);
        fixture.block("END ", new byte[0]);

        BessState state = BessCodec.read(fixture.finish());
        assertEquals("S2  ", state.core().model());
        assertNotNull(state.sgb());
        assertArrayEquals(tiles, state.sgb().borderTiles());
        assertArrayEquals(palettes, state.sgb().activePalettes());
        assertEquals(0, state.sgb().borderTilemap().length);
        assertEquals(0, state.sgb().ramPalettes().length);
        assertEquals(0x42, state.sgb().multiplayerStatus());
    }

    @Test
    public void relocatesAndRoundTripsAllSgbBuffers() throws IOException {
        BessState basic = BessCodec.read(fileWithCore(sgbCore()));
        BessState.Sgb sgb = new BessState.Sgb(pattern(0x2000, 1), pattern(0x800, 2),
                pattern(0x80, 3), pattern(0x20, 4), pattern(0x1000, 5), pattern(0x168, 6),
                pattern(0xfd2, 7), 0x21);
        BessState state = new BessState("SGB producer", null, basic.core(), List.of(), Map.of(), sgb);
        byte[] file = BessCodec.write(state);
        int cursor = le(file).getInt(file.length - 8);
        while (!id(file, cursor).equals("SGB ")) cursor += 8 + le(file).getInt(cursor + 4);
        assertEquals(0x39, le(file).getInt(cursor + 4));
        byte[][] expected = {sgb.borderTiles(), sgb.borderTilemap(), sgb.borderPalettes(),
                sgb.activePalettes(), sgb.ramPalettes(), sgb.attributeMap(), sgb.attributeFiles()};
        for (int i = 0; i < expected.length; i++) {
            int size = le(file).getInt(cursor + 8 + i * 8);
            int offset = le(file).getInt(cursor + 12 + i * 8);
            assertEquals(expected[i].length, size);
            assertTrue(offset + size <= le(file).getInt(file.length - 8));
            assertArrayEquals(expected[i], Arrays.copyOfRange(file, offset, offset + size));
        }
        BessState restored = BessCodec.read(file);
        assertArrayEquals(sgb.borderTiles(), restored.sgb().borderTiles());
        assertArrayEquals(sgb.borderTilemap(), restored.sgb().borderTilemap());
        assertArrayEquals(sgb.borderPalettes(), restored.sgb().borderPalettes());
        assertArrayEquals(sgb.activePalettes(), restored.sgb().activePalettes());
        assertArrayEquals(sgb.ramPalettes(), restored.sgb().ramPalettes());
        assertArrayEquals(sgb.attributeMap(), restored.sgb().attributeMap());
        assertArrayEquals(sgb.attributeFiles(), restored.sgb().attributeFiles());
        assertEquals(0x21, restored.sgb().multiplayerStatus());
        assertArrayEquals(file, BessCodec.write(restored));
    }

    @Test
    public void rejectsSgbBlockOnWrongHardwareBeforeCoreOrWithInvalidData() throws IOException {
        byte[] sgb = new byte[0x39];
        sgb[0x38] = 0x10;
        expectFailure(withBlock("SGB ", sgb), "requires an SGB model");
        Fixture fixture = new Fixture();
        fixture.block("SGB ", sgb);
        fixture.block("CORE", sgbCore());
        fixture.block("END ", new byte[0]);
        expectFailure(fixture.finish(), "follow CORE");

        for (byte[] invalid : List.of(new byte[0x38], new byte[0x39])) {
            fixture = new Fixture();
            fixture.block("CORE", sgbCore());
            fixture.block("SGB ", invalid);
            fixture.block("END ", new byte[0]);
            expectFailure(fixture.finish(), "Invalid BESS SGB");
        }
        le(sgb).putInt(0, 1).putInt(4, -1);
        fixture = new Fixture();
        fixture.block("CORE", sgbCore());
        fixture.block("SGB ", sgb);
        fixture.block("END ", new byte[0]);
        expectFailure(fixture.finish(), "outside the file");
        assertNull(BessCodec.read(fileWithCore(sgbCore())).sgb());
    }

    private static BessState state() {
        return new BessState("Coffee GB test", pattern(18, 10),
                new BessState.Core("CCE ", 0x1234, 0x2340, 0x3456, 0x4567, 0x5678, 0xfffa,
                        true, 0x1f, 2, pattern(128, 1), pattern(0x8000, 2), pattern(0x4000, 3),
                        pattern(0x20000, 4), pattern(0xa0, 5), pattern(0x7f, 6),
                        pattern(0x40, 7), pattern(0x40, 8)),
                List.of(new BessState.MbcWrite(0x2000, 27), new BessState.MbcWrite(0x3000, 1),
                        new BessState.MbcWrite(0x4000, 3), new BessState.MbcWrite(0, 10)),
                Map.of("RTC ", pattern(48, 9), "HUC3", pattern(17, 10), "TPP1", pattern(17, 11),
                        "MBC7", pattern(10, 12), "XOAM", pattern(96, 13)));
    }

    private static byte[] core() {
        ByteBuffer core = ByteBuffer.allocate(0xd0).order(ByteOrder.LITTLE_ENDIAN);
        core.putShort((short) 1).putShort((short) 1).put(ascii("GD  "));
        core.putShort((short) 0x4567).putShort((short) 0x89b0).putShort((short) 0x2345);
        core.putShort((short) 0x6789).putShort((short) 0xabcd).putShort((short) 0xfffe);
        core.put((byte) 1).put((byte) 0x1f).put((byte) 1).put((byte) 0);
        core.put(pattern(128, 3));
        return core.array();
    }

    private static byte[] sgbCore() {
        byte[] core = core();
        System.arraycopy(ascii("SN  "), 0, core, 4, 4);
        return core;
    }

    private static byte[] fileWithCore(byte[] core) {
        Fixture fixture = new Fixture();
        fixture.block("CORE", core);
        fixture.block("END ", new byte[0]);
        return fixture.finish();
    }

    private static byte[] withBlock(String id, byte[] data) {
        Fixture fixture = new Fixture();
        fixture.block("CORE", core());
        fixture.block(id, data);
        fixture.block("END ", new byte[0]);
        return fixture.finish();
    }

    private static void expectFailure(byte[] file, String message) {
        IOException failure = assertThrows(IOException.class, () -> BessCodec.read(file));
        assertTrue(failure.getMessage(), failure.getMessage().contains(message));
    }

    private static byte[] pattern(int size, int seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 13 + seed);
        }
        return data;
    }

    private static ByteBuffer le(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static String id(byte[] file, int offset) {
        return new String(file, offset, 4, StandardCharsets.US_ASCII);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static final class Fixture {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int firstBlock;

        void raw(byte[] bytes) {
            output.writeBytes(bytes);
        }

        int size() {
            return output.size();
        }

        void startBlocks() {
            firstBlock = size();
        }

        void block(String id, byte[] data) {
            raw(ascii(id));
            raw(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array());
            raw(data);
        }

        byte[] finish() {
            raw(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(firstBlock).array());
            raw(ascii("BESS"));
            return output.toByteArray();
        }
    }
}
