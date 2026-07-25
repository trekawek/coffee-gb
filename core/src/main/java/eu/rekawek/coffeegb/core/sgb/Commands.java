package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;

public class Commands {

    public static final int PACKET_SIZE = 16;

    public static final int MAX_PACKETS = 7;

    public static final int TRANSFER_SIZE = 0x1000;

    private Commands() {
    }

    public enum Disposition {
        PRACTICAL,
        UNSUPPORTED,
        UNKNOWN,
        INVALID
    }

    /** A total, side-effect-free decode result for one completely collected command. */
    public record ParseResult(AbstractCommand command, Disposition disposition, String reason) {

        public boolean isDeliverable() {
            return disposition == Disposition.PRACTICAL;
        }
    }

    /**
     * Validates the complete command before constructing an event that can reach a component.
     * Unknown and unsupported commands are classified separately so the transport can consume
     * their framing without exposing them to practical SGB state.
     */
    public static ParseResult parse(int[] packet) {
        String violation = validateEnvelope(packet);
        if (violation != null) {
            return invalid(violation);
        }

        int code = packet[0] >>> 3;
        if (code >= 0x1a) {
            return new ParseResult(null, Disposition.UNKNOWN,
                    "reserved or unknown command ID 0x" + Integer.toHexString(code));
        }

        violation = validateCommand(code, packet);
        if (violation != null) {
            return invalid(violation);
        }

        AbstractCommand command = createCommand(code, packet);
        return new ParseResult(command, isPracticalCommand(code)
                ? Disposition.PRACTICAL : Disposition.UNSUPPORTED, null);
    }

    public static AbstractCommand toCommand(int[] packet) {
        ParseResult result = parse(packet);
        return result.disposition == Disposition.INVALID || result.disposition == Disposition.UNKNOWN
                ? null : result.command;
    }

    public static boolean isRecognizedCommandId(int code) {
        return code >= 0 && code <= 0x19;
    }

    public static Class<? extends AbstractCommand> commandClass(int code) {
        return switch (code) {
            case 0x00 -> Pal01Cmd.class;
            case 0x01 -> Pal23Cmd.class;
            case 0x02 -> Pal03Cmd.class;
            case 0x03 -> Pal12Cmd.class;
            case 0x04 -> AttrBlkCmd.class;
            case 0x05 -> AttrLinCmd.class;
            case 0x06 -> AttrDivCmd.class;
            case 0x07 -> AttrChrCmd.class;
            case 0x08 -> SoundCmd.class;
            case 0x09 -> SoundTrnCmd.class;
            case 0x0a -> PalSetCmd.class;
            case 0x0b -> PalTrnCmd.class;
            case 0x0c -> AtrcEnCmd.class;
            case 0x0d -> TestEnCmd.class;
            case 0x0e -> IconEnCmd.class;
            case 0x0f -> DataSndCmd.class;
            case 0x10 -> DataTrnCmd.class;
            case 0x11 -> MltReqCmd.class;
            case 0x12 -> JumpCmd.class;
            case 0x13 -> ChrTrnCmd.class;
            case 0x14 -> PctTrnCmd.class;
            case 0x15 -> AttrTrnCmd.class;
            case 0x16 -> AttrSetCmd.class;
            case 0x17 -> MaskEnCmd.class;
            case 0x18 -> ObjTrnCmd.class;
            case 0x19 -> PalPriCmd.class;
            default -> null;
        };
    }

    public static String validateTransferData(TransferCommand command, int[] data) {
        if (command == null) {
            return "transfer command is absent";
        }
        if (data == null) {
            return "transfer payload is absent";
        }
        if (data.length != TRANSFER_SIZE) {
            return "transfer payload has " + data.length + " bytes, expected " + TRANSFER_SIZE;
        }
        for (int i = 0; i < data.length; i++) {
            if (data[i] < 0 || data[i] > 0xff) {
                return "transfer payload byte " + i + " is outside 0..255";
            }
        }
        return null;
    }

    /** Validates a newly captured ICD2 payload before it can enter practical SGB state. */
    public static String validateTransferCommitData(TransferCommand command, int[] data) {
        String violation = validateTransferData(command, data);
        if (violation != null) {
            return violation;
        }
        return command instanceof PctTrnCmd ? validatePictureData(data) : null;
    }

    private static String validatePictureData(int[] data) {
        // Coffee GB deliberately retains its established full three-bit palette addressing and
        // Pocket Kanjirou compatibility: tile numbers above 0xff (notably 0x2ff) are transparent
        // rather than wrapped into CHR RAM. The documented BG-priority bit, however, must be zero.
        for (int index = 0; index < 32 * 28; index++) {
            int value = data[index * 2] | data[index * 2 + 1] << 8;
            if ((value & 0x2000) != 0) {
                return "PCT_TRN map entry " + index + " sets unsupported BG priority";
            }
        }
        return null;
    }

