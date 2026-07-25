package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

public class BasicRom implements MemoryController {

    private final int[] rom;

    private final int[] ram;

    private final Battery battery;

    private boolean ramUpdated;

    public BasicRom(Rom rom) {
        this(rom, Battery.NULL_BATTERY);
    }

    public BasicRom(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.ram = new int[rom.getRamSize()];
        Arrays.fill(ram, 0xff);
        this.battery = battery;
        battery.loadRam(ram);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0xa000 && address < 0xc000 && ram.length > 0) {
            ram[(address - 0xa000) % ram.length] = value;
            ramUpdated = true;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x8000) {
            return address < rom.length ? rom[address] : 0xff;
        } else if (address >= 0xa000 && address < 0xc000 && ram.length > 0) {
            return ram[(address - 0xa000) % ram.length];
        }
        // no cartridge RAM: reads float to 0xff (gbtests INITREGS)
        return 0xff;
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
        return new BasicRomState(battery.captureState(), ram.clone(), ramUpdated);
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new BasicRomState(
                battery.captureState(capture), capture.ints(ram), ramUpdated);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        battery.declareMachineStatePayloads(capture);
        capture.declareInts(ram);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        // BasicRom had no mutable state before plain ROM+RAM support was added.
        // Accept its legacy null snapshots as the initial RAM state.
        if (state == null) {
            return;
        }
        if (!(state instanceof BasicRomState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (ram.length != mem.ram.length) {
            throw new IllegalArgumentException("ComponentState RAM length doesn't match");
        }
        if (mem.batteryMemento != null) {
            battery.restoreState(mem.batteryMemento);
        }
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        ramUpdated = mem.ramUpdated;
    }

    private record BasicRomState(ComponentState<Battery> batteryMemento, int[] ram, boolean ramUpdated)
            implements ComponentState<MemoryController> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record BasicRomMemento(Memento<Battery> batteryMemento, int[] ram, boolean ramUpdated)
            implements Memento<MemoryController> {
    }
}
