package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;

import java.util.Arrays;

/**
 * Newer N&amp;T/Makon mapper used by selected games on multi-game boards.
 *
 * <p>It begins with an MBC3-like, 16 KiB switchable ROM window. Writing {@code 55} to
 * {@code 1400-1fff} changes that window into independently switchable 8 KiB halves, selected
 * through {@code 2000} and {@code 2400}. This is the board protocol, rather than MBC1's upper
 * ROM-bank register.</p>
 */
public class NtNew implements MemoryController {

    private final int[] rom;

    private final int rom8kBanks;

    private final int[] ram = new int[0x2000];

    private final Battery battery;

    private int lowRomBank = 2;

    private int highRomBank = 3;

    private boolean bank8kMode;

    private boolean ramWriteEnabled;

    private boolean ramUpdated;

    public NtNew(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.rom8kBanks = Math.max(2, (this.rom.length + 0x1fff) / 0x2000);
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
        if (address >= 0x0000 && address < 0x2000) {
            if ((address & 0x1f00) == 0x1400 && value == 0x55) {
                bank8kMode = true;
            }
            ramWriteEnabled = (value & 0x0f) == 0x0a;
        } else if (address >= 0x2000 && address < 0x3000) {
            selectRomBank(address, value);
        } else if (address >= 0xa000 && address < 0xc000 && ramWriteEnabled) {
            ram[address - 0xa000] = value;
            ramUpdated = true;
        }
    }

    private void selectRomBank(int address, int value) {
        if (!bank8kMode) {
            int bank = sanitize8kBank(value << 1);
            lowRomBank = bank;
            highRomBank = bank | 1;
            return;
        }
        switch (address & 0x0f00) {
            case 0x0000 -> lowRomBank = sanitize8kBank(value);
            case 0x0400 -> highRomBank = sanitize8kBank(value);
            default -> {
                // Other addresses in the range are not decoded by this board.
            }
        }
    }

    private static int sanitize8kBank(int bank) {
        return (bank & 0xfe) == 0 ? bank | 2 : bank;
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(0, address);
        } else if (address >= 0x4000 && address < 0x6000) {
            return getRomByte(lowRomBank, address - 0x4000);
        } else if (address >= 0x6000 && address < 0x8000) {
            return getRomByte(highRomBank, address - 0x6000);
        } else if (address >= 0xa000 && address < 0xc000) {
            return ramWriteEnabled ? ram[address - 0xa000] : 0xff;
        }
        throw new IllegalArgumentException(Integer.toHexString(address));
    }

    private int getRomByte(int bank, int offset) {
        int address = Math.floorMod(bank, rom8kBanks) * 0x2000 + offset;
        return address < rom.length ? rom[address] : 0xff;
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
        return new NtNewState(battery.captureState(), ram.clone(), lowRomBank, highRomBank,
                bank8kMode, ramWriteEnabled, ramUpdated);
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new NtNewState(battery.captureState(capture), capture.ints(ram), lowRomBank,
                highRomBank, bank8kMode, ramWriteEnabled, ramUpdated);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        battery.declareMachineStatePayloads(capture);
        capture.declareInts(ram);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof NtNewState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        battery.restoreState(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        lowRomBank = mem.lowRomBank;
        highRomBank = mem.highRomBank;
        bank8kMode = mem.bank8kMode;
        ramWriteEnabled = mem.ramWriteEnabled;
        ramUpdated = mem.ramUpdated;
    }

    private record NtNewState(ComponentState<Battery> batteryMemento, int[] ram,
                              int lowRomBank, int highRomBank, boolean bank8kMode,
                              boolean ramWriteEnabled, boolean ramUpdated)
            implements ComponentState<MemoryController> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record NtNewMemento(Memento<Battery> batteryMemento, int[] ram,
                                int lowRomBank, int highRomBank, boolean bank8kMode,
                                boolean ramWriteEnabled, boolean ramUpdated)
            implements Memento<MemoryController> {
    }
}