    private static AbstractCommand createCommand(int code, int[] packet) {
        return switch (code) {
            case 0x00 -> new Pal01Cmd(packet);
            case 0x01 -> new Pal23Cmd(packet);
            case 0x02 -> new Pal03Cmd(packet);
            case 0x03 -> new Pal12Cmd(packet);
            case 0x04 -> new AttrBlkCmd(packet);
            case 0x05 -> new AttrLinCmd(packet);
            case 0x06 -> new AttrDivCmd(packet);
            case 0x07 -> new AttrChrCmd(packet);
            case 0x08 -> new SoundCmd(packet);
            case 0x09 -> new SoundTrnCmd(packet);
            case 0x0a -> new PalSetCmd(packet);
            case 0x0b -> new PalTrnCmd(packet);
            case 0x0c -> new AtrcEnCmd(packet);
            case 0x0d -> new TestEnCmd(packet);
            case 0x0e -> new IconEnCmd(packet);
            case 0x0f -> new DataSndCmd(packet);
            case 0x10 -> new DataTrnCmd(packet);
            case 0x11 -> new MltReqCmd(packet);
            case 0x12 -> new JumpCmd(packet);
            case 0x13 -> new ChrTrnCmd(packet);
            case 0x14 -> new PctTrnCmd(packet);
            case 0x15 -> new AttrTrnCmd(packet);
            case 0x16 -> new AttrSetCmd(packet);
            case 0x17 -> new MaskEnCmd(packet);
            case 0x18 -> new ObjTrnCmd(packet);
            case 0x19 -> new PalPriCmd(packet);
            default -> null;
        };
    }

    private static ParseResult invalid(String reason) {
        return new ParseResult(null, Disposition.INVALID, reason);
    }

    private static boolean isPracticalCommand(int code) {
        return switch (code) {
            case 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                    0x0a, 0x0b, 0x11, 0x13, 0x14, 0x15, 0x16, 0x17, 0x19 -> true;
            default -> false;
        };
    }

    private static String validateEnvelope(int[] packet) {
        if (packet == null) {
            return "command bytes are absent";
        }
        if (packet.length < PACKET_SIZE || packet.length > PACKET_SIZE * MAX_PACKETS
                || packet.length % PACKET_SIZE != 0) {
            return "command length " + packet.length + " is not 1..7 complete packets";
        }
        for (int i = 0; i < packet.length; i++) {
            if (packet[i] < 0 || packet[i] > 0xff) {
                return "command byte " + i + " is outside 0..255";
            }
        }
        int count = packet[0] & 7;
        if (count < 1 || count > MAX_PACKETS) {
            return "declared packet count " + count + " is outside 1..7";
        }
        if (packet.length != count * PACKET_SIZE) {
            return "declared packet count " + count + " does not match " + packet.length + " bytes";
        }
        return null;
    }

    private static String validateCommand(int code, int[] packet) {
        return switch (code) {
            // Byte 15 has no defined purpose for the direct-palette commands. Pan Docs' packet
            // contract says such bytes are ignored, so rejecting it would invent a reserved-bit
            // rule that the SGB firmware does not have.
            case 0x00, 0x01, 0x02, 0x03 -> requirePacketCount(packet, 1, 1);
            case 0x04 -> validateAttrBlk(packet);
            case 0x05 -> validateAttrLin(packet);
            case 0x06 -> validateAttrDiv(packet);
            case 0x07 -> validateAttrChr(packet);
            case 0x08 -> firstViolation(
                    requirePacketCount(packet, 1, 1), requireZeroes(packet, 5));
            case 0x09 -> firstViolation(
                    requirePacketCount(packet, 1, 1), requireZeroes(packet, 1));
            case 0x0a -> validatePalSet(packet);
            // Only the fixed $59 header is defined. The other packet bytes are ignored.
            case 0x0b -> requirePacketCount(packet, 1, 1);
            case 0x0c, 0x0d -> firstViolation(
                    requirePacketCount(packet, 1, 1), requireRange(packet[1], 0, 1,
                            "boolean control"), requireZeroes(packet, 2));
            case 0x0e -> firstViolation(
                    requirePacketCount(packet, 1, 1),
                    (packet[1] & ~0x07) == 0 ? null : "ICON_EN has reserved flag bits set",
                    requireZeroes(packet, 2));
            case 0x0f -> validateDataSnd(packet);
            case 0x10 -> firstViolation(
                    requirePacketCount(packet, 1, 1), requireZeroes(packet, 4));
            case 0x11 -> firstViolation(
                    requirePacketCount(packet, 1, 1), requireRange(packet[1], 0, 3,
                            "MLT_REQ control"), requireZeroes(packet, 2));
            case 0x12 -> firstViolation(
                    requirePacketCount(packet, 1, 1), requireZeroes(packet, 7));
            case 0x13 -> firstViolation(
                    requirePacketCount(packet, 1, 1),
                    (packet[1] & ~0x03) == 0 ? null : "CHR_TRN has reserved destination bits set",
                    requireZeroes(packet, 2));
            case 0x14, 0x15 -> firstViolation(
                    requirePacketCount(packet, 1, 1), requireZeroes(packet, 1));
            case 0x16 -> firstViolation(
                    requirePacketCount(packet, 1, 1),
                    (packet[1] & 0x80) == 0 ? null : "ATTR_SET has its reserved bit set",
                    requireRange(packet[1] & 0x3f, 0, 44, "ATTR_SET file"),
                    requireZeroes(packet, 2));
            case 0x17 -> firstViolation(
                    requirePacketCount(packet, 1, 1), requireRange(packet[1], 0, 3,
                            "MASK_EN value"), requireZeroes(packet, 2));
            // The pinned public reference does not define the OBJ_TRN payload. Its platform-
            // dependent behavior remains unsupported, so only its unambiguous framing is used.
            case 0x18 -> requirePacketCount(packet, 1, 1);
            case 0x19 -> firstViolation(
                    requirePacketCount(packet, 1, 1), requireRange(packet[1], 0, 1,
                            "PAL_PRI value"));
            default -> "unregistered command ID 0x" + Integer.toHexString(code);
        };
    }

