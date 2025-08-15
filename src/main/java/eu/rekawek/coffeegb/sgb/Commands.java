package eu.rekawek.coffeegb.sgb;

import eu.rekawek.coffeegb.events.Event;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;

public class Commands {

    private Commands() {
    }

    public static AbstractCommand toCommand(int[] packet) {
        int code = packet[0] / 8;
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

    public static class AbstractCommand implements Event {
        protected final int[] packet;

        protected AbstractCommand(int[] packet) {
            this.packet = packet;
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

        public void setDataTransfer(int[] dataTransfer) {
            this.dataTransfer = dataTransfer;
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
            return (b >> (i * 2)) & 0b00000011;
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
            return "DATA_SND [snesAddress=" + getSnesAddress() + ", bankAddress=" + getBankAddress() + ", length=" + getLength() + "]";
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
