package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class Hdma implements AddressSpace, Serializable, Originator<Hdma> {

    private static final int HDMA1 = 0xff51;

    private static final int HDMA2 = 0xff52;

    private static final int HDMA3 = 0xff53;

    private static final int HDMA4 = 0xff54;

    private static final int HDMA5 = 0xff55;

    private final AddressSpace addressSpace;

    private Mode gpuMode;

    private boolean transferInProgress;

    private boolean hblankTransfer;

    private boolean lcdEnabled;

    private int length;

    private int src;

    private int dst;

    private int tick;

    public Hdma(AddressSpace addressSpace) {
        this.addressSpace = addressSpace;
    }

    @Override
    public boolean accepts(int address) {
        return address >= HDMA1 && address <= HDMA5;
    }

    public void tick() {
        if (!isTransferInProgress()) {
            return;
        }
        if (++tick < 0x20) {
            return;
        }
        for (int j = 0; j < 0x10; j++) {
            addressSpace.setByte(dst + j, addressSpace.getByte(src + j));
        }
        src = (src + 0x10) & 0xffff;
        dst = (dst + 0x10) & 0xffff;
        if (length-- == 0) {
            transferInProgress = false;
            length = 0x7f;
        } else if (hblankTransfer) {
            gpuMode = null; // wait until next HBlank
        }
    }

    @Override
    public void setByte(int address, int value) {
        switch (address) {
            case HDMA1:
                src = (value << 8) | (src & 0xff);
                break;
            case HDMA2:
                src = (src & 0xff00) | (value & 0xf0);
                break;
            case HDMA3:
                dst = 0x8000 | ((value & 0x1f) << 8) | (dst & 0xff);
                break;
            case HDMA4:
                dst = (dst & 0xff00) | (value & 0xf0);
                break;
            case HDMA5:
                if (transferInProgress && (value & (1 << 7)) == 0) {
                    stopTransfer();
                } else {
                    startTransfer(value);
                }
                break;
        }
    }

    @Override
    public int getByte(int address) {
        if (address == HDMA5) {
            return (transferInProgress ? 0 : (1 << 7)) | length;
        }
        return 0xff;
    }

    public void onGpuUpdate(Mode newGpuMode) {
        this.gpuMode = newGpuMode;
    }

    public void onLcdSwitch(boolean lcdEnabled) {
        this.lcdEnabled = lcdEnabled;
    }

    public boolean isTransferInProgress() {
        if (!transferInProgress) {
            return false;
        } else if (hblankTransfer && (gpuMode == Mode.HBlank || !lcdEnabled)) {
            return true;
        } else return !hblankTransfer;
    }

    private void startTransfer(int reg) {
        hblankTransfer = (reg & (1 << 7)) != 0;
        length = reg & 0x7f;
        transferInProgress = true;
    }

    private void stopTransfer() {
        transferInProgress = false;
    }

    @Override
    public Memento<Hdma> saveToMemento() {
        return new HdmaMemento(gpuMode, transferInProgress, hblankTransfer, lcdEnabled, length, src, dst, tick);
    }

    @Override
    public void restoreFromMemento(Memento<Hdma> memento) {
        if (!(memento instanceof HdmaMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.gpuMode = mem.gpuMode;
        this.transferInProgress = mem.transferInProgress;
        this.hblankTransfer = mem.hblankTransfer;
        this.lcdEnabled = mem.lcdEnabled;
        this.length = mem.length;
        this.src = mem.src;
        this.dst = mem.dst;
        this.tick = mem.tick;
    }

    public record HdmaMemento(Mode gpuMode, boolean transferInProgress, boolean hblankTransfer, boolean lcdEnabled,
                              int length, int src, int dst, int tick) implements Memento<Hdma> {
    }

}
