package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;

public class SpeedMode implements AddressSpace {

    private boolean currentSpeed;

    private boolean prepareSpeedSwitch;

    @Override
    public boolean accepts(int address) {
        return address == 0xff4d;
    }

    @Override
    public void setByte(int address, int value) {
        prepareSpeedSwitch = (value & 0x01) != 0;
    }

    @Override
    public int getByte(int address) {
        return (currentSpeed ? (1 << 7) : 0) | (prepareSpeedSwitch ? (1 << 0) : 0) | 0b01111110;
    }

    boolean onStop() {
        if (prepareSpeedSwitch) {
            currentSpeed = !currentSpeed;
            prepareSpeedSwitch = false;
            return true;
        } else {
            return false;
        }
    }

    public int getSpeedMode() {
        return currentSpeed ? 2 : 1;
    }
}
