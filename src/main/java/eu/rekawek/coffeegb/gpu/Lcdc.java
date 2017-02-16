package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;

import static com.google.common.base.Preconditions.checkArgument;

public class Lcdc implements AddressSpace {

    private int value = 0x91;

    public boolean isBgAndWindowDisplay() {
        return (value & 0x01) != 0;
    }

    public boolean isObjDisplay() {
        return (value & 0x02) != 0;
    }

    public int getSpriteHeight() {
        return (value & 0x04) == 0 ? 8 : 16;
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
        this.value = value;
    }

    @Override
    public int getByte(int address) {
        checkArgument(address == 0xff40);
        return value;
    }

    public void set(int value) {
        this.value = value;
    }

    public int get() {
        return value;
    }
}
