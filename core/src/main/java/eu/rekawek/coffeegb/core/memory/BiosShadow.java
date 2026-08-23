package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;

public class BiosShadow implements AddressSpace, StatefulComponent<BiosShadow>,
        PerformanceRomAccessProvider {

    private final Bios bios;

    private final Cartridge cartridge;

    private boolean isEnabled = true;

    /**
     * Constructs the boot-ROM overlay and its sticky FF50 disable latch.
     *
     * <p>On DMG, {@code dmg_cpu_b/sys_decode.kicad_sch} nodes SATO, TEPU, and TUGE implement
     * {@code TEPU.D = TEPU.Q | D0} on an FF50 write. Consequently only a set D0 can disable the
     * boot ROM and the disable remains sticky. SameBoy independently applies the same D0 rule to
     * CGB hardware.
     */
    public BiosShadow(Bios bios, Cartridge cartridge) {
        this.bios = bios;
        this.cartridge = cartridge;
    }

    public boolean isBootFinished() {
        return !isEnabled;
    }

    @Override
    public boolean accepts(int address) {
        if (address == 0xff50) {
            return true;
        }
        return bios.accepts(address) || cartridge.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        if (address == 0xff50) {
            if ((value & 0x01) != 0) {
                isEnabled = false;
            }
        } else if (cartridge.accepts(address)) {
            cartridge.setByte(address, value);
        }
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff50) {
            return isEnabled ? 0xfe : 0xff;
        } else if (isEnabled && bios.accepts(address)) {
            return bios.getByte(address);
        } else {
            return cartridge.getByte(address);
        }
    }

    @Override
    public PerformanceRomAccess acquirePerformanceRomAccess() {
        return isEnabled ? null : cartridge.acquirePerformanceRomAccess();
    }

    @Override
    public ComponentState<BiosShadow> captureState() {
        return new BiosShadowState(isEnabled);
    }

    @Override
    public void restoreState(ComponentState<BiosShadow> state) {
        if (!(state instanceof BiosShadowState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.isEnabled = mem.isEnabled;
    }

    private record BiosShadowState(boolean isEnabled) implements ComponentState<BiosShadow> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record BiosShadowMemento(boolean isEnabled) implements Memento<BiosShadow> {
    }
}
