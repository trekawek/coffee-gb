package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

import java.io.Serializable;

public class SpeedMode implements AddressSpace, Serializable, Originator<SpeedMode> {

    private final boolean gbc;

    private boolean currentSpeed;

    private boolean prepareSpeedSwitch;

    public SpeedMode(boolean gbc) {
        this.gbc = gbc;
    }

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
        if (gbc) {
            return (currentSpeed ? (1 << 7) : 0) | (prepareSpeedSwitch ? (1 << 0) : 0) | 0b01111110;
        } else {
            return 0xff;
        }
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

    @Override
    public Memento<SpeedMode> saveToMemento() {
        return new SpeedModeMomento(currentSpeed, prepareSpeedSwitch);
    }

    @Override
    public void restoreFromMemento(Memento<SpeedMode> memento) {
        if (!(memento instanceof SpeedModeMomento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.currentSpeed = mem.currentSpeed;
        this.prepareSpeedSwitch = mem.prepareSpeedSwitch;
    }

    private record SpeedModeMomento(boolean currentSpeed, boolean prepareSpeedSwitch) implements Memento<SpeedMode> {
    }
}
