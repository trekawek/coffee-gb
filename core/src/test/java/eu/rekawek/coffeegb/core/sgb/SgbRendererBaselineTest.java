package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.sgb.Commands.MaskEnCmd.GameboyScreenMask;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SgbRendererBaselineTest {

    private static final String BASELINES = "/sgb-baselines/renderer-hashes.properties";

    @Test
    public void syntheticRendererFixtureHasStableCanonicalHashesAndOwnedState() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.seedRenderer();
            int[] dmgPixels = fixture.dmgPixels();

            int[] cancel = fixture.render(GameboyScreenMask.CANCEL, dmgPixels);
            assertEquals(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT, dmgPixels.length);
            assertEquals(SuperGameboy.SGB_DISPLAY_WIDTH * SuperGameboy.SGB_DISPLAY_HEIGHT,
                    cancel.length);

            int framesBeforeFreeze = fixture.frameCount();
            fixture.mask(GameboyScreenMask.FREEZE);
            fixture.postDmgFrame(invertLowBits(dmgPixels));
            assertEquals(framesBeforeFreeze, fixture.frameCount());

            int[] black = fixture.render(GameboyScreenMask.BLANK_BLACK, dmgPixels);
            int[] color0 = fixture.render(GameboyScreenMask.BLANK_COLOR0, dmgPixels);
            assertNotEquals(sha256(cancel), sha256(black));
            assertNotEquals(sha256(black), sha256(color0));

            fixture.mask(GameboyScreenMask.BLANK_COLOR0);
            var backgroundState = fixture.background.captureState();
            var displayState = fixture.display.captureState();
            int[] originalDmg = dmgPixels.clone();

            fixture.advanceBorderFrames(1);
            int[] expectedContinuation = fixture.renderCurrentMask(originalDmg);

            // Diverge the live timeline and every fixture-owned source array. The detached
            // component states must own enough data to reproduce the original continuation.
            Arrays.fill(fixture.lowTiles, 0);
            Arrays.fill(fixture.highTiles, 0);
            Arrays.fill(fixture.pictureData, 0);
            Arrays.fill(fixture.paletteData, 0);
            Arrays.fill(fixture.attributeData, 0);
            Arrays.fill(dmgPixels, 3);
            fixture.replaceLiveBorder();
            fixture.render(GameboyScreenMask.BLANK_BLACK, dmgPixels);

            fixture.background.restoreState(backgroundState);
            fixture.display.restoreState(displayState);
            fixture.advanceBorderFrames(1);
            int[] actualContinuation = fixture.renderCurrentMask(originalDmg);
            assertArrayEquals(expectedContinuation, actualContinuation);

            // The producer may reuse callback-scoped buffers, but its private canonical base
            // remains isolated: mutating a delivered event cannot leak into the next frame.
            int[] exposed = fixture.lastRawFrame.get();
            exposed[0] ^= 0x00ffffff;
            fixture.background.restoreState(backgroundState);
            fixture.display.restoreState(displayState);
            fixture.advanceBorderFrames(1);
            int[] repeated = fixture.renderCurrentMask(originalDmg);
            assertArrayEquals(actualContinuation, repeated);

            Map<String, String> actual = new LinkedHashMap<>();
            actual.put("dmg-input", sha256(fixture.originalDmg));
            actual.put("tile-input", sha256(fixture.originalLowTiles, fixture.originalHighTiles));
            actual.put("picture-input", sha256(fixture.originalPicture));
            actual.put("palette-input", sha256(fixture.originalPalette));
            actual.put("attribute-input", sha256(fixture.originalAttributes));
            actual.put("frame-cancel", sha256(cancel));
            actual.put("frame-black", sha256(black));
            actual.put("frame-color0", sha256(color0));
            actual.put("frame-continuation", sha256(actualContinuation));
            assertEquals("Update the reviewed baseline only when current behavior intentionally changes",
                    expectedHashes(), actual);
        }
    }

    private static Map<String, String> expectedHashes() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = SgbRendererBaselineTest.class.getResourceAsStream(BASELINES)) {
            if (stream == null) {
                throw new IOException("Missing " + BASELINES);
            }
            properties.load(stream);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : new String[]{"dmg-input", "tile-input", "picture-input",
                "palette-input", "attribute-input", "frame-cancel", "frame-black",
                "frame-color0", "frame-continuation"}) {
            String value = properties.getProperty(key);
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid baseline hash for " + key);
            }
            result.put(key, value);
        }
        return result;
    }

    /** SHA-256 over array-count, then for each array its length and signed int32 values, big-endian. */
    static String sha256(int[]... arrays) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateInt(digest, arrays.length);
            for (int[] array : arrays) {
                updateInt(digest, array.length);
                for (int value : array) {
                    updateInt(digest, value);
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static int[] invertLowBits(int[] input) {
        return Arrays.stream(input).map(value -> value ^ 3).toArray();
    }

    private static final class Fixture implements AutoCloseable {

        private final EventBusImpl sgbBus = new EventBusImpl(null, null, false);

        private final EventBusImpl eventBus = new EventBusImpl(null, null, false);

        private final Background background = new Background(sgbBus);

        private final SgbDisplay display;

        private final AtomicReference<int[]> lastRawFrame = new AtomicReference<>();

        private final AtomicReference<int[]> lastOwnedFrame = new AtomicReference<>();

        private final AtomicInteger frames = new AtomicInteger();

        private final int[] lowTiles = new int[0x1000];

        private final int[] highTiles = new int[0x1000];

        private final int[] pictureData = new int[0x1000];

        private final int[] paletteData = new int[0x1000];

        private final int[] attributeData = new int[0x1000];

        private int[] originalLowTiles;

        private int[] originalHighTiles;

        private int[] originalPicture;

        private int[] originalPalette;

        private int[] originalAttributes;

        private int[] originalDmg;

        private Fixture() throws IOException {
            display = new SgbDisplay(syntheticRom(), sgbBus, true, true);
            background.init(eventBus);
            display.init(eventBus);
            eventBus.register(event -> {
                lastRawFrame.set(event.buffer());
                lastOwnedFrame.set(event.buffer().clone());
                frames.incrementAndGet();
            }, SgbDisplay.SgbFrameReadyEvent.class);
        }

        private void seedRenderer() {
            fillTileData(lowTiles, 0x13);
            fillTileData(highTiles, 0x97);
            postCharacterTransfer(0, lowTiles);
            postCharacterTransfer(1, highTiles);

            for (int y = 0; y < 28; y++) {
                for (int x = 0; x < 32; x++) {
                    int character = (x * 11 + y * 29) & 0xff;
                    int palette = 4 + ((x + 2 * y) & 3);
                    int value = character | palette << 10;
                    if ((x & 1) != 0) value |= 1 << 14;
                    if ((y & 1) != 0) value |= 1 << 15;
                    setLittleEndian16(pictureData, 2 * (x + y * 32), value);
                }
            }
            for (int palette = 4; palette < 8; palette++) {
                for (int color = 0; color < 16; color++) {
                    int rgb555 = ((palette * 5 + color * 3) & 0x1f)
                            | ((palette * 9 + color * 7) & 0x1f) << 5
                            | ((palette * 13 + color * 11) & 0x1f) << 10;
                    int offset = 0x800 + (palette - 4) * 32 + color * 2;
                    setLittleEndian16(pictureData, offset, rgb555);
                }
            }
            Commands.PctTrnCmd picture = (Commands.PctTrnCmd) command(0x14);
            picture.setDataTransfer(pictureData);
            sgbBus.post(picture);
            advanceBorderFrames(80); // rendered border plus a non-zero deterministic fade

            for (int palette = 0; palette < 512; palette++) {
                for (int color = 0; color < 4; color++) {
                    int rgb555 = ((palette + color * 3) & 0x1f)
                            | ((palette * 3 + color * 5) & 0x1f) << 5
                            | ((palette * 7 + color * 11) & 0x1f) << 10;
                    setLittleEndian16(paletteData, palette * 8 + color * 2, rgb555);
                }
            }
            Commands.PalTrnCmd palettes = (Commands.PalTrnCmd) command(0x0b);
            palettes.setDataTransfer(paletteData);
            sgbBus.post(palettes);

            int[] palSet = commandPacket(0x0a);
            setLittleEndian16(palSet, 1, 17);
            setLittleEndian16(palSet, 3, 271);
            setLittleEndian16(palSet, 5, 511);
            setLittleEndian16(palSet, 7, 33);
            sgbBus.post(Commands.toCommand(palSet));

            for (int file = 0; file < 45; file++) {
                for (int packed = 0; packed < 90; packed++) {
                    int base = (file + packed) & 3;
                    attributeData[file * 90 + packed] =
                            base << 6 | (base + 1 & 3) << 4 | (base + 2 & 3) << 2 | (base + 3 & 3);
                }
            }
            Commands.AttrTrnCmd attributes = (Commands.AttrTrnCmd) command(0x15);
            attributes.setDataTransfer(attributeData);
            sgbBus.post(attributes);
            int[] attrSet = commandPacket(0x16);
            attrSet[1] = 37;
            sgbBus.post(Commands.toCommand(attrSet));

            originalLowTiles = lowTiles.clone();
            originalHighTiles = highTiles.clone();
            originalPicture = pictureData.clone();
            originalPalette = paletteData.clone();
            originalAttributes = attributeData.clone();
            originalDmg = dmgPixels();
        }

        private int[] dmgPixels() {
            int[] pixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            for (int y = 0; y < Display.DISPLAY_HEIGHT; y++) {
                for (int x = 0; x < Display.DISPLAY_WIDTH; x++) {
                    pixels[x + y * Display.DISPLAY_WIDTH] = (x * 3 + y * 5 + (x >>> 3)) & 3;
                }
            }
            return pixels;
        }

        private int[] render(GameboyScreenMask mask, int[] pixels) {
            mask(mask);
            return renderCurrentMask(pixels);
        }

        private int[] renderCurrentMask(int[] pixels) {
            int count = frameCount();
            postDmgFrame(pixels);
            assertTrue("Mask should emit a frame", frameCount() > count);
            return lastOwnedFrame.get();
        }

        private void mask(GameboyScreenMask mask) {
            int[] packet = commandPacket(0x17);
            packet[1] = mask.ordinal();
            sgbBus.post(Commands.toCommand(packet));
        }

        private void postDmgFrame(int[] pixels) {
            eventBus.post(new Display.DmgFrameReadyEvent(pixels));
        }

        private void advanceBorderFrames(int count) {
            int[] pixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            for (int i = 0; i < count; i++) {
                eventBus.post(new Display.DmgFrameReadyEvent(pixels));
            }
        }

        private int frameCount() {
            return frames.get();
        }

        private void replaceLiveBorder() {
            int[] buffer = new int[SuperGameboy.SGB_DISPLAY_WIDTH * SuperGameboy.SGB_DISPLAY_HEIGHT];
            int[] mask = new int[buffer.length];
            Arrays.fill(buffer, 0x7fff);
            Arrays.fill(mask, 1);
            eventBus.post(new Background.SgbBackgroundReadyEvent(buffer, mask));
            eventBus.post(new Background.SgbBackgroundFadeEvent(31));
        }

        private void postCharacterTransfer(int half, int[] data) {
            Commands.ChrTrnCmd characters = (Commands.ChrTrnCmd) command(0x13);
            characters.packet[1] = half;
            characters.setDataTransfer(data);
            sgbBus.post(characters);
        }

        @Override
        public void close() {
            eventBus.close();
            sgbBus.close();
        }
    }

    private static void fillTileData(int[] target, int seed) {
        for (int i = 0; i < target.length; i++) {
            target[i] = (i * 73 + (i >>> 3) * 19 + seed) & 0xff;
        }
    }

    private static Commands.AbstractCommand command(int code) {
        return Commands.toCommand(commandPacket(code));
    }

    private static int[] commandPacket(int code) {
        int[] packet = new int[16];
        packet[0] = code << 3 | 1;
        return packet;
    }

    private static void setLittleEndian16(int[] target, int offset, int value) {
        target[offset] = value & 0xff;
        target[offset + 1] = value >>> 8 & 0xff;
    }

    private static Rom syntheticRom() throws IOException {
        byte[] bytes = new byte[0x8000];
        byte[] title = "SYNTHETIC-SGB".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, bytes, 0x134, title.length);
        bytes[0x146] = 3;
        bytes[0x147] = 0;
        int checksum = 0;
        for (int address = 0x134; address <= 0x14c; address++) {
            checksum = (checksum - (bytes[address] & 0xff) - 1) & 0xff;
        }
        bytes[0x14d] = (byte) checksum;
        return new Rom(bytes);
    }
}
