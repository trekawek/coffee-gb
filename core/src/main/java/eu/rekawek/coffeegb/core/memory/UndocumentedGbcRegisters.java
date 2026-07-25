package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public class UndocumentedGbcRegisters implements AddressSpace, StatefulComponent<UndocumentedGbcRegisters> {

    // FF76/FF77 are the PCM12/PCM34 sound registers, handled by the APU
    private final Ram ram = new Ram(0xff72, 4);

    private int xff6c;

    private SpeedMode speedMode;

    public UndocumentedGbcRegisters() {
        xff6c = 0xfe;
        ram.setByte(0xff75, 0x8f);
    }

    public void setSpeedMode(SpeedMode speedMode) {
        this.speedMode = speedMode;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff6c || ram.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        switch (address) {
            case 0xff6c:
                xff6c = 0xfe | (value & 1);
                break;

            case 0xff72:
            case 0xff73:
                ram.setByte(address, value);
                break;

            case 0xff74:
                if (speedMode == null || !speedMode.isDmgCompat()) {
                    ram.setByte(address, value);
                }
                break;

            case 0xff75:
                ram.setByte(address, 0x8f | (value & 0b01110000));
        }
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff6c) {
            return xff6c;
        } else if (address == 0xff74 && speedMode != null && speedMode.isDmgCompat()) {
            return 0xff;
        } else if (ram.accepts(address)) {
            return ram.getByte(address);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public ComponentState<UndocumentedGbcRegisters> captureState() {
        return new UndocumentedGbcRegistersState(ram.captureState(), xff6c);
    }

    @Override
    public ComponentState<UndocumentedGbcRegisters> captureState(MachineStateCapture capture) {
        return new UndocumentedGbcRegistersState(ram.captureState(capture), xff6c);
    }

    @Override
    public void restoreState(ComponentState<UndocumentedGbcRegisters> state) {
        if (!(state instanceof UndocumentedGbcRegistersState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        ram.restoreState(mem.ramMemento);
        xff6c = mem.xff6c;
    }

    public record UndocumentedGbcRegistersState(ComponentState<Ram> ramMemento,
                                                  int xff6c) implements ComponentState<UndocumentedGbcRegisters> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    public record UndocumentedGbcRegistersMemento(Memento<Ram> ramMemento,
                                                  int xff6c) implements Memento<UndocumentedGbcRegisters> {
    }

}