    private static String validateAttrBlk(int[] packet) {
        String violation = requirePacketCount(packet, 1, 7);
        if (violation != null) {
            return violation;
        }
        int count = packet[1];
        if (count < 1 || count > 18) {
            return "ATTR_BLK data-set count " + count + " is outside 1..18";
        }
        int used = 2 + count * 6;
        if (used > packet.length) {
            return "ATTR_BLK data-set count exceeds the collected payload";
        }
        for (int i = 0; i < count; i++) {
            int offset = 2 + i * 6;
            if ((packet[offset] & ~0x07) != 0) {
                return "ATTR_BLK data set " + i + " has reserved control bits set";
            }
            if ((packet[offset + 1] & ~0x3f) != 0) {
                return "ATTR_BLK data set " + i + " has reserved palette bits set";
            }
            int x1 = packet[offset + 2];
            int y1 = packet[offset + 3];
            int x2 = packet[offset + 4];
            int y2 = packet[offset + 5];
            if (x1 > 19 || x2 > 19 || y1 > 17 || y2 > 17 || x1 > x2 || y1 > y2) {
                return "ATTR_BLK data set " + i + " has invalid rectangle coordinates";
            }
        }
        return requireZeroes(packet, used);
    }

    private static String validateAttrLin(int[] packet) {
        String violation = requirePacketCount(packet, 1, 7);
        if (violation != null) {
            return violation;
        }
        int count = packet[1];
        if (count < 1 || count > 110) {
            return "ATTR_LIN data-set count " + count + " is outside 1..110";
        }
        int used = 2 + count;
        if (used > packet.length) {
            return "ATTR_LIN data-set count exceeds the collected payload";
        }
        for (int i = 0; i < count; i++) {
            int entry = packet[2 + i];
            int line = entry & 0x1f;
            boolean horizontal = (entry & 0x80) != 0;
            if (line >= (horizontal ? 18 : 20)) {
                return "ATTR_LIN data set " + i + " has out-of-range line " + line;
            }
        }
        return requireZeroes(packet, used);
    }

    private static String validateAttrDiv(int[] packet) {
        String violation = requirePacketCount(packet, 1, 1);
        if (violation != null) {
            return violation;
        }
        if ((packet[1] & 0x80) != 0) {
            return "ATTR_DIV has its reserved bit set";
        }
        boolean horizontal = (packet[1] & 0x40) != 0;
        int maximum = horizontal ? 17 : 19;
        return firstViolation(requireRange(packet[2], 0, maximum, "ATTR_DIV coordinate"),
                requireZeroes(packet, 3));
    }

