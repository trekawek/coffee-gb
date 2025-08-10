package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import eu.rekawek.coffeegb.memory.cart.Cartridge;

public class BiosShadow implements AddressSpace, Originator<BiosShadow> {

    private final Bios bios;

    private final Cartridge cartridge;

    private boolean isEnabled = true;

    public BiosShadow(Bios bios, Cartridge cartridge) {
        this.bios = bios;
        this.cartridge = cartridge;
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
    public Memento<BiosShadow> saveToMemento() {
        return new BiosShadowMemento(isEnabled);
    }

    @Override
    public void restoreFromMemento(Memento<BiosShadow> memento) {
        if (!(memento instanceof BiosShadowMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.isEnabled = mem.isEnabled;
    }

    private record BiosShadowMemento(boolean isEnabled) implements Memento<BiosShadow> {
    }
}
