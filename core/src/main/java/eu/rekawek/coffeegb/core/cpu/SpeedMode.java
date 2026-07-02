package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.BiosShadow;

import java.io.Serializable;

public class SpeedMode implements AddressSpace, Serializable, Originator<SpeedMode> {

    private final boolean gbc;

    private boolean currentSpeed;

    private boolean prepareSpeedSwitch;

    // KEY0 (FF4C): the boot ROM switches the CGB into DMG compatibility mode for
    // non-color cartridges; CGB-only registers read FF afterwards (boot_hwio-C)
    private boolean dmgCompat;

    private BiosShadow biosShadow;

    public SpeedMode(boolean gbc) {
        this.gbc = gbc;
    }

    public void setBiosShadow(BiosShadow biosShadow) {
        this.biosShadow = biosShadow;
    }

    public void setDmgCompat(boolean dmgCompat) {
        this.dmgCompat = dmgCompat;
    }

    public boolean isDmgCompat() {
        return dmgCompat;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff4c || address == 0xff4d;
    }

    @Override
    public void setByte(int address, int value) {
        if (address == 0xff4c) {
            if (biosShadow == null || !biosShadow.isBootFinished()) {
                dmgCompat = (value & 0x0c) != 0;
            }
        } else if (!dmgCompat) {
            prepareSpeedSwitch = (value & 0x01) != 0;
        }
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff4c) {
            return 0xff;
        }
        if (gbc && !dmgCompat) {
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
        return new SpeedModeMomento(currentSpeed, prepareSpeedSwitch, dmgCompat);
    }

    @Override
    public void restoreFromMemento(Memento<SpeedMode> memento) {
        if (!(memento instanceof SpeedModeMomento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.currentSpeed = mem.currentSpeed;
        this.prepareSpeedSwitch = mem.prepareSpeedSwitch;
        this.dmgCompat = mem.dmgCompat;
    }

    private record SpeedModeMomento(boolean currentSpeed, boolean prepareSpeedSwitch, boolean dmgCompat)
            implements Memento<SpeedMode> {
    }
}
