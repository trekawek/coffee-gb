package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;

import java.util.Arrays;

/** Future Console Design's Xploder GB pass-through cheat cartridge. */
public class XploderGb implements MemoryController {

    private static final int RAM_BANK_SIZE = 0x2000;

    private static final int RAM_BANKS = 16;

    private final int[] rom;

    private final int romBanks;

    private final int[] ram = new int[RAM_BANKS * RAM_BANK_SIZE];

    private final Battery battery;

    private int selectedRomBank = 1;

    private int selectedRamBank;

    private boolean ramUpdated;

    public XploderGb(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.romBanks = Math.max(1, (this.rom.length + 0x3fff) / 0x4000);
        this.battery = battery;
        Arrays.fill(ram, 0xff);
        battery.loadRam(ram);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000)
                || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        value &= 0xff;
        if (address == 0x0006) {
            selectedRomBank = value;
        } else if (address == 0x0007) {
            selectedRamBank = value & 0x0f;
        } else if (address >= 0xa000 && address < 0xc000) {
            ram[getRamAddress(address)] = value;
            ramUpdated = true;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(address);
        } else if (address >= 0x4000 && address < 0x8000) {
            int bank = Math.floorMod(selectedRomBank, romBanks);
            return getRomByte(bank * 0x4000 + address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            return ram[getRamAddress(address)];
        }
        throw new IllegalArgumentException(Integer.toHexString(address));
    }

    private int getRomByte(int address) {
        return address < rom.length ? rom[address] : 0xff;
    }

    private int getRamAddress(int address) {
        return selectedRamBank * RAM_BANK_SIZE + address - 0xa000;
    }

    @Override
    public void flushRam() {
        if (ramUpdated) {
            battery.saveRam(ram);
            battery.flush();
        }
    }

    @Override
    public ComponentState<MemoryController> captureState() {
        return new XploderGbState(battery.captureState(), ram.clone(), selectedRomBank,
                selectedRamBank, ramUpdated);
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new XploderGbState(battery.captureState(capture), capture.ints(ram),
                selectedRomBank, selectedRamBank, ramUpdated);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        battery.declareMachineStatePayloads(capture);
        capture.declareInts(ram);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof XploderGbState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (mem.ram.length != ram.length) {
            throw new IllegalArgumentException("ComponentState RAM length doesn't match");
        }
        battery.restoreState(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        selectedRomBank = mem.selectedRomBank;
        selectedRamBank = mem.selectedRamBank;
        ramUpdated = mem.ramUpdated;
    }

    private record XploderGbState(ComponentState<Battery> batteryMemento, int[] ram,
                                   int selectedRomBank, int selectedRamBank,
                                   boolean ramUpdated)
            implements ComponentState<MemoryController> {
    }

}