    private static String validateAttrChr(int[] packet) {
        String violation = requirePacketCount(packet, 1, 6);
        if (violation != null) {
            return violation;
        }
        violation = firstViolation(requireRange(packet[1], 0, 19, "ATTR_CHR x"),
                requireRange(packet[2], 0, 17, "ATTR_CHR y"));
        if (violation != null) {
            return violation;
        }
        int count = littleEndian16(packet, 3);
        if (count < 1 || count > 360) {
            return "ATTR_CHR data-set count " + count + " is outside 1..360";
        }
        if (packet[5] != 0 && packet[5] != 1) {
            return "ATTR_CHR writing style is outside 0..1";
        }
        int dataBytes = (count + 3) / 4;
        int used = 6 + dataBytes;
        if (used > packet.length) {
            return "ATTR_CHR data-set count exceeds the collected payload";
        }
        int remainder = count & 3;
        if (remainder != 0) {
            int unusedBits = 8 - remainder * 2;
            if ((packet[used - 1] & ((1 << unusedBits) - 1)) != 0) {
                return "ATTR_CHR has nonzero unused palette bits";
            }
        }
        return requireZeroes(packet, used);
    }

    private static String validatePalSet(int[] packet) {
        String violation = requirePacketCount(packet, 1, 1);
        if (violation != null) {
            return violation;
        }
        for (int i = 0; i < 4; i++) {
            int id = littleEndian16(packet, 1 + i * 2);
            if (id > 511) {
                return "PAL_SET palette " + i + " ID " + id + " is outside 0..511";
            }
        }
        if ((packet[9] & 0x3f) > 44) {
            return "PAL_SET attribute-file ID is outside 0..44";
        }
        // Bytes 10..15 have no defined purpose rather than a documented zero requirement.
        return null;
    }

    private static String validateDataSnd(int[] packet) {
        String violation = requirePacketCount(packet, 1, 1);
        if (violation != null) {
            return violation;
        }
        int count = packet[4];
        if (count < 1 || count > 11) {
            return "DATA_SND byte count " + count + " is outside 1..11";
        }
        return requireZeroes(packet, 5 + count);
    }

    private static int littleEndian16(int[] packet, int offset) {
        return packet[offset] | packet[offset + 1] << 8;
    }

    private static String requirePacketCount(int[] packet, int minimum, int maximum) {
        int count = packet[0] & 7;
        return count >= minimum && count <= maximum ? null
                : "command packet count " + count + " is outside " + minimum + ".." + maximum;
    }

    private static String requireRange(int value, int minimum, int maximum, String field) {
        return value >= minimum && value <= maximum ? null
                : field + " " + value + " is outside " + minimum + ".." + maximum;
    }

    private static String requireZeroes(int[] packet, int start) {
        for (int i = start; i < packet.length; i++) {
            if (packet[i] != 0) {
                return "reserved or unused byte " + i + " is nonzero";
            }
        }
        return null;
    }

    private static String firstViolation(String... violations) {
        for (String violation : violations) {
            if (violation != null) {
                return violation;
            }
        }
        return null;
    }

    public static class AbstractCommand implements Event {
        protected final int[] packet;

        protected AbstractCommand(int[] packet) {
            this.packet = packet.clone();
        }

        public int getCode() {
            return packet[0] / 8;
        }

        public int getLength() {
            return packet[0] % 8;
        }

        public String toString() {
            return "Command [code=" + getCode() + ", length=" + getLength() + "]";
        }
    }

    public static class TransferCommand extends AbstractCommand {

        protected int[] dataTransfer;

        protected TransferCommand(int[] packet) {
            super(packet);
        }

        public static TransferCommand restoreState(ComponentState<TransferCommand> state) {
            if (!(state instanceof TransferCommandState mem)) {
                throw new IllegalArgumentException("Invalid state type");
            }
            var command = Commands.toCommand(mem.packet.clone());
            if (command instanceof TransferCommand transferCommand) {
                transferCommand.setDataTransfer(mem.dataTransfer == null ? null : mem.dataTransfer.clone());
                return transferCommand;
            } else {
                throw new IllegalArgumentException("ComponentState does not contain a transfer command");
            }
        }

        public void setDataTransfer(int[] dataTransfer) {
            if (dataTransfer == null) {
                this.dataTransfer = null;
                return;
            }
            String violation = validateTransferData(this, dataTransfer);
            if (violation != null) {
                throw new IllegalArgumentException(violation);
            }
            this.dataTransfer = dataTransfer.clone();
        }

        public ComponentState<TransferCommand> captureState() {
            return new TransferCommandState(packet.clone(), dataTransfer == null ? null : dataTransfer.clone());
        }

        public ComponentState<TransferCommand> captureState(MachineStateCapture capture) {
            return new TransferCommandState(
                    capture.ints(packet),
                    dataTransfer == null ? null : capture.ints(dataTransfer));
        }

        public void declareMachineStatePayloads(MachineStateCapture capture) {
            capture.declareInts(packet);
            if (dataTransfer != null) {
                capture.declareInts(dataTransfer);
            }
        }

        private record TransferCommandState(int[] packet, int[] dataTransfer) implements ComponentState<TransferCommand> {
        }

