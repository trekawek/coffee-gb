package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.rumble.CodeBreakerRumble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.core.cpu.BitUtils.checkByteArgument;
import static eu.rekawek.coffeegb.core.cpu.BitUtils.checkWordArgument;

public class Mmu implements AddressSpace, StatefulComponent<Mmu>, PerformanceRomAccessProvider {

    private static final Logger LOG = LoggerFactory.getLogger(Mmu.class);

    private static final AddressSpace VOID = new Void();

    private final List<AddressSpace> spaces = new ArrayList<>();

    private final Ram ramC000 = new Ram(0xc000, 0x1000);

    private final Ram ramD000 = new Ram(0xd000, 0x1000);

    private final Ram ramFF80 = new Ram(0xff80, 0x7f);

    private final GbcRam gbcRam = new GbcRam();

    private final boolean gbc;

    private final OamEchoRam oamEchoRam;

    public void setSpeedMode(eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode) {
        gbcRam.setSpeedMode(speedMode);
        undocumentedGbcRegisters.setSpeedMode(speedMode);
    }

    public void setGpu(Gpu gpu) {
        oamEchoRam.setGpu(gpu);
    }

    private final UndocumentedGbcRegisters undocumentedGbcRegisters = new UndocumentedGbcRegisters();

    private AddressSpace[] addressToSpace;

