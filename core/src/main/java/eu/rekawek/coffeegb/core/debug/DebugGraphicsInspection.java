package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/** Detached raw PPU memories and palette state captured without CPU visibility rules. */
public final class DebugGraphicsInspection {

    public static final int VRAM_BANK_LENGTH = 0x2000;

    public static final int OAM_LENGTH = 0xa0;

    public static final int CGB_PALETTE_LENGTH = 0x40;

    private final DebugGraphicsHardwareMode hardwareMode;

    private final int selectedVramBank;

    private final int lcdc;

    private final int bgp;

    private final int obp0;

    private final int obp1;

    private final int bgPaletteIndex;

    private final int objectPaletteIndex;

    private final DebugByteData vramBank0;

    private final DebugByteData vramBank1;

    private final DebugByteData oam;

    private final DebugByteData cgbBackgroundPalette;

    private final DebugByteData cgbObjectPalette;

    public DebugGraphicsInspection(
            DebugGraphicsHardwareMode hardwareMode,
            int selectedVramBank,
            int lcdc,
            int bgp,
            int obp0,
            int obp1,
            int bgPaletteIndex,
            int objectPaletteIndex,
            DebugByteData vramBank0,
            DebugByteData vramBank1,
            DebugByteData oam,
            DebugByteData cgbBackgroundPalette,
            DebugByteData cgbObjectPalette) {
        this.hardwareMode = Objects.requireNonNull(hardwareMode, "hardwareMode");
        DebugValueChecks.range("selectedVramBank", selectedVramBank, 0, 1);
        if (hardwareMode != DebugGraphicsHardwareMode.CGB_NATIVE && selectedVramBank != 0) {
            throw new IllegalArgumentException(
                    "Only native CGB graphics may select VRAM bank one");
        }
        this.selectedVramBank = selectedVramBank;
        DebugValueChecks.unsignedByte("lcdc", lcdc);
        DebugValueChecks.unsignedByte("bgp", bgp);
        DebugValueChecks.unsignedByte("obp0", obp0);
        DebugValueChecks.unsignedByte("obp1", obp1);
        this.lcdc = lcdc;
        this.bgp = bgp;
        this.obp0 = obp0;
        this.obp1 = obp1;
        boolean cgbHardware = hardwareMode != DebugGraphicsHardwareMode.DMG;
        requirePaletteIndex("bgPaletteIndex", bgPaletteIndex, cgbHardware);
        requirePaletteIndex("objectPaletteIndex", objectPaletteIndex, cgbHardware);
        this.bgPaletteIndex = bgPaletteIndex;
        this.objectPaletteIndex = objectPaletteIndex;
        this.vramBank0 = requireLength("vramBank0", vramBank0, VRAM_BANK_LENGTH);
        this.vramBank1 = requireLength(
                "vramBank1", vramBank1, cgbHardware ? VRAM_BANK_LENGTH : 0);
        this.oam = requireLength("oam", oam, OAM_LENGTH);
        this.cgbBackgroundPalette = requireLength(
                "cgbBackgroundPalette", cgbBackgroundPalette,
                cgbHardware ? CGB_PALETTE_LENGTH : 0);
        this.cgbObjectPalette = requireLength(
                "cgbObjectPalette", cgbObjectPalette,
                cgbHardware ? CGB_PALETTE_LENGTH : 0);
    }

    private static void requirePaletteIndex(String name, int value, boolean cgbHardware) {
        if (cgbHardware) {
            DebugValueChecks.unsignedByte(name, value);
        } else if (value != -1) {
            throw new IllegalArgumentException(name + " must be -1 on DMG hardware");
        }
    }

    private static DebugByteData requireLength(
            String name, DebugByteData value, int length) {
        Objects.requireNonNull(value, name);
        if (value.length() != length) {
            throw new IllegalArgumentException(
                    name + " must contain exactly " + length + " bytes");
        }
        return value;
    }

    public DebugGraphicsHardwareMode hardwareMode() {
        return hardwareMode;
    }

    public int selectedVramBank() {
        return selectedVramBank;
    }

    public int lcdc() {
        return lcdc;
    }

    public int bgp() {
        return bgp;
    }

    public int obp0() {
        return obp0;
    }

    public int obp1() {
        return obp1;
    }

    /** Raw FF68 value, or -1 on DMG hardware. */
    public int bgPaletteIndex() {
        return bgPaletteIndex;
    }

    /** Raw FF6A value, or -1 on DMG hardware. */
    public int objectPaletteIndex() {
        return objectPaletteIndex;
    }

    public DebugByteData vramBank0() {
        return vramBank0;
    }

    public DebugByteData vramBank1() {
        return vramBank1;
    }

    public DebugByteData oam() {
        return oam;
    }

    /** Little-endian RGB555 bytes in palette/color order. */
    public DebugByteData cgbBackgroundPalette() {
        return cgbBackgroundPalette;
    }

    /** Little-endian RGB555 bytes in palette/color order. */
    public DebugByteData cgbObjectPalette() {
        return cgbObjectPalette;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DebugGraphicsInspection that)) return false;
        return selectedVramBank == that.selectedVramBank
                && lcdc == that.lcdc
                && bgp == that.bgp
                && obp0 == that.obp0
                && obp1 == that.obp1
                && bgPaletteIndex == that.bgPaletteIndex
                && objectPaletteIndex == that.objectPaletteIndex
                && hardwareMode == that.hardwareMode
                && vramBank0.equals(that.vramBank0)
                && vramBank1.equals(that.vramBank1)
                && oam.equals(that.oam)
                && cgbBackgroundPalette.equals(that.cgbBackgroundPalette)
                && cgbObjectPalette.equals(that.cgbObjectPalette);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(hardwareMode, selectedVramBank, lcdc, bgp, obp0, obp1,
                bgPaletteIndex, objectPaletteIndex);
        result = 31 * result + vramBank0.hashCode();
        result = 31 * result + vramBank1.hashCode();
        result = 31 * result + oam.hashCode();
        result = 31 * result + cgbBackgroundPalette.hashCode();
        return 31 * result + cgbObjectPalette.hashCode();
    }

    @Override
    public String toString() {
        return "DebugGraphicsInspection[hardwareMode=" + hardwareMode
                + ", selectedVramBank=" + selectedVramBank + "]";
    }
}