        /** Importer-only compatibility record for released local snapshots. */
        private record TransferCommandMemento(int[] packet, int[] dataTransfer) implements Memento<TransferCommand> {
        }
    }

    public static class Pal01Cmd extends AbstractCommand {
        protected Pal01Cmd(int[] packet) {
            super(packet);
        }

        public int[] getPalette0() {
            return new int[]{packet[1] | packet[2] << 8, packet[3] | packet[4] << 8, packet[5] | packet[6] << 8, packet[7] | packet[8] << 8};
        }

        public int[] getPalette1() {
            return new int[]{packet[1] | packet[2] << 8, packet[9] | packet[10] << 8, packet[11] | packet[12] << 8, packet[13] | packet[14] << 8};
        }

        public String toString() {
            return "PAL01 [palette0=" + Arrays.toString(getPalette0()) + ", palette1=" + Arrays.toString(getPalette1()) + "]";
        }
    }

    public static class Pal23Cmd extends AbstractCommand {
        protected Pal23Cmd(int[] packet) {
            super(packet);
        }

        public int[] getPalette2() {
            return new int[]{packet[1] | packet[2] << 8, packet[3] | packet[4] << 8, packet[5] | packet[6] << 8, packet[7] | packet[8] << 8};
        }

        public int[] getPalette3() {
            return new int[]{packet[1] | packet[2] << 8, packet[9] | packet[10] << 8, packet[11] | packet[12] << 8, packet[13] | packet[14] << 8};
        }

        public String toString() {
            return "PAL23 [palette2=" + Arrays.toString(getPalette2()) + ", palette3=" + Arrays.toString(getPalette3()) + "]";
        }
    }

    public static class Pal03Cmd extends AbstractCommand {
        protected Pal03Cmd(int[] packet) {
            super(packet);
        }

        public int[] getPalette0() {
            return new int[]{packet[1] | packet[2] << 8, packet[3] | packet[4] << 8, packet[5] | packet[6] << 8, packet[7] | packet[8] << 8};
        }

        public int[] getPalette3() {
            return new int[]{packet[1] | packet[2] << 8, packet[9] | packet[10] << 8, packet[11] | packet[12] << 8, packet[13] | packet[14] << 8};
        }

        public String toString() {
            return "PAL03 [palette0=" + Arrays.toString(getPalette0()) + ", palette3=" + Arrays.toString(getPalette3()) + "]";
        }
    }

    public static class Pal12Cmd extends AbstractCommand {
        protected Pal12Cmd(int[] packet) {
            super(packet);
        }

        public int[] getPalette1() {
            return new int[]{packet[1] | packet[2] << 8, packet[3] | packet[4] << 8, packet[5] | packet[6] << 8, packet[7] | packet[8] << 8};
        }

        public int[] getPalette2() {
            return new int[]{packet[1] | packet[2] << 8, packet[9] | packet[10] << 8, packet[11] | packet[12] << 8, packet[13] | packet[14] << 8};
        }

        public String toString() {
            return "PAL12 [palette0=" + Arrays.toString(getPalette1()) + ", palette3=" + Arrays.toString(getPalette2()) + "]";
        }
    }

    public static class AttrBlkCmd extends AbstractCommand {
        protected AttrBlkCmd(int[] packet) {
            super(packet);
        }

        public int getDataSetsCount() {
            return packet[1];
        }

        public DataSet getDataSet(int index) {
            return new DataSet(index);
        }

        public class DataSet {

            private final int offset;

            private DataSet(int index) {
                this.offset = 2 + (index - 1) * 6;
            }

            public int getControlCode() {
                return packet[offset];
            }

            public boolean changeColorsInside() {
                return (packet[offset] & 0b00000001) != 0;
            }

            public boolean changeLineColor() {
                return (packet[offset] & 0b00000010) != 0;
            }

            public boolean changeColorsOutside() {
                return (packet[offset] & 0b00000100) != 0;
            }

            public int getControlPaletteDesignation() {
                return packet[offset + 1];
            }

            public int paletteNumberInside() {
                return packet[offset + 1] & 0b00000011;
            }

            public int paletteNumberLine() {
                return (packet[offset + 1] >> 2) & 0b00000011;
            }

            public int paletteNumberOutside() {
                return (packet[offset + 1] >> 4) & 0b00000011;
            }

            public int getX1() {
                return packet[offset + 2];
            }

            public int getY1() {
                return packet[offset + 3];
            }

            public int getX2() {
                return packet[offset + 4];
            }

            public int getY2() {
                return packet[offset + 5];
            }

            public boolean isOutside(int x, int y) {
                return x > getX2() || y > getY2() || x < getX1() || y < getY1();
            }

            public boolean isInside(int x, int y) {
                return x > getX1() && y > getY1() && x < getX2() && y < getY2();
            }


