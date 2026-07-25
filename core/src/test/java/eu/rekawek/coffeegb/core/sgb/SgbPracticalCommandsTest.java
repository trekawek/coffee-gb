package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent.translateGbcRgb;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SgbPracticalCommandsTest {

    @Test
    public void attrLinAppliesHorizontalVerticalBoundariesAndLastEntryWins() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setDirectPalettes();
            fixture.send(0x05, 1,
                    6,
                    horizontal(0, 1), horizontal(17, 2),
                    vertical(0, 3), vertical(19, 1),
                    horizontal(5, 1), vertical(5, 2));

            fixture.render(1);
            assertEquals(color(1), fixture.tile(10, 0));
            assertEquals(color(2), fixture.tile(10, 17));
            assertEquals(color(3), fixture.tile(0, 10));
            assertEquals(color(1), fixture.tile(19, 10));
            assertEquals(color(1), fixture.tile(4, 5));
            assertEquals("the later vertical entry wins at the overlap", color(2), fixture.tile(5, 5));
            assertEquals(color(2), fixture.tile(5, 4));

            int[] maximum = new int[111];
            maximum[0] = 110;
            for (int i = 0; i < 110; i++) {
                maximum[i + 1] = vertical(0, i & 3);
            }
            fixture.send(0x05, 7, maximum);
            fixture.render(1);
            assertEquals("all 110 entries are collected and the last one wins",
                    color(1), fixture.tile(0, 9));
        }
    }

    @Test
    public void attrDivAppliesBothOrientationsAllRegionsAndBoundaries() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setDirectPalettes();

            fixture.send(0x06, 1, divider(false, 1, 2, 3), 10);
            fixture.render(1);
            assertEquals(color(1), fixture.tile(9, 8));
            assertEquals(color(2), fixture.tile(10, 8));
            assertEquals(color(3), fixture.tile(11, 8));

            fixture.send(0x06, 1, divider(true, 3, 1, 2), 9);
            fixture.render(1);
            assertEquals(color(3), fixture.tile(8, 8));
            assertEquals(color(1), fixture.tile(8, 9));
            assertEquals(color(2), fixture.tile(8, 10));

            fixture.send(0x06, 1, divider(false, 1, 2, 3), 0);
            fixture.render(1);
            assertEquals(color(2), fixture.tile(0, 4));
            assertEquals(color(3), fixture.tile(1, 4));

            fixture.send(0x06, 1, divider(false, 1, 2, 3), 19);
            fixture.render(1);
            assertEquals(color(1), fixture.tile(18, 4));
            assertEquals(color(2), fixture.tile(19, 4));

            fixture.send(0x06, 1, divider(true, 1, 2, 3), 17);
            fixture.render(1);
            assertEquals(color(1), fixture.tile(4, 16));
            assertEquals(color(2), fixture.tile(4, 17));
        }
    }

    @Test
    public void attrDivAtomicallyOverwritesEarlierAttributeCommands() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setDirectPalettes();
            fixture.send(0x05, 1, 2, horizontal(4, 3), vertical(7, 3));
            fixture.send(0x06, 1, divider(false, 1, 2, 0), 7);
            fixture.render(1);

            assertEquals(color(1), fixture.tile(6, 4));
            assertEquals(color(2), fixture.tile(7, 4));
            assertEquals(color(0), fixture.tile(8, 4));
        }
    }

    @Test
    public void attrBlkAppliesBoundaryRectangleAutomaticLineAndLaterOverlap() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setDirectPalettes();
            fixture.send(0x04, 1,
                    2,
                    1, 1, 0, 0, 19, 17,       // inside only; boundary also becomes palette 1
                    4, 3 << 4, 5, 5, 10, 10); // outside only; boundary also becomes palette 3
            fixture.render(1);

            assertEquals(color(3), fixture.tile(0, 0));
            assertEquals(color(3), fixture.tile(5, 5));
            assertEquals(color(1), fixture.tile(6, 6));
            assertEquals(color(3), fixture.tile(19, 17));
        }
    }

    @Test
    public void attrChrSupportsBothWritingStylesAndBoundaryWrap() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setDirectPalettes();
            fixture.send(0x07, 1, 19, 16, 3, 0, 0, 0x6c); // palettes 1,2,3
            fixture.render(1);
            assertEquals(color(1), fixture.tile(19, 16));
            assertEquals(color(2), fixture.tile(0, 17));
            assertEquals(color(3), fixture.tile(1, 17));

            fixture.send(0x07, 1, 18, 17, 3, 0, 1, 0x6c);
            fixture.render(1);
            assertEquals(color(1), fixture.tile(18, 17));
            assertEquals(color(2), fixture.tile(19, 0));
            assertEquals(color(3), fixture.tile(19, 1));
        }
    }

    @Test
    public void palSetAcceptsMaximumIdsAppliesMaximumAtfAndCancelsMask() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int[] palettes = new int[Commands.TRANSFER_SIZE];
            for (int id = 508; id <= 511; id++) {
                setLittleEndian16(palettes, id * 8 + 2, (id - 507) * 0x0111);
            }
            Commands.PalTrnCmd palTrn =
                    (Commands.PalTrnCmd) Commands.toCommand(Fixture.packet(0x0b));
            palTrn.setDataTransfer(palettes);
            fixture.packetFixture.sgbBus().post(palTrn);

            int[] attributes = new int[Commands.TRANSFER_SIZE];
            Arrays.fill(attributes, 44 * 90, 45 * 90, 0xff); // ATF 44 => palette 3
            Commands.AttrTrnCmd attrTrn =
                    (Commands.AttrTrnCmd) Commands.toCommand(Fixture.packet(0x15));
            attrTrn.setDataTransfer(attributes);
            fixture.packetFixture.sgbBus().post(attrTrn);

            fixture.send(0x17, 1, 1); // freeze until PAL_SET's cancel bit commits
            fixture.send(0x19, 1, 1); // priority survives the indirect palette update
            fixture.send(0x0a, 1,
                    0xfc, 1, 0xfd, 1, 0xfe, 1, 0xff, 1,
                    0x80 | 0x40 | 44);
            fixture.render(1);
            assertEquals(translateGbcRgb(0x0444), fixture.tile(0, 0));
            assertEquals(translateGbcRgb(0x0444), fixture.tile(19, 17));
            assertTrue(fixture.display.isPalettePriorityEnabled());
        }
    }

    @Test
    public void palPriPersistsWhilePaletteAttributeAndMaskCommandsRemainDeterministic()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setDirectPalettes();
            assertFalse(fixture.display.isPalettePriorityEnabled());

            fixture.send(0x19, 1, 1);
            assertTrue(fixture.display.isPalettePriorityEnabled());
            fixture.send(0x05, 1, 1, vertical(3, 2));
            fixture.send(0x17, 1, 3);
            ComponentState<SgbDisplay> state = fixture.display.captureState();

            // Coffee GB has no SNES firmware palette-selection UI to override the game palette.
            // PAL_PRI is retained, while subsequent game commands still update active palettes.
            fixture.directPalette(2, 0x0555);
            fixture.send(0x17, 1, 0);
            fixture.render(1);
            assertEquals(translateGbcRgb(0x0555), fixture.tile(3, 7));
            assertTrue(fixture.display.isPalettePriorityEnabled());

            fixture.send(0x19, 1, 0);
            fixture.send(0x06, 1, divider(false, 0, 0, 0), 0);
            assertFalse(fixture.display.isPalettePriorityEnabled());

            fixture.display.restoreState(state);
            assertTrue(fixture.display.isPalettePriorityEnabled());
            fixture.send(0x17, 1, 0);
            fixture.render(1);
            assertEquals(color(2), fixture.tile(3, 7));
        }
    }

    @Test
    public void componentStateRestoresAttributeAndPriorityContinuationWithoutAliasing()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setDirectPalettes();
            fixture.send(0x05, 1, 2, horizontal(8, 1), vertical(9, 2));
            fixture.send(0x06, 1, divider(true, 1, 2, 3), 6);
            fixture.send(0x19, 1, 1);
            ComponentState<SgbDisplay> state = fixture.display.captureState();

            fixture.send(0x05, 1, 1, vertical(10, 3));
            fixture.render(1);
            int[] expected = fixture.frame();

            fixture.send(0x06, 1, divider(true, 0, 0, 0), 0);
            fixture.send(0x19, 1, 0);
            fixture.directPalette(3, 0x0666);
            fixture.render(3);

            fixture.display.restoreState(state);
            fixture.send(0x05, 1, 1, vertical(10, 3));
            fixture.render(1);
            assertArrayEquals(expected, fixture.frame());
            assertTrue(fixture.display.isPalettePriorityEnabled());
        }
    }

    @Test
    public void malformedPracticalCommandsCannotPartiallyMutateDisplayOrPoisonNextCommand()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setDirectPalettes();
            fixture.send(0x05, 1, 1, horizontal(6, 2));
            fixture.render(1);
            int[] baseline = fixture.frame();
            int delivered = fixture.packetFixture.commands().size();

            List<List<int[]>> malformed = new ArrayList<>();
            malformed.add(SgbPacketTestBuilder.command(0x04, 1, 1, 8, 0, 0, 0, 19, 17));
            malformed.add(SgbPacketTestBuilder.command(0x05, 1, 1, vertical(20, 1)));
            malformed.add(SgbPacketTestBuilder.command(0x06, 1, 0x80, 0));
            malformed.add(SgbPacketTestBuilder.command(0x07, 1, 20, 0, 1, 0, 0, 0));
            malformed.add(SgbPacketTestBuilder.command(0x0a, 1, 0, 2)); // palette ID 512
            malformed.add(SgbPacketTestBuilder.command(0x16, 1, 45));
            malformed.add(SgbPacketTestBuilder.command(0x17, 1, 4));
            malformed.add(SgbPacketTestBuilder.command(0x19, 1, 2));
            for (List<int[]> packets : malformed) {
                packets.forEach(fixture.packetFixture::sendPacket);
                fixture.render(1);
                assertArrayEquals(baseline, fixture.frame());
                assertEquals(delivered, fixture.packetFixture.commands().size());
            }

            fixture.send(0x05, 1, 1, vertical(12, 3));
            fixture.render(1);
            assertEquals(color(3), fixture.tile(12, 4));
            assertEquals(delivered + 1, fixture.packetFixture.commands().size());
        }
    }

    @Test
    public void freshDisplayAndReceiverRestartHaveDocumentedDefaults() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setDirectPalettes();
            assertFalse(fixture.display.isPalettePriorityEnabled());
            fixture.render(1);
            assertEquals(color(0), fixture.tile(12, 12));

            fixture.send(0x05, 1, 1, vertical(12, 3));
            fixture.packetFixture.restartReceiver();
            fixture.packetFixture.restartReceiver();
            fixture.render(1);
            assertEquals("receiver restart aborts framing, not committed display state",
                    color(3), fixture.tile(12, 12));
        }
    }

    private static int horizontal(int line, int palette) {
        return 0x80 | palette << 5 | line;
    }

    private static int vertical(int line, int palette) {
        return palette << 5 | line;
    }

    private static int divider(boolean horizontal, int aboveLeft, int line, int belowRight) {
        return (horizontal ? 0x40 : 0) | belowRight | aboveLeft << 2 | line << 4;
    }

    private static int color(int palette) {
        return translateGbcRgb((palette + 1) * 0x0111);
    }

    private static void setLittleEndian16(int[] data, int offset, int value) {
        data[offset] = value & 0xff;
        data[offset + 1] = value >>> 8;
    }

    private static final class Fixture implements AutoCloseable {

        private final SgbPacketTestBuilder packetFixture = new SgbPacketTestBuilder();

        private final EventBusImpl eventBus = new EventBusImpl(null, null, false);

        private final SgbDisplay display;

        private final AtomicReference<int[]> frame = new AtomicReference<>();

        private Fixture() throws IOException {
            display = new SgbDisplay(testRom(), packetFixture.sgbBus(), true, false);
            display.init(eventBus);
            eventBus.register(event -> frame.set(event.buffer().clone()),
                    SgbDisplay.SgbFrameReadyEvent.class);
        }

        private void send(int code, int count, int... payload) {
            packetFixture.sendCommand(code, count, payload);
        }

        private void setDirectPalettes() {
            int[] pal01 = packet(0x00);
            setColor(pal01, 3, 0x0111);
            setColor(pal01, 9, 0x0222);
            packetFixture.sgbBus().post(Commands.toCommand(pal01));

            int[] pal23 = packet(0x01);
            setColor(pal23, 3, 0x0333);
            setColor(pal23, 9, 0x0444);
            packetFixture.sgbBus().post(Commands.toCommand(pal23));
        }

        private void directPalette(int palette, int color) {
            int[] packet;
            if (palette < 2) {
                packet = packet(0x00);
                setColor(packet, palette == 0 ? 3 : 9, color);
            } else {
                packet = packet(0x01);
                setColor(packet, palette == 2 ? 3 : 9, color);
            }
            packetFixture.sgbBus().post(Commands.toCommand(packet));
        }

        private void render(int dmgColor) {
            int[] pixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            Arrays.fill(pixels, dmgColor);
            eventBus.post(new Display.DmgFrameReadyEvent(pixels));
        }

        private int tile(int x, int y) {
            return frame.get()[x * 8 + y * 8 * Display.DISPLAY_WIDTH];
        }

        private int[] frame() {
            return frame.get().clone();
        }

        @Override
        public void close() {
            eventBus.close();
            packetFixture.close();
        }

        private static int[] packet(int code) {
            int[] packet = new int[16];
            packet[0] = code << 3 | 1;
            return packet;
        }

        private static void setColor(int[] packet, int offset, int color) {
            packet[offset] = color & 0xff;
            packet[offset + 1] = color >>> 8;
        }

        private static Rom testRom() throws IOException {
            byte[] bytes = new byte[0x8000];
            bytes[0x147] = 0;
            return new Rom(bytes);
        }
    }
}
