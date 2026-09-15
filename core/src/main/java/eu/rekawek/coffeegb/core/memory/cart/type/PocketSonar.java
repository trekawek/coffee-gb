package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;

/** Bandai MBC1S: five-bit ROM banking and a sampled sonar input instead of cartridge RAM. */
public final class PocketSonar implements MemoryController {
    private final int[] rom;
    private final boolean colorConsole;
    private int romBank = 1;
    private boolean enabled;
    private boolean pulse;
    private boolean powered = true;
    private int column = -1;
    private int row;
    private byte[] samples = SonarScene.demo().copySamples();

    public PocketSonar(Rom rom, boolean colorConsole) {
        this.rom = rom.getRom();
        this.colorConsole = colorConsole;
    }

    /** Called on the machine owner thread; null keeps the current scene. */
    public void configure(SonarScene scene, boolean powered) {
        if (scene != null) {
            samples = scene.copySamples();
            column = -1;
            row = 0;
        }
        this.powered = powered;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x2000 && address < 0x4000) {
            romBank = Math.max(1, value & 0x1f);
        } else if (address >= 0x4000 && address < 0x6000) {
            boolean nextPulse = (value & 1) != 0;
            if (enabled && powered && pulse && !nextPulse) {
                column = (column + 1) % SonarScene.WIDTH;
                row = 0;
            }
            pulse = enabled && nextPulse;
        } else if (address >= 0x6000 && address < 0x8000) {
            enabled = (value & 1) != 0;
            if (!enabled) pulse = false;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0 && address < 0x8000) {
            int offset = address < 0x4000 ? address
                    : (romBank % Math.max(1, rom.length / 0x4000)) * 0x4000 + (address & 0x3fff);
            return offset < rom.length ? rom[offset] : 0xff;
        }
        if (address != 0xa000 || !enabled || !powered) return 0xff;
        // Original CGB hardware cannot read the MBC1S sensor even in DMG compatibility mode.
        if (colorConsole) return 0;
        if (column < 0) return 7;
        int value = samples[row * SonarScene.WIDTH + column];
        row = Math.min(SonarScene.HEIGHT - 1, row + 1);
        return value;
    }

    @Override
    public ComponentState<MemoryController> captureState() {
        return state(samples.clone());
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return state(capture.bytes(samples));
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        capture.declareBytes(samples);
    }

    private PocketSonarState state(byte[] payload) {
        return new PocketSonarState(romBank, enabled, pulse, powered, column, row, payload);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof PocketSonarState s) || s.romBank < 1 || s.romBank > 31
                || s.column < -1 || s.column >= SonarScene.WIDTH
                || s.row < 0 || s.row >= SonarScene.HEIGHT || (s.pulse && !s.enabled)) {
            throw new IllegalArgumentException("Invalid Pocket Sonar state");
        }
        byte[] restored = new SonarScene(s.samples).copySamples();
        romBank = s.romBank;
        enabled = s.enabled;
        pulse = s.pulse;
        powered = s.powered;
        column = s.column;
        row = s.row;
        samples = restored;
    }

    private record PocketSonarState(int romBank, boolean enabled, boolean pulse, boolean powered,
                                   int column, int row, byte[] samples)
            implements ComponentState<MemoryController> { }
}
