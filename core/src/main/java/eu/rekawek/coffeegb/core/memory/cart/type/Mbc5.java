package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.trace.MapperRtcTrace;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;

import java.util.Arrays;

public class Mbc5 implements MemoryController {

    private final int romBanks;

    private final int ramBanks;

    private final int[] cartridge;

    private final int[] ram;

    private final Battery battery;

    private int selectedRamBank;

    private int selectedRomBank = 1;

    private boolean ramWriteEnabled;

    private boolean ramUpdated;

    // rumble carts (types 0x1C-0x1E) wire bit 3 of the RAM-bank register to the motor,
    // leaving bits 0-2 for the bank select
    private final boolean rumble;

    private boolean motorOn;

    private transient EventBus eventBus;

    // Non-battery cart RAM is volatile scratch with no save to protect, so the RAM-enable
    // handshake serves no purpose there. Some homebrew built for flash carts (Bung/EMS, whose
    // SRAM is always accessible) use it - e.g. as a stack - without ever enabling it, and would
    // otherwise crash (Green Beret PD, #65). Battery carts keep the gate so a defensive write
    // to disabled RAM can't corrupt the save.
    private final boolean gateRamWrites;

    private transient DebugHooks debugHooks;

    public Mbc5(Rom rom, Battery battery) {
        this.cartridge = rom.getRom();
        this.romBanks = rom.getRomBanks();
        this.ramBanks = rom.getRamBanks();
        this.ram = new int[0x2000 * Math.max(this.ramBanks, 1)];
        Arrays.fill(ram, 0xff);
        this.battery = battery;
        this.gateRamWrites = rom.getType().isBattery();
        this.rumble = rom.getType().isRumble();
        battery.loadRam(ram);
    }

    @Override
    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        DebugHooks hooks = debugHooks;
        int previousRomBank = hooks == null ? -1 : selectedRomBank % romBanks;
        int previousRamBank = hooks == null ? -1 : selectedRamBank;
        boolean previousRamEnabled = ramWriteEnabled;
        if (address >= 0x0000 && address < 0x2000) {
            ramWriteEnabled = (value & 0b1010) != 0;
        } else if (address >= 0x2000 && address < 0x3000) {
            selectedRomBank = (selectedRomBank & 0x100) | value;
        } else if (address >= 0x3000 && address < 0x4000) {
            selectedRomBank = (selectedRomBank & 0x0ff) | ((value & 1) << 8);
        } else if (address >= 0x4000 && address < 0x6000) {
            if (rumble) {
                boolean on = (value & 0x08) != 0;
                if (on != motorOn) {
                    motorOn = on;
                    if (eventBus != null) {
                        eventBus.post(new RumbleEvent(on));
                    }
                }
            }
            int bank = value & (rumble ? 0x07 : 0x0f);
            if (bank < ramBanks) {
                selectedRamBank = bank;
            }
        } else if (address >= 0xa000 && address < 0xc000 && (ramWriteEnabled || !gateRamWrites)) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                ram[ramAddress] = value;
                ramUpdated = true;
            }
        }
        if (hooks != null) {
            int romBank = selectedRomBank % romBanks;
            if (romBank != previousRomBank) {
                hooks.onMapperRtcEvent(
                        MapperRtcTrace.Kind.ROM_BANK_CHANGED, -1, romBank);
            }
            if (selectedRamBank != previousRamBank) {
                hooks.onMapperRtcEvent(
                        MapperRtcTrace.Kind.RAM_BANK_CHANGED, -1, selectedRamBank);
            }
            if (ramWriteEnabled != previousRamEnabled) {
                hooks.onMapperRtcEvent(
                        MapperRtcTrace.Kind.RAM_ENABLE_CHANGED,
                        -1,
                        ramWriteEnabled ? 1 : 0);
            }
        }
    }

    @Override
    public void setDebugHooks(DebugHooks hooks) {
        debugHooks = hooks;
    }

    @Override
    public boolean isRumbleActive() {
        return motorOn;
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(getRomBankFor0x0000(), address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(getRomBankFor0x4000(), address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                return ram[ramAddress];
            } else {
                return 0xff;
            }
        } else {
            throw new IllegalArgumentException(Integer.toHexString(address));
        }
    }

    @Override
    public void flushRam() {
        if (ramUpdated) {
            battery.saveRam(ram);
            battery.flush();
        }
    }

    /** Hook for MBC5-derived boards that can remap the normally fixed ROM window. */
    protected int getRomBankFor0x0000() {
        return 0;
    }

    /** Hook for MBC5-derived boards that offset the switchable ROM window. */
    protected int getRomBankFor0x4000() {
        return selectedRomBank % romBanks;
    }

    protected final int getSelectedRomBank() {
        return selectedRomBank;
    }

    protected final void setSelectedRomBank(int bank) {
        selectedRomBank = bank;
    }

    protected final int normalizeRomBank(int bank) {
        return Math.floorMod(bank, romBanks);
    }

    protected int getRomByte(int bank, int address) {
        int cartOffset = bank * 0x4000 + address;
        if (cartOffset < cartridge.length) {
            return cartridge[cartOffset];
        } else {
            return 0xff;
        }
    }

    private int getRamAddress(int address) {
        return selectedRamBank * 0x2000 + (address - 0xa000);
    }


    @Override
    public ComponentState<MemoryController> captureState() {
        return new Mbc5State(battery.captureState(), ram.clone(), selectedRamBank, selectedRomBank, ramWriteEnabled, ramUpdated, motorOn);
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new Mbc5State(
                battery.captureState(capture),
                capture.ints(ram),
                selectedRamBank,
                selectedRomBank,
                ramWriteEnabled,
                ramUpdated,
                motorOn);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        battery.declareMachineStatePayloads(capture);
        capture.declareInts(ram);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof Mbc5State mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (this.ram.length != mem.ram.length) {
            throw new IllegalArgumentException("ComponentState ram length doesn't match");
        }
        battery.restoreState(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.selectedRamBank = mem.selectedRamBank;
        this.selectedRomBank = mem.selectedRomBank;
        this.ramWriteEnabled = mem.ramWriteEnabled;
        this.ramUpdated = mem.ramUpdated;
        this.motorOn = mem.motorOn;
    }

    private record Mbc5State(ComponentState<Battery> batteryMemento, int[] ram, int selectedRamBank, int selectedRomBank,
                               boolean ramWriteEnabled, boolean ramUpdated,
                               boolean motorOn) implements ComponentState<MemoryController> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record Mbc5Memento(Memento<Battery> batteryMemento, int[] ram, int selectedRamBank, int selectedRomBank,
                               boolean ramWriteEnabled, boolean ramUpdated,
                               boolean motorOn) implements Memento<MemoryController> {
    }
}
