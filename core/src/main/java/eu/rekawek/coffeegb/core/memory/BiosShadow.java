package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;

public class BiosShadow implements AddressSpace, StatefulComponent<BiosShadow> {

    private final Bios bios;

    private final Cartridge cartridge;

    private boolean isEnabled = true;

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
            isEnabled = false;
        } else if (cartridge.accepts(address)) {
            cartridge.setByte(address, value);
        }
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff50) {
            return 0xff;
        } else if (isEnabled && bios.accepts(address)) {
            return bios.getByte(address);
        } else {
            return cartridge.getByte(address);
        }
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
