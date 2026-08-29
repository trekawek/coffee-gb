package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.memory.BiosShadow;

public class SpeedMode implements AddressSpace, StatefulComponent<SpeedMode> {

    private final boolean gbc;

    private final boolean allowLegacySpeedSwitch;

    private boolean currentSpeed;

    private boolean prepareSpeedSwitch;

    // KEY0 (FF4C): the boot ROM switches the CGB into DMG compatibility mode for
    // non-color cartridges; CGB-only registers read FF afterwards (boot_hwio-C)
    private boolean dmgCompat;

    private BiosShadow biosShadow;

    private transient Runnable timingStateListener;

    public SpeedMode(boolean gbc) {
        this(gbc, false);
    }

    public SpeedMode(boolean gbc, boolean allowLegacySpeedSwitch) {
        this.gbc = gbc;
        this.allowLegacySpeedSwitch = allowLegacySpeedSwitch;
    }

    public void setBiosShadow(BiosShadow biosShadow) {
        this.biosShadow = biosShadow;
    }

    public void setDmgCompat(boolean dmgCompat) {
        this.dmgCompat = dmgCompat;
        notifyTimingStateChanged();
    }

    /** Installs the owner-thread observer used by timing consumers to refresh cached state. */
    public void setTimingStateListener(Runnable listener) {
        timingStateListener = listener;
    }

    private void notifyTimingStateChanged() {
        if (timingStateListener != null) {
            timingStateListener.run();
        }
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
                boolean newDmgCompat = (value & 0x0c) != 0;
                if (dmgCompat != newDmgCompat) {
                    dmgCompat = newDmgCompat;
                    notifyTimingStateChanged();
                }
            }
        } else if (isSpeedSwitchAccessible()) {
            prepareSpeedSwitch = (value & 0x01) != 0;
        }
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff4c) {
            return 0xff;
        }
        if (isSpeedSwitchAccessible()) {
            return (currentSpeed ? (1 << 7) : 0) | (prepareSpeedSwitch ? (1 << 0) : 0) | 0b01111110;
        } else {
            return 0xff;
        }
    }

    private boolean isSpeedSwitchAccessible() {
        return allowLegacySpeedSwitch || (gbc && !dmgCompat);
    }

    boolean onStop() {
        if (prepareSpeedSwitch) {
            currentSpeed = !currentSpeed;
            prepareSpeedSwitch = false;
            notifyTimingStateChanged();
            return true;
        } else {
            return false;
        }
    }

    public int getSpeedMode() {
        return currentSpeed ? 2 : 1;
    }

    public boolean isGbc() {
        return gbc;
    }

    /** Allocation-free scalar comparison for the owning Gameboy's link timing check. */
    public boolean hasSameTimingState(SpeedMode other) {
        return other != null
                && gbc == other.gbc
                && allowLegacySpeedSwitch == other.allowLegacySpeedSwitch
                && currentSpeed == other.currentSpeed
                && prepareSpeedSwitch == other.prepareSpeedSwitch
                && dmgCompat == other.dmgCompat;
    }

    @Override
    public ComponentState<SpeedMode> captureState() {
        return new SpeedModeState(currentSpeed, prepareSpeedSwitch, dmgCompat);
    }

    @Override
    public void restoreState(ComponentState<SpeedMode> state) {
        if (!(state instanceof SpeedModeState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.currentSpeed = mem.currentSpeed;
        this.prepareSpeedSwitch = mem.prepareSpeedSwitch;
        this.dmgCompat = mem.dmgCompat;
        notifyTimingStateChanged();
    }

    private record SpeedModeState(boolean currentSpeed, boolean prepareSpeedSwitch, boolean dmgCompat)
            implements ComponentState<SpeedMode> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record SpeedModeMomento(boolean currentSpeed, boolean prepareSpeedSwitch, boolean dmgCompat)
            implements Memento<SpeedMode> {
    }
}