            public boolean isOnLine(int x, int y) {
                return (x == getX1() && y >= getY1() && y <= getY2())
                        || (x == getX2() && y >= getY1() && y <= getY2())
                        || (y == getY1() && x >= getX1() && x <= getX2())
                        || (y == getY2() && x >= getX1() && x <= getX2());
            }
        }

        public String toString() {
            return "ATTR_BLK [dataSetsCount=" + getDataSetsCount() + "]";
        }
    }

    public static class AttrLinCmd extends AbstractCommand {
        protected AttrLinCmd(int[] packet) {
            super(packet);
        }

        public int getDataSetsCount() {
            return packet[1];
        }

        public DataSet getDataSet(int index) {
            return new DataSet(index);
        }

        public class DataSet {

            private final int offset;

            private DataSet(int index) {
                offset = 2 + index - 1;
            }

            public int getLineNumber() {
                return packet[offset] & 0b00011111;
            }

            public int getPaletteNumber() {
                return (packet[offset] >> 5) & 0b00000011;
            }

            public char getHVMode() {
                return (packet[offset] & 0b10000000) == 0 ? 'V' : 'H';
            }
        }

        public String toString() {
            return "ATTR_LIN [dataSetsCount=" + getDataSetsCount() + "]";
        }
    }

    public static class AttrDivCmd extends AbstractCommand {
        protected AttrDivCmd(int[] packet) {
            super(packet);
        }

        public int getPaletteNumberBelowRight() {
            return packet[1] & 0b00000011;
        }

        public int getPaletteNumberAboveLeft() {
            return packet[1] >> 2 & 0b00000011;
        }

        public int getPaletteNumberDivisionLine() {
            return packet[1] >> 4 & 0b00000011;
        }

        public char getHVMode() {
            return (packet[1] & 0b01000000) == 0 ? 'V' : 'H';
        }

        public int getXY() {
            return packet[2];
        }

        public String toString() {
            return "ATTR_DIV [paletteNumberBelowRight=" + getPaletteNumberBelowRight() + ", paletteNumberAboveLeft=" + getPaletteNumberAboveLeft() + ", paletteNumberDivisionLine=" + getPaletteNumberDivisionLine() + ", HVMode=" + getHVMode() + ", XY=" + getXY() + "]";
        }
    }

    public static class AttrChrCmd extends AbstractCommand {
        protected AttrChrCmd(int[] packet) {
            super(packet);
        }

        public int getX() {
            return packet[1];
        }

        public int getY() {
            return packet[2];
        }

        public int getDataSetCount() {
            return packet[3] | packet[4] << 8;
        }

        public int getWritingStyle() {
            return packet[5];
        }

        public int getDataSet(int index) {
            int b = packet[6 + (index - 1) / 4];
            int i = (index - 1) % 4;
            return (b >> (2 * (3 - i))) & 0b00000011;
        }

        public String toString() {
            return "ATTR_CHR [x=" + getX() + ", y=" + getY() + ", dataSetCount=" + getDataSetCount() + ", writingStyle=" + getWritingStyle() + "]";
        }
    }

    public static class SoundCmd extends AbstractCommand {
        protected SoundCmd(int[] packet) {
            super(packet);
        }

        public int getSoundEffectA() {
            return packet[1];
        }

        public int getSoundEffectB() {
            return packet[2];
        }

        public int getSoundEffectAPitch() {
            return packet[3] & 0b00000011;
        }

        public int getSoundEffectAVolume() {
            return (packet[3] >> 2) & 0b00000011;
        }

        public int getSoundEffectBPitch() {
            return (packet[3] >> 4) & 0b00000011;
        }

        public int getSoundEffectBVolume() {
            return (packet[3] >> 6) & 0b00000011;
        }

        public int getMusicScore() {
            return packet[4];
        }

        public String toString() {
            return "SOUND [soundEffectA=" + getSoundEffectA() + ", soundEffectB=" + getSoundEffectB() + ", soundEffectAPitch=" + getSoundEffectAPitch() + ", soundEffectAVolume=" + getSoundEffectAVolume() + ", soundEffectBPitch=" + getSoundEffectBPitch() + ", soundEffectBVolume=" + getSoundEffectBVolume() + ", musicScore=" + getMusicScore() + "]";
        }
    }

    public static class SoundTrnCmd extends TransferCommand {
        protected SoundTrnCmd(int[] packet) {
            super(packet);
        }

        public String toString() {
            return "SOU_TRN";
        }
    }

    public static class PalSetCmd extends AbstractCommand {
        protected PalSetCmd(int[] packet) {
            super(packet);
        }

        public int[] getPaletteIds() {
            return new int[]{
                    packet[1] | packet[2] << 8,
                    packet[3] | packet[4] << 8,
                    packet[5] | packet[6] << 8,
                    packet[7] | packet[8] << 8,
            };
        }

