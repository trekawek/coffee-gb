package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;

public class Lcdc implements AddressSpace, Serializable, Originator<Lcdc> {

    private static final int OAM_SIZE_HISTORY_LENGTH = 8;

    private boolean gbc;

    private int value = 0x91;

    // DMG write conflict (mealybug m3_lcdc_*_change): during the T-cycle in which a CPU
    // write lands, the LCD output stage sees the old value with the new BG_EN bit OR-ed
    // in; the full new value settles one T-cycle later. -1 = no write this tick.
    private int mixValue = -1;

    private int pendingMixValue = -1;

    // LCDC.2 reaches the OAM reader through its own clock-domain latch. Keep this
    // history running for the complete scanline, including HBlank, because writes at
    // the end of one line can still be pending when entry 0 is read on the next line.
    private final int[] oamSizeHistory = new int[OAM_SIZE_HISTORY_LENGTH];

    public Lcdc() {
        Arrays.fill(oamSizeHistory, value);
    }

    public boolean isBgAndWindowDisplay() {
        return (value & 0x01) != 0;
    }

    public boolean isObjDisplay() {
        return (value & 0x02) != 0;
    }

    /** BG/window enable as seen by the LCD output stage (with the write-conflict mix). */
    public boolean isBgAndWindowDisplayEffective() {
        return ((mixValue >= 0 ? mixValue : value) & 0x01) != 0;
    }

    /** Object enable as seen by the LCD output stage (with the write-conflict mix). */
    public boolean isObjDisplayEffective() {
        return ((mixValue >= 0 ? mixValue : value) & 0x02) != 0;
    }

    /** Called once per GPU tick: the mix value lives for the single tick after the write. */
    void tickConflicts() {
        mixValue = pendingMixValue;
        pendingMixValue = -1;
        System.arraycopy(oamSizeHistory, 0, oamSizeHistory, 1,
                oamSizeHistory.length - 1);
        oamSizeHistory[0] = value;
    }

    public int getSpriteHeight() {
        return (value & 0x04) == 0 ? 8 : 16;
    }

    public int getOamSpriteHeight(int dotsAgo) {
        checkArgument(dotsAgo >= 0 && dotsAgo < oamSizeHistory.length);
        return (oamSizeHistory[dotsAgo] & 0x04) == 0 ? 8 : 16;
    }

    public int getBgTileMapDisplay() {
        return (value & 0x08) == 0 ? 0x9800 : 0x9c00;
    }

    public int getBgWindowTileData() {
        return (value & 0x10) == 0 ? 0x9000 : 0x8000;
    }

    public boolean isBgWindowTileDataSigned() {
        return (value & 0x10) == 0;
    }

    public boolean isWindowDisplay() {
        return (value & 0x20) != 0;
    }

    public int getWindowTileMapDisplay() {
        return (value & 0x40) == 0 ? 0x9800 : 0x9c00;
    }

    public boolean isLcdEnabled() {
        return (value & 0x80) != 0;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff40;
    }

    @Override
    public void setByte(int address, int value) {
        checkArgument(address == 0xff40);
        set(value);
    }

    @Override
    public int getByte(int address) {
        checkArgument(address == 0xff40);
        return value;
    }

    public void set(int value) {
        set(value, false);
    }

    /**
     * @param dropObjEnInMix DMG special case: when objects are being disabled while an
     *     object fetch is in progress or at position 0, the OBJ_EN bit turns off already
     *     in the conflict-mix T-cycle instead of one T-cycle later
     */
    public void set(int value, boolean dropObjEnInMix) {
        if (gbc) {
            // the CGB applies LCDC writes cleanly, without the DMG's conflict mix
            this.value = value;
            return;
        }
        int mix = this.value | (value & 0x01);
        if (dropObjEnInMix) {
            mix &= ~0x02;
        }
        pendingMixValue = mix;
        this.value = value;
    }


    public void setGbc(boolean gbc) {
        this.gbc = gbc;
    }

    public int get() {
        return value;
    }

    @Override
    public Memento<Lcdc> saveToMemento() {
        return new LcdcMemento(value, mixValue, pendingMixValue, oamSizeHistory.clone());
    }

    @Override
    public void restoreFromMemento(Memento<Lcdc> memento) {
        if (!(memento instanceof LcdcMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.value = mem.value;
        this.mixValue = mem.mixValue;
        this.pendingMixValue = mem.pendingMixValue;
        if (mem.oamSizeHistory.length != oamSizeHistory.length) {
            throw new IllegalArgumentException("Memento OAM-size history length doesn't match");
        }
        System.arraycopy(mem.oamSizeHistory, 0, oamSizeHistory, 0, oamSizeHistory.length);
    }

    private record LcdcMemento(
            int value, int mixValue, int pendingMixValue, int[] oamSizeHistory)
            implements Memento<Lcdc> {
    }
}
