package eu.rekawek.coffeegb.core.sgb;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CommandsValidationTest {

    @Test
    public void practicalCommandBoundariesAreAcceptedOnlyAfterCompleteValidation() {
        List<int[]> packets = new ArrayList<>();
        for (int code = 0; code <= 3; code++) {
            packets.add(command(code, 1, new int[15]));
        }
        packets.add(attrBlk(18));
        packets.add(attrLin(110));
        packets.add(attrDiv(true, 17));
        packets.add(attrChr(19, 17, 360, 1));
        packets.add(palSet(511, 511, 511, 511, 0xc0 | 44));
        packets.add(command(0x0b, 1));
        packets.add(command(0x11, 1, 3));
        packets.add(command(0x13, 1, 3));
        packets.add(command(0x14, 1));
        packets.add(command(0x15, 1));
        packets.add(command(0x16, 1, 0x40 | 44));
        packets.add(command(0x17, 1, 3));
        packets.add(command(0x19, 1, 1));

        for (int[] packet : packets) {
            Commands.ParseResult result = Commands.parse(packet);
            assertEquals(result.reason(), Commands.Disposition.PRACTICAL, result.disposition());
            assertNotNull(result.command());
        }
    }

    @Test
    public void unsupportedAndUnknownCommandsHaveSafeExplicitDisposition() {
        int[][] unsupported = {
                command(0x08, 1, 0x80, 0x80, 0, 0),
                command(0x09, 1),
                command(0x0c, 1, 1),
                command(0x0d, 1, 1),
                command(0x0e, 1, 7),
                command(0x0f, 1, 0, 0x18, 0, 1, 0xaa),
                command(0x10, 1, 0, 0x18, 0),
                command(0x12, 1, 0, 0x18, 0, 0, 0, 0),
                command(0x18, 1, 0xff, 0xff),
        };
        for (int[] packet : unsupported) {
            Commands.ParseResult result = Commands.parse(packet);
            assertEquals(result.reason(), Commands.Disposition.UNSUPPORTED, result.disposition());
            assertNotNull(result.command());
        }

        Commands.ParseResult unknown = Commands.parse(command(0x1f, 7, new int[111]));
        assertEquals(Commands.Disposition.UNKNOWN, unknown.disposition());
        assertNull(unknown.command());
    }

    @Test
    public void malformedFieldsAreRejectedBeforeCommandConstruction() {
        List<InvalidCase> cases = new ArrayList<>();
        cases.add(new InvalidCase("absent", null));
        cases.add(new InvalidCase("short physical packet", new int[15]));
        cases.add(new InvalidCase("zero count", raw(0x00, 1)));
        cases.add(new InvalidCase("declared length mismatch", commandBytes(0x00, 1, 32)));
        cases.add(new InvalidCase("negative byte", mutate(command(0x00, 1), 1, -1)));
        cases.add(new InvalidCase("wide byte", mutate(command(0x00, 1), 1, 256)));
        cases.add(new InvalidCase("fixed count", command(0x00, 2, new int[31])));

        cases.add(new InvalidCase("ATTR_BLK zero sets", command(0x04, 1, 0)));
        cases.add(new InvalidCase("ATTR_BLK too many sets", command(0x04, 7, 19)));
        cases.add(new InvalidCase("ATTR_BLK truncated sets", command(0x04, 1, 3)));
        cases.add(new InvalidCase("ATTR_BLK control bits", mutate(attrBlk(1), 2, 8)));
        cases.add(new InvalidCase("ATTR_BLK palette bits", mutate(attrBlk(1), 3, 0x40)));
        cases.add(new InvalidCase("ATTR_BLK x bound", mutate(attrBlk(1), 4, 20)));
        cases.add(new InvalidCase("ATTR_BLK y bound", mutate(attrBlk(1), 5, 18)));
        cases.add(new InvalidCase("ATTR_BLK coordinate order",
                mutate(mutate(attrBlk(1), 4, 19), 6, 0)));
        cases.add(new InvalidCase("ATTR_BLK padding", mutate(attrBlk(1), 15, 1)));

        cases.add(new InvalidCase("ATTR_LIN zero sets", command(0x05, 1, 0)));
        cases.add(new InvalidCase("ATTR_LIN too many sets", command(0x05, 7, 111)));
        cases.add(new InvalidCase("ATTR_LIN truncated sets", command(0x05, 1, 15)));
        cases.add(new InvalidCase("ATTR_LIN vertical bound", command(0x05, 1, 1, 20)));
        cases.add(new InvalidCase("ATTR_LIN horizontal bound", command(0x05, 1, 1, 0x80 | 18)));
        cases.add(new InvalidCase("ATTR_LIN padding", command(0x05, 1, 1, 0, 1)));

        cases.add(new InvalidCase("ATTR_DIV reserved bit", mutate(attrDiv(false, 0), 1, 0x80)));
        cases.add(new InvalidCase("ATTR_DIV vertical bound", attrDiv(false, 20)));
        cases.add(new InvalidCase("ATTR_DIV horizontal bound", attrDiv(true, 18)));
        cases.add(new InvalidCase("ATTR_DIV padding", mutate(attrDiv(false, 0), 3, 1)));

        cases.add(new InvalidCase("ATTR_CHR x bound", attrChr(20, 0, 1, 0)));
        cases.add(new InvalidCase("ATTR_CHR y bound", attrChr(0, 18, 1, 0)));
        cases.add(new InvalidCase("ATTR_CHR zero sets", attrChr(0, 0, 0, 0)));
        cases.add(new InvalidCase("ATTR_CHR too many sets", attrChr(0, 0, 361, 0)));
        cases.add(new InvalidCase("ATTR_CHR style", attrChr(0, 0, 1, 2)));
        cases.add(new InvalidCase("ATTR_CHR unused packed bits",
                mutate(attrChr(0, 0, 1, 0), 6, 1)));
        cases.add(new InvalidCase("ATTR_CHR padding", mutate(attrChr(0, 0, 1, 0), 7, 1)));

        cases.add(new InvalidCase("PAL_SET palette ID", palSet(512, 0, 0, 0, 0)));
        cases.add(new InvalidCase("PAL_SET ATF", palSet(0, 0, 0, 0, 45)));
        cases.add(new InvalidCase("MLT_REQ enum", command(0x11, 1, 4)));
        cases.add(new InvalidCase("MLT_REQ padding", command(0x11, 1, 1, 1)));
        cases.add(new InvalidCase("CHR_TRN reserved bits", command(0x13, 1, 4)));
        cases.add(new InvalidCase("PCT_TRN reserved byte", command(0x14, 1, 1)));
        cases.add(new InvalidCase("ATTR_TRN reserved byte", command(0x15, 1, 1)));
        cases.add(new InvalidCase("ATTR_SET reserved bit", command(0x16, 1, 0x80)));
        cases.add(new InvalidCase("ATTR_SET file", command(0x16, 1, 45)));
        cases.add(new InvalidCase("MASK_EN enum", command(0x17, 1, 4)));
        cases.add(new InvalidCase("PAL_PRI enum", command(0x19, 1, 2)));

        for (InvalidCase invalid : cases) {
            Commands.ParseResult result = Commands.parse(invalid.packet());
            assertEquals(invalid.name() + ": " + result.reason(),
                    Commands.Disposition.INVALID, result.disposition());
            assertNull(invalid.name(), result.command());
            assertNull(invalid.name(), Commands.toCommand(invalid.packet()));
            assertTrue(invalid.name(), result.reason() != null && !result.reason().isBlank());
        }
    }

    @Test
    public void ignoredBytesAreNotMistakenForDocumentedReservedBytes() {
        assertEquals(Commands.Disposition.PRACTICAL,
                Commands.parse(mutate(command(0x00, 1), 15, 0xa5)).disposition());
        assertEquals(Commands.Disposition.PRACTICAL,
                Commands.parse(mutate(command(0x0a, 1), 15, 0xa5)).disposition());
        assertEquals(Commands.Disposition.PRACTICAL,
                Commands.parse(mutate(command(0x0b, 1), 15, 0xa5)).disposition());
        assertEquals(Commands.Disposition.PRACTICAL,
                Commands.parse(mutate(command(0x19, 1, 1), 15, 0xa5)).disposition());
    }

    @Test
    public void everyTransferPayloadIsExactlyFourKiBOfBytesAndDetached() {
        Commands.ChrTrnCmd command = (Commands.ChrTrnCmd) Commands.toCommand(command(0x13, 1));
        int[] payload = new int[Commands.TRANSFER_SIZE];
        assertNotNull(Commands.validateTransferData(null, payload));
        payload[0] = 0x5a;
        command.setDataTransfer(payload);
        payload[0] = 0xa5;
        assertEquals(0x5a, command.dataTransfer[0]);

        assertThrows(IllegalArgumentException.class,
                () -> command.setDataTransfer(new int[Commands.TRANSFER_SIZE - 1]));
        assertThrows(IllegalArgumentException.class,
                () -> command.setDataTransfer(new int[Commands.TRANSFER_SIZE + 1]));
        int[] negative = new int[Commands.TRANSFER_SIZE];
        negative[0] = -1;
        assertThrows(IllegalArgumentException.class, () -> command.setDataTransfer(negative));
        int[] wide = new int[Commands.TRANSFER_SIZE];
        wide[0] = 256;
        assertThrows(IllegalArgumentException.class, () -> command.setDataTransfer(wide));

        Commands.PctTrnCmd picture =
                (Commands.PctTrnCmd) Commands.toCommand(command(0x14, 1));
        int[] validPicture = new int[Commands.TRANSFER_SIZE];
        // The complete three-bit palette range and transparent 0x2ff tiles are established Coffee
        // GB compatibility behavior even though Pan Docs recommends border palettes 4..6.
        setLittleEndian16(validPicture, 0, 0);
        setLittleEndian16(validPicture, 2, 7 << 10 | 0xff);
        setLittleEndian16(validPicture, 4, 0x2ff);
        assertNull(Commands.validateTransferCommitData(picture, validPicture));
        setLittleEndian16(validPicture, 0, 4 << 10 | 1 << 13);
        assertNotNull(Commands.validateTransferCommitData(picture, validPicture));
    }

    private static void setLittleEndian16(int[] data, int offset, int value) {
        data[offset] = value & 0xff;
        data[offset + 1] = value >>> 8 & 0xff;
    }

    private static int[] attrBlk(int count) {
        int[] payload = new int[1 + count * 6];
        payload[0] = count;
        for (int i = 0; i < count; i++) {
            int offset = 1 + i * 6;
            payload[offset] = 7;
            payload[offset + 1] = 0x39;
            payload[offset + 2] = 0;
            payload[offset + 3] = 0;
            payload[offset + 4] = 19;
            payload[offset + 5] = 17;
        }
        return command(0x04, packetsForPayload(payload.length), payload);
    }

    private static int[] attrLin(int count) {
        int[] payload = new int[1 + count];
        payload[0] = count;
        for (int i = 0; i < count; i++) {
            boolean horizontal = (i & 1) != 0;
            payload[i + 1] = (horizontal ? 0x80 : 0) | (i & 3) << 5
                    | i % (horizontal ? 18 : 20);
        }
        return command(0x05, packetsForPayload(payload.length), payload);
    }

    private static int[] attrDiv(boolean horizontal, int coordinate) {
        return command(0x06, 1, (horizontal ? 0x40 : 0) | 3 | 1 << 2 | 2 << 4,
                coordinate);
    }

    private static int[] attrChr(int x, int y, int count, int style) {
        int dataBytes = inRange(count, 1, 360) ? (count + 3) / 4 : 1;
        int[] payload = new int[5 + dataBytes];
        payload[0] = x;
        payload[1] = y;
        payload[2] = count & 0xff;
        payload[3] = count >>> 8 & 0xff;
        payload[4] = style;
        for (int i = 0; i < dataBytes; i++) {
            payload[5 + i] = 0x1b;
        }
        if ((count & 3) != 0 && count > 0) {
            payload[payload.length - 1] &= 0xff << (8 - (count & 3) * 2);
        }
        return command(0x07, Math.min(6, packetsForPayload(payload.length)), payload);
    }

    private static int[] palSet(int first, int second, int third, int fourth, int flags) {
        int[] payload = new int[9];
        int[] ids = {first, second, third, fourth};
        for (int i = 0; i < ids.length; i++) {
            payload[i * 2] = ids[i] & 0xff;
            payload[i * 2 + 1] = ids[i] >>> 8 & 0xff;
        }
        payload[8] = flags;
        return command(0x0a, 1, payload);
    }

    private static int packetsForPayload(int payloadLength) {
        return (payloadLength + 1 + 15) / 16;
    }

    private static boolean inRange(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }

    private static int[] raw(int header, int value) {
        int[] packet = new int[16];
        packet[0] = header;
        packet[1] = value;
        return packet;
    }

    private static int[] commandBytes(int code, int declaredPackets, int actualBytes) {
        int[] bytes = new int[actualBytes];
        bytes[0] = code << 3 | declaredPackets;
        return bytes;
    }

    private static int[] command(int code, int packetCount, int... payload) {
        int[] packet = new int[packetCount * Commands.PACKET_SIZE];
        packet[0] = code << 3 | packetCount;
        if (payload.length > packet.length - 1) {
            throw new IllegalArgumentException("test payload exceeds command capacity");
        }
        System.arraycopy(payload, 0, packet, 1, payload.length);
        return packet;
    }

    private static int[] mutate(int[] input, int offset, int value) {
        int[] copy = input.clone();
        copy[offset] = value;
        return copy;
    }

    private record InvalidCase(String name, int[] packet) {
    }
}