        public boolean getApplyAtf() {
            return (packet[9] & 0b10000000) != 0;
        }

        public boolean getCancelMaskEn() {
            return (packet[9] & 0b01000000) != 0;
        }

        public int getAtfNumber() {
            return packet[9] & 0b00111111;
        }

        public String toString() {
            return "PAL_SET [paletteIds=" + Arrays.toString(getPaletteIds()) + ", applyAtf=" + getApplyAtf() + ", cancelMaskEn=" + getCancelMaskEn() + ", atfNumber=" + getAtfNumber() + "]";
        }
    }

    public static class PalTrnCmd extends TransferCommand {
        protected PalTrnCmd(int[] packet) {
            super(packet);
        }

        public int[] getPalette(int id) {
            checkArgument(id >= 0 && id < 512);
            int offset = id * 8;
            return new int[]{
                    dataTransfer[offset] | dataTransfer[offset + 1] << 8,
                    dataTransfer[offset + 2] | dataTransfer[offset + 3] << 8,
                    dataTransfer[offset + 4] | dataTransfer[offset + 5] << 8,
                    dataTransfer[offset + 6] | dataTransfer[offset + 7] << 8,
            };
        }

        public String toString() {
            return "PAL_TRN";
        }
    }

    public static class AtrcEnCmd extends AbstractCommand {
        protected AtrcEnCmd(int[] packet) {
            super(packet);
        }

        public boolean getAttractionDisable() {
            return packet[1] == 1;
        }

        public String toString() {
            return "ATRC_EN [attractionDisable=" + getAttractionDisable() + "]";
        }
    }

    public static class TestEnCmd extends AbstractCommand {
        protected TestEnCmd(int[] packet) {
            super(packet);
        }

        public boolean getTestModeEnable() {
            return packet[1] == 1;
        }

        public String toString() {
            return "TEST_EN [testModeEnable=" + getTestModeEnable() + "]";
        }
    }

    public static class IconEnCmd extends AbstractCommand {
        protected IconEnCmd(int[] packet) {
            super(packet);
        }

        public boolean getDisableUseSgbColorPalette() {
            return (packet[1] & 0b00000001) != 0;
        }

        public boolean getDisableControllerSetupScreen() {
            return (packet[1] & 0b00000010) != 0;
        }

        public boolean getDisableSgbRegisterFileTransfer() {
            return (packet[1] & 0b00000100) != 0;
        }

        public String toString() {
            return "ICON_EN [disableUseSgbColorPalette=" + getDisableUseSgbColorPalette() + ", disableControllerSetupScreen=" + getDisableControllerSetupScreen() + ", disableSgbRegisterFileTransfer=" + getDisableSgbRegisterFileTransfer() + "]";
        }
    }

    public static class DataSndCmd extends AbstractCommand {
        protected DataSndCmd(int[] packet) {
            super(packet);
        }

        public int getSnesAddress() {
            return packet[1] | packet[2] << 8;
        }

        public int getBankAddress() {
            return packet[3];
        }

        public int getLength() {
            return packet[4];
        }

        public int[] getDataBytes() {
            int[] data = new int[getLength()];
            if (getLength() >= 0) System.arraycopy(packet, 5, data, 0, getLength());
            return data;
        }

        public String toString() {
            return "DATA_SND [snesAddress=" + String.format("0x%04X", getSnesAddress()) + ", bankAddress=" + getBankAddress() + ", length=" + getLength() + "]";
        }
    }

    public static class DataTrnCmd extends TransferCommand {
        protected DataTrnCmd(int[] packet) {
            super(packet);
        }

        public int getSnesAddress() {
            return packet[1] | packet[2] << 8;
        }

        public int getBankAddress() {
            return packet[3];
        }

        public String toString() {
            return "DATA_TRN [snesAddress=" + getSnesAddress() + ", bankAddress=" + getBankAddress() + ", length=" + getLength() + "]";
        }
    }

    public static class MltReqCmd extends AbstractCommand {
        protected MltReqCmd(int[] packet) {
            super(packet);
        }

        public int getMultiplayerControl() {
            return packet[1];
        }

        public String toString() {
            return "MLT_REQ [multiplayerControl=" + getMultiplayerControl() + "]";
        }
    }

    public static class JumpCmd extends AbstractCommand {
        protected JumpCmd(int[] packet) {
            super(packet);
        }

        public int getSnesAddress() {
            return packet[1] | packet[2] << 8;
        }

        public int getBankAddress() {
            return packet[3];
        }

        public int getNmiHandlerAddress() {
            return packet[4] | packet[5] << 8;
        }

        public int getNmiHandlerBankAddress() {
            return packet[6];
        }

