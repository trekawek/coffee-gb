package eu.rekawek.coffeegb.core.serial;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GameboyPrinterSerialEndpointTest {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    private static class Capture implements GameboyPrinterSerialEndpoint.PrintCallback {
        int[] argb;
        int width;
        int height;
        int topMargin;
        int bottomMargin;
        int exposure;
        int count;

        @Override
        public void print(int[] argb, int width, int height, int topMargin, int bottomMargin,
                           int exposure) {
            this.argb = argb;
            this.width = width;
            this.height = height;
            this.topMargin = topMargin;
            this.bottomMargin = bottomMargin;
            this.exposure = exposure;
            this.count++;
        }
    }

    private final Capture capture = new Capture();
    private final GameboyPrinterSerialEndpoint printer = new GameboyPrinterSerialEndpoint(capture);

    /** Clocks one byte out as the master would and returns the printer's reply byte. */
    private int transfer(int b) {
        printer.setSb(b & 0xff);
        printer.startSending();
        int reply = 0;
        for (int i = 0; i < 8; i++) {
            reply = (reply << 1) | (printer.sendBit() & 1);
        }
        return reply;
    }

    /** Sends a full packet; returns {aliveReply, statusReply} from the two trailing bytes. */
    private int[] sendPacket(int command, int compression, int[] data) {
        transfer(0x88);
        transfer(0x33);
        int checksum = command + compression;
        transfer(command);
        transfer(compression);
        int len = data.length;
        transfer(len & 0xff);
        transfer((len >> 8) & 0xff);
        checksum += (len & 0xff) + ((len >> 8) & 0xff);
        for (int b : data) {
            transfer(b);
            checksum += b;
        }
        transfer(checksum & 0xff);
        transfer((checksum >> 8) & 0xff);
        int alive = transfer(0x00);
        int status = transfer(0x00);
        return new int[] {alive, status};
    }

    private static int[] printCommand(int palette, int margins) {
        return new int[] {0x01, margins, palette, 0x40};
    }

    @Test
    public void validPacketRepliesDeviceAlive() {
        int[] reply = sendPacket(0x0F, 0x00, new int[0]); // status inquiry
        assertEquals(0x81, reply[0]);
    }

    @Test
    public void badChecksumWithholdsDeviceAlive() {
        // a valid packet answers 0x81; a bad checksum aborts and never sends it
        transfer(0x88);
        transfer(0x33);
        transfer(0x0F);
        transfer(0x00);
        transfer(0x00);
        transfer(0x00);
        transfer(0xFF); // wrong checksum low
        transfer(0xFF); // wrong checksum high
        int alive = transfer(0x00);
        assertTrue("printer must not report alive on a checksum error", alive != 0x81);
    }

    @Test
    public void printDecodesTilesAndAppliesPalette() {
        sendPacket(0x01, 0x00, new int[0]); // INIT

        // one full data band: pixel (0,0) black (both bitplanes set), the rest white
        int[] band = new int[0x280];
        band[0] = 0x80; // tile 0, row 0, low plane, leftmost pixel bit
        band[1] = 0x80; // tile 0, row 0, high plane, leftmost pixel bit
        sendPacket(0x04, 0x00, band);

        assertNull("nothing printed until the print command", capture.argb);

        // print with the standard palette 0xE4 (identity: shade i -> shade i)
        sendPacket(0x02, 0x00, printCommand(0xE4, 0x03));

        assertEquals(1, capture.count);
        assertEquals(160, capture.width);
        assertEquals(16, capture.height); // one band = two tile rows = 16 px
        assertEquals(160 * 16, capture.argb.length);
        assertEquals(BLACK, capture.argb[0]);
        assertEquals(WHITE, capture.argb[1]);
        assertEquals(0, capture.topMargin);
        assertEquals(3, capture.bottomMargin);
        assertEquals(0x40, capture.exposure);
    }

    @Test
    public void invertedPaletteMapsShadeZeroToBlack() {
        sendPacket(0x01, 0x00, new int[0]);
        sendPacket(0x04, 0x00, new int[0x280]); // all shade 0
        sendPacket(0x02, 0x00, printCommand(0x1B, 0x00)); // 0x1B = inverted palette
        assertNotNull(capture.argb);
        assertEquals(BLACK, capture.argb[0]); // shade 0 -> black under inverted palette
    }

    @Test
    public void printingStatusTransitionsToDone() {
        sendPacket(0x01, 0x00, new int[0]);
        sendPacket(0x04, 0x00, new int[0x280]);
        sendPacket(0x02, 0x00, printCommand(0xE4, 0x00));

        // poll status until the printer reports done (bit 2 = 0x04) and clears printing (0x02)
        int status = -1;
        for (int i = 0; i < 100; i++) {
            status = sendPacket(0x0F, 0x00, new int[0])[1];
            if ((status & 0x02) == 0) {
                break;
            }
        }
        assertEquals(0x04, status); // done
    }

    @Test
    public void rleCompressedBandDecodesLikeRawBand() {
        sendPacket(0x01, 0x00, new int[0]);
        // an all-zero band encoded as RLE: literal-run headers cover 0x280 zero bytes.
        // a literal run header 0x7F means "copy the next 0x80 bytes verbatim".
        int[] rle = new int[0x280 / 0x80 * (0x80 + 1)];
        int p = 0;
        for (int run = 0; run < 0x280 / 0x80; run++) {
            rle[p++] = 0x7F; // literal run of 0x80 bytes
            for (int i = 0; i < 0x80; i++) {
                rle[p++] = 0x00;
            }
        }
        sendPacket(0x04, 0x01, rle);
        sendPacket(0x02, 0x00, printCommand(0xE4, 0x00));
        assertNotNull(capture.argb);
        assertEquals(WHITE, capture.argb[0]); // shade 0 under identity palette = white
        assertEquals(160 * 16, capture.argb.length);
    }
}
