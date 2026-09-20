package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.ir.InfraredEndpoint;
import eu.rekawek.coffeegb.core.state.bess.BessCartridgeState;
import eu.rekawek.coffeegb.core.state.bess.BessState.MbcWrite;
import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hudson HuC-1 mapper (used by e.g. Chousoku Spinner). MBC1-like banking without the mode
 * register; writing 0x0E to 0x0000-0x1FFF selects the infrared mode instead of the cart RAM
 * (reads return 0xC0/0xC1 depending on the received IR light; without a link partner no
 * light is ever seen).
 */
public class Huc1 implements MemoryController {

    private final int[] cartridge;

    private final int[] ram;

    private final int romBanks;

    private final int ramBanks;

    private final Battery battery;

    private int romBank = 1;

    private int ramBank;

    private boolean irMode;

    private boolean ramUpdated;

    private boolean irOutput;

    private transient InfraredEndpoint infraredEndpoint = InfraredEndpoint.NULL_ENDPOINT;

    public Huc1(Rom rom, Battery battery) {
        this.cartridge = rom.getRom();
        this.romBanks = rom.getRomBanks();
        this.ramBanks = Math.max(rom.getRamBanks(), 1);
        this.ram = new int[0x2000 * this.ramBanks];
        Arrays.fill(ram, 0xff);
        this.battery = battery;
        battery.loadRam(ram);
    }


    @Override
    public BessCartridgeState captureBessState() {
        if (getClass() != Huc1.class) {
            throw new IllegalArgumentException("BESS states are not supported for this cartridge mapper");
        }
        return new BessCartridgeState(BessCartridgeState.bytes(ram),
                List.of(new MbcWrite(0x0000, 0x0e),
                        new MbcWrite(0xa000, irOutput ? 1 : 0),
                        new MbcWrite(0x0000, irMode ? 0x0e : 0x0a),
                        new MbcWrite(0x2000, romBank),
                        new MbcWrite(0x4000, ramBank)), Map.of());
    }

    @Override
    public void restoreBessRam(byte[] data) {
        BessCartridgeState.restoreRam(data, ram);
        ramUpdated = true;
    }

    @Override
    public boolean hasInfrared() {
        return true;
    }

    @Override
    public void setInfraredEndpoint(InfraredEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (infraredEndpoint == endpoint) return;
        infraredEndpoint.disconnect();
        infraredEndpoint = endpoint;
        infraredEndpoint.setLightOn(irOutput);
    }

    @Override
    public boolean isClocked() {
        return true;
    }

    @Override
    public void tick() {
        infraredEndpoint.tick();
    }

    @Override
    public int performanceQuietSpanLimit(int requested) {
        return infraredEndpoint.performanceQuietSpanLimit(requested);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x0000 && address < 0x2000) {
            irMode = (value & 0x0f) == 0x0e;
        } else if (address >= 0x2000 && address < 0x4000) {
            romBank = value & 0b00111111;
        } else if (address >= 0x4000 && address < 0x6000) {
            ramBank = value & 0b111;
        } else if (address >= 0xa000 && address < 0xc000) {
            if (irMode) {
                irOutput = (value & 1) != 0;
                infraredEndpoint.setLightOn(irOutput);
            } else {
                ram[getRamAddress(address)] = value;
                ramUpdated = true;
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(0, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(romBank % romBanks, address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            if (irMode) {
                return 0xc0 | (infraredEndpoint.isLightOn() ? 1 : 0);
            }
            return ram[getRamAddress(address)];
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

    private int getRamAddress(int address) {
        return (ramBank % ramBanks) * 0x2000 + (address - 0xa000);
    }

    private int getRomByte(int bank, int address) {
        int cartOffset = bank * 0x4000 + address;
        if (cartOffset < cartridge.length) {
            return cartridge[cartOffset];
        } else {
            return 0xff;
        }
    }

    @Override
    public ComponentState<MemoryController> captureState() {
        return new Huc1State(battery.captureState(), ram.clone(), romBank, ramBank, irMode, ramUpdated, irOutput);
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new Huc1State(
                battery.captureState(capture),
                capture.ints(ram),
                romBank,
                ramBank,
                irMode,
                ramUpdated,
                irOutput);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        battery.declareMachineStatePayloads(capture);
        capture.declareInts(ram);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof Huc1State mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (this.ram.length != mem.ram.length) {
            throw new IllegalArgumentException("ComponentState ram length doesn't match");
        }
        battery.restoreState(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.romBank = mem.romBank;
        this.ramBank = mem.ramBank;
        this.irMode = mem.irMode;
        this.ramUpdated = mem.ramUpdated;
        this.irOutput = mem.irOutput;
        infraredEndpoint.onMachineStateRestored();
        infraredEndpoint.setLightOn(irOutput);
    }

    private record Huc1State(ComponentState<Battery> batteryMemento, int[] ram, int romBank, int ramBank,
                               boolean irMode, boolean ramUpdated, boolean irOutput) implements ComponentState<MemoryController> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record Huc1Memento(Memento<Battery> batteryMemento, int[] ram, int romBank, int ramBank,
                               boolean irMode, boolean ramUpdated) implements Memento<MemoryController> {
    }
}