        public String toString() {
            return "JUMP [snesAddress=" + getSnesAddress() + ", bankAddress=" + getBankAddress() + ", nmiHandlerAddress=" + getNmiHandlerAddress() + ", nmiHandlerBankAddress=" + getNmiHandlerBankAddress() + "]";
        }
    }

    public static class ChrTrnCmd extends TransferCommand {
        protected ChrTrnCmd(int[] packet) {
            super(packet);
        }

        public int getTileOffset() {
            return (packet[1] & 0b00000001) == 0 ? 0x00 : 0x80;
        }

        public char getTileType() {
            return (packet[1] & 0b00000010) == 0 ? 'B' : 'O';
        }

        public String toString() {
            return "CHR_TRN [tileOffset=" + getTileOffset() + ", tileType=" + getTileType() + "]";
        }
    }

    public static class PctTrnCmd extends TransferCommand {
        protected PctTrnCmd(int[] packet) {
            super(packet);
        }

        public BgMapEntry getBgMapEntry(int index) {
            return new BgMapEntry(index);
        }

        public int getPaletteColor(int paletteId, int colorId) {
            int offset = 0x0800 + (paletteId - 4) * 16 * 2 + colorId * 2;
            return dataTransfer[offset] | dataTransfer[offset + 1] << 8;
        }

        public class BgMapEntry {

            private final int value;

            private BgMapEntry(int index) {
                value = dataTransfer[2 * index] | dataTransfer[2 * index + 1] << 8;
            }

            public int getCharNumber() {
                return value & 0b0000000011111111;
            }

            public boolean isUnusedTile() {
                return (value & 0b0000001100000000) != 0;
            }

            public int getPaletteNumber() {
                return (value >> 10) & 0b00000111;
            }

            public int getBgPriority() {
                return (value >> 13) & 0b00000001;
            }

            public boolean getXFlip() {
                return ((value >> 14) & 0b00000001) == 1;
            }

            public boolean getYFlip() {
                return ((value >> 15) & 0b00000001) == 1;
            }
        }

        public String toString() {
            return "PCT_TRN";
        }
    }

    public static class AttrTrnCmd extends TransferCommand {
        protected AttrTrnCmd(int[] packet) {
            super(packet);
        }

        public AttributeFile getAttributeFile(int atfId) {
            return new AttributeFile(atfId);
        }

        public String toString() {
            return "ATTR_TRN";
        }

        public class AttributeFile {

            private final int offset;

            public AttributeFile(int atfId) {
                this.offset = atfId * 90;
            }

            public int getColor(int charId) {
                int b = dataTransfer[offset + charId / 4];
                return (b >> 2 * (3 - (charId % 4))) & 0b11;
            }
        }
    }

    public static class AttrSetCmd extends AbstractCommand {
        protected AttrSetCmd(int[] packet) {
            super(packet);
        }

        public int getAttributeFileNumber() {
            return packet[1] & 0b00111111;
        }

        public boolean getCancelMask() {
            return (packet[1] & 0b01000000) != 0;
        }

        public String toString() {
            return "ATTR_SET [attributeFileNumber=" + getAttributeFileNumber() + ", cancelMask=" + getCancelMask() + "]";
        }
    }

    public static class MaskEnCmd extends AbstractCommand {
        protected MaskEnCmd(int[] packet) {
            super(packet);
        }

        public GameboyScreenMask getScreenMask() {
            return GameboyScreenMask.values()[packet[1]];
        }

        public enum GameboyScreenMask {
            CANCEL, FREEZE, BLANK_BLACK, BLANK_COLOR0
        }

        public String toString() {
            return "MASK_EN [screenMask=" + getScreenMask() + "]";
        }
    }

    public static class ObjTrnCmd extends AbstractCommand {
        protected ObjTrnCmd(int[] packet) {
            super(packet);
        }

        public boolean enableSnesObjMode() {
            return (packet[1] & 0b00000001) != 0;
        }

        public boolean changeObjColor() {
            return (packet[1] & 0b00000010) != 0;
        }

        public int getSystemPaletteForObjPalette(int objPalette) {
            int offset = 2 + (objPalette - 4) * 2;
            return packet[offset] | packet[offset + 1] << 8;
        }

        public String toString() {
            return "OBJ_TRN [enableSnesObjMode=" + enableSnesObjMode() + ", changeObjColor=" + changeObjColor() + "]";
        }
    }

    public static class PalPriCmd extends AbstractCommand {
        protected PalPriCmd(int[] packet) {
            super(packet);
        }

        public boolean getPriority() {
            return packet[1] == 1;
        }

        public String toString() {
            return "PAL_PRI [getPriority()=" + getPriority() + "]";
        }
    }


}
