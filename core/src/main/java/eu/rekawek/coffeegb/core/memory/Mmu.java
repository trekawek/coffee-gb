package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.rumble.CodeBreakerRumble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.core.cpu.BitUtils.checkByteArgument;
import static eu.rekawek.coffeegb.core.cpu.BitUtils.checkWordArgument;

public class Mmu implements AddressSpace, Serializable, Originator<Mmu> {

    private static final Logger LOG = LoggerFactory.getLogger(Mmu.class);

    private static final AddressSpace VOID = new Void();

    private final List<AddressSpace> spaces = new ArrayList<>();

    private final Ram ramC000 = new Ram(0xc000, 0x1000);

    private final Ram ramD000 = new Ram(0xd000, 0x1000);

    private final Ram ramFF80 = new Ram(0xff80, 0x7f);

    private final GbcRam gbcRam = new GbcRam();

    public void setSpeedMode(eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode) {
        gbcRam.setSpeedMode(speedMode);
        undocumentedGbcRegisters.setSpeedMode(speedMode);
    }

    private final UndocumentedGbcRegisters undocumentedGbcRegisters = new UndocumentedGbcRegisters();

    private AddressSpace[] addressToSpace;

    // the Sachen MMC2 cart watches pre-header WRAM writes to tell a CGB boot from a DMG
    // one; null for every other cartridge (see SachenMmc)
    private transient eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc busListener;

    // the CodeBreaker pass-through cartridge watches the console bus for writes to the
    // last byte of HRAM, using bit 7 as its built-in motor line
    private transient CodeBreakerRumble codeBreakerRumble;

    public void setBusListener(eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc listener) {
        this.busListener = listener;
    }

    public void setCodeBreakerRumble(CodeBreakerRumble codeBreakerRumble) {
        this.codeBreakerRumble = codeBreakerRumble;
    }

    public Mmu(boolean gbc) {
        // WRAM powers up with garbage, and neither boot ROM clears it. Games with
        // lazily-seeded random generators rely on that: Minesweeper for 'Windows'
        // spins forever placing mines when its LFSR seed area reads all zeros
        // (issue #48). A fixed seed keeps runs reproducible and netplay peers
        // identical.
        java.util.Random garbage = new java.util.Random(0xC0FFEE);
        fillWithGarbage(ramC000, 0xc000, 0x1000, garbage);
        fillWithGarbage(ramD000, 0xd000, 0x1000, garbage);
        gbcRam.fillWithGarbage(garbage);
        // The fixed garbage pattern must still contain zero runs. Older GBDK font
        // code uses this block as a lazy-init sentinel (issue #111), while the
        // following block remains nonzero for Minesweeper's seed (issue #48).
        for (int address = 0xc0f8; address < 0xc100; address++) {
            ramC000.setByte(address, 0);
        }

        addAddressSpace(ramC000);
        if (gbc) {
            addAddressSpace(gbcRam);
            addAddressSpace(undocumentedGbcRegisters);
        } else {
            addAddressSpace(ramD000);
        }
        addAddressSpace(ramFF80);
        addAddressSpace(new ShadowAddressSpace(this, 0xe000, 0xc000, 0x1e00));
    }

    private static void fillWithGarbage(Ram ram, int offset, int length, java.util.Random garbage) {
        for (int i = 0; i < length; i++) {
            ram.setByte(offset + i, garbage.nextInt(0x100));
        }
    }

    public void addAddressSpace(AddressSpace space) {
        spaces.add(space);
    }

    public void indexSpaces() {
        addressToSpace = new AddressSpace[0x10000];
        for (int i = 0; i < addressToSpace.length; i++) {
            addressToSpace[i] = VOID;
            for (AddressSpace s : spaces) {
                if (s.accepts(i)) {
                    addressToSpace[i] = s;
                    break;
                }
            }
        }
    }

    @Override
    public boolean accepts(int address) {
        return true;
    }

    @Override
    public void setByte(int address, int value) {
        checkByteArgument("value", value);
        checkWordArgument("address", address);
        if (busListener != null && address >= 0xc000 && address < 0xe000) {
            busListener.onHighBusWrite();
        }
        getSpace(address).setByte(address, value);
        if (codeBreakerRumble != null && address == 0xfffe) {
            codeBreakerRumble.onHramWrite(value);
        }
    }

    @Override
    public int getByte(int address) {
        checkWordArgument("address", address);
        return getSpace(address).getByte(address);
    }

    private AddressSpace getSpace(int address) {
        if (addressToSpace == null) {
            throw new IllegalStateException("Address spaces hasn't been indexed yet");
        }
        return addressToSpace[address];
    }

    private static class Void implements AddressSpace, Serializable {
        @Override
        public boolean accepts(int address) {
            return true;
        }

        @Override
        public void setByte(int address, int value) {
            if (address < 0 || address > 0xffff) {
                throw new IllegalArgumentException("Invalid address: " + Integer.toHexString(address));
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "Writing value {} to void address {}",
                        Integer.toHexString(value),
                        Integer.toHexString(address));
            }
        }

        @Override
        public int getByte(int address) {
            if (address < 0 || address > 0xffff) {
                throw new IllegalArgumentException("Invalid address: " + Integer.toHexString(address));
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Reading value from void address {}", Integer.toHexString(address));
            }
            return 0xff;
        }
    }

    @Override
    public Memento<Mmu> saveToMemento() {
        return new MmuMemento(ramC000.saveToMemento(), ramD000.saveToMemento(), ramFF80.saveToMemento(), gbcRam.saveToMemento(), undocumentedGbcRegisters.saveToMemento());
    }

    @Override
    public void restoreFromMemento(Memento<Mmu> memento) {
        if (!(memento instanceof MmuMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.ramC000.restoreFromMemento(mem.ramC000Memento);
        this.ramD000.restoreFromMemento(mem.ramD000Memento);
        this.ramFF80.restoreFromMemento(mem.ramFF80Memento);
        this.gbcRam.restoreFromMemento(mem.gbcRamMemento);
        this.undocumentedGbcRegisters.restoreFromMemento(mem.undocumentedGbcRegistersMemento);
    }

    private record MmuMemento(Memento<Ram> ramC000Memento, Memento<Ram> ramD000Memento, Memento<Ram> ramFF80Memento,
                              Memento<GbcRam> gbcRamMemento,
                              Memento<UndocumentedGbcRegisters> undocumentedGbcRegistersMemento
    ) implements Memento<Mmu> {
    }
}