    /** Derived once with the address-space index; null unless one provider owns all CPU ROM. */
    private PerformanceRomAccessProvider performanceRomAccessProvider;

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
        this(gbc, false);
    }

    public Mmu(boolean gbc, boolean cgb0Revision) {
        this.gbc = gbc;
        oamEchoRam = new OamEchoRam(gbc, cgb0Revision);
        // WRAM powers up with garbage, and neither boot ROM clears it. Games with
        // lazily-seeded random generators rely on that: Minesweeper for 'Windows'
        // spins forever placing mines when its LFSR seed area reads all zeros
        // (issue #48). A fixed seed keeps runs reproducible and netplay peers
        // identical.
        java.util.Random garbage = new java.util.Random(0xC0FFEE);
        fillWithGarbage(ramC000, 0xc000, 0x1000, garbage);
        fillWithGarbage(ramD000, 0xd000, 0x1000, garbage);
        gbcRam.fillWithGarbage(garbage);
        if (!gbc) {
            initializeDmgHighWramPowerOnPattern();
        }
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
        addAddressSpace(oamEchoRam);
    }

    private static void fillWithGarbage(Ram ram, int offset, int length, java.util.Random garbage) {
        for (int i = 0; i < length; i++) {
            ram.setByte(offset + i, garbage.nextInt(0x100));
        }
    }

    /**
     * The upper two pages of bank 1 have a strong, repeatable bias on DMG-CPU B:
     * the $DE page powers up high and the $DF page low (with occasional individual
     * bit decay). Preserve that model-wide power-on characteristic instead of using
     * synthetic random bytes there. Besides being closer to the measured DMG-08 WRAM
     * dump, it matters when the partially decoded $FE/$FF OAM-DMA source aliases these
     * physical WRAM pages.
     */
    private void initializeDmgHighWramPowerOnPattern() {
        for (int address = 0xde00; address < 0xdf00; address++) {
            ramD000.setByte(address, 0xff);
        }
        for (int address = 0xdf00; address < 0xe000; address++) {
            ramD000.setByte(address, 0x00);
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
        AddressSpace romWindow = addressToSpace[0x0000];
        for (int address = 0x0001; address < 0x8000; address++) {
            if (addressToSpace[address] != romWindow) {
                performanceRomAccessProvider = null;
                return;
            }
        }
        performanceRomAccessProvider = romWindow instanceof PerformanceRomAccessProvider provider
                ? provider : null;
    }

    @Override
    public boolean accepts(int address) {
        return true;
    }

    @Override
    public void setByte(int address, int value) {
        setByte(address, value, false);
    }

    @Override
    public void setByteFromCpu(int address, int value) {
        setByte(address, value, true);
    }

    private void setByte(int address, int value, boolean fromCpu) {
        checkByteArgument("value", value);
        checkWordArgument("address", address);
        if (busListener != null && address >= 0xc000 && address < 0xe000) {
            busListener.onHighBusWrite();
        }
        AddressSpace space = getSpace(address);
        if (fromCpu) {
            space.setByteFromCpu(address, value);
        } else {
            space.setByte(address, value);
        }
        if (codeBreakerRumble != null && address == 0xfffe) {
            codeBreakerRumble.onHramWrite(value);
        }
    }

    @Override
    public int getByte(int address) {
        checkWordArgument("address", address);
        return getSpace(address).getByte(address);
    }

    @Override
    public PerformanceRomAccess acquirePerformanceRomAccess() {
        return performanceRomAccessProvider == null
                ? null : performanceRomAccessProvider.acquirePerformanceRomAccess();
    }

    /** Returns the owner-held SVBK value without routing a read through the MMU bus. */
    public int getDebugSvbk() {
        return gbcRam.getByte(GbcRam.SVBK);
    }

    /** Returns one owner-held undocumented CGB register without MMU address dispatch. */
    public int getDebugUndocumentedGbcRegister(int address) {
        if (!undocumentedGbcRegisters.accepts(address)) {
            throw new IllegalArgumentException("Not an undocumented CGB register: "
                    + Integer.toHexString(address));
        }
        return undocumentedGbcRegisters.getByte(address);
    }

    /**
     * Copies debugger-visible memory without entering the emulated CPU bus. Only RAM owned by
     * this MMU is exposed: work RAM, its E000-FDFF echo, and high RAM. Cartridge, VRAM, OAM and
     * memory-mapped I/O reads are deliberately rejected because an ordinary read may have
     * hardware side effects.
     */
    public byte[] readDebugMemory(int address, int length) {
        validateDebugMemoryRange(address, length);
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) getDebugMemoryByte(address + i);
        }
        return bytes;
    }

    /** Writes directly to debugger-visible RAM without entering the emulated CPU bus. */
    public void writeDebugMemory(int address, int value) {
        checkByteArgument("value", value);
        validateDebugMemoryRange(address, 1);
        setDebugMemoryByte(address, value);
    }

    private void validateDebugMemoryRange(int address, int length) {
        if (address < 0 || address > 0xffff) {
            throw new IllegalArgumentException("Invalid debug memory address: "
                    + Integer.toHexString(address));
        }
        if (length < 0 || (long) address + length > 0x10000L) {
            throw new IllegalArgumentException("Invalid debug memory length: " + length);
        }
        if (!isDebugMemoryAddress(address)) {
            throw new IllegalArgumentException("Debug memory address is not side-effect-free: "
                    + Integer.toHexString(address));
        }
        for (int i = 1; i < length; i++) {
            if (!isDebugMemoryAddress(address + i)) {
                throw new IllegalArgumentException("Debug memory address is not side-effect-free: "
                        + Integer.toHexString(address + i));
            }
        }
    }

    private static boolean isDebugMemoryAddress(int address) {
        return address >= 0xc000 && address < 0xfe00
                || address >= 0xff80 && address < 0xffff;
    }

    private int getDebugMemoryByte(int address) {
        if (address >= 0xe000 && address < 0xfe00) {
            address -= 0x2000;
        }
        if (address >= 0xc000 && address < 0xd000) {
            return ramC000.getByte(address);
        }
        if (address >= 0xd000 && address < 0xe000) {
            return gbc ? gbcRam.getByte(address) : ramD000.getByte(address);
        }
        if (address >= 0xff80 && address < 0xffff) {
            return ramFF80.getByte(address);
        }
        throw new IllegalArgumentException("Debug memory address is not side-effect-free: "
                + Integer.toHexString(address));
    }

    private void setDebugMemoryByte(int address, int value) {
        if (address >= 0xe000 && address < 0xfe00) {
            address -= 0x2000;
        }
        if (address >= 0xc000 && address < 0xd000) {
            ramC000.setByte(address, value);
            return;
        }
        if (address >= 0xd000 && address < 0xe000) {
            if (gbc) {
                gbcRam.setByte(address, value);
            } else {
                ramD000.setByte(address, value);
            }
            return;
        }
        if (address >= 0xff80 && address < 0xffff) {
            ramFF80.setByte(address, value);
            return;
        }
        throw new IllegalArgumentException("Debug memory address is not side-effect-free: "
                + Integer.toHexString(address));
    }

    private AddressSpace getSpace(int address) {
        if (addressToSpace == null) {
            throw new IllegalStateException("Address spaces hasn't been indexed yet");
        }
        return addressToSpace[address];
    }

    private static class Void implements AddressSpace {
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
    public ComponentState<Mmu> captureState() {
        return new MmuState(ramC000.captureState(), ramD000.captureState(), ramFF80.captureState(),
                gbcRam.captureState(), undocumentedGbcRegisters.captureState(),
                oamEchoRam.captureState());
    }

    @Override
    public ComponentState<Mmu> captureState(MachineStateCapture capture) {
        return new MmuState(
                ramC000.captureState(capture),
                ramD000.captureState(capture),
                ramFF80.captureState(capture),
                gbcRam.captureState(capture),
                undocumentedGbcRegisters.captureState(capture),
                oamEchoRam.captureState(capture));
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        ramC000.declareMachineStatePayloads(capture);
        ramD000.declareMachineStatePayloads(capture);
        ramFF80.declareMachineStatePayloads(capture);
        gbcRam.declareMachineStatePayloads(capture);
    }

    @Override
    public void restoreState(ComponentState<Mmu> state) {
        if (!(state instanceof MmuState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.ramC000.restoreState(mem.ramC000Memento);
        this.ramD000.restoreState(mem.ramD000Memento);
        this.ramFF80.restoreState(mem.ramFF80Memento);
        this.gbcRam.restoreState(mem.gbcRamMemento);
        this.undocumentedGbcRegisters.restoreState(mem.undocumentedGbcRegistersMemento);
        this.oamEchoRam.restoreState(mem.oamEchoRamMemento);
    }

    private record MmuState(ComponentState<Ram> ramC000Memento, ComponentState<Ram> ramD000Memento, ComponentState<Ram> ramFF80Memento,
                              ComponentState<GbcRam> gbcRamMemento,
                              ComponentState<UndocumentedGbcRegisters> undocumentedGbcRegistersMemento,
                              ComponentState<OamEchoRam> oamEchoRamMemento
    ) implements ComponentState<Mmu> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record MmuMemento(Memento<Ram> ramC000Memento, Memento<Ram> ramD000Memento, Memento<Ram> ramFF80Memento,
                              Memento<GbcRam> gbcRamMemento,
                              Memento<UndocumentedGbcRegisters> undocumentedGbcRegistersMemento,
                              Memento<OamEchoRam> oamEchoRamMemento
    ) implements Memento<Mmu> {
    }
}
