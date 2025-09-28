package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.StatRegister;
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

    private final Ram hdma1234 = new Ram(HDMA1, 4);

    private boolean transferInProgress;

    private boolean hblankTransfer;

    private int length;

    private int src;

    private int dst;

    private int tick;

    private boolean hBlankChunkCompleted;

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
        src += 0x10;
        dst += 0x10;
        if (length-- == 0) {
            transferInProgress = false;
            length = 0x7f;
        } else if (hblankTransfer) {
            hBlankChunkCompleted = true;
        }
    }

    @Override
    public void setByte(int address, int value) {
        if (hdma1234.accepts(address)) {
            hdma1234.setByte(address, value);
        } else if (address == HDMA5) {
            if (transferInProgress && (value & (1 << 7)) == 0) {
                stopTransfer();
            } else {
                startTransfer(value);
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (hdma1234.accepts(address)) {
            return 0xff;
        } else if (address == HDMA5) {
            return (transferInProgress ? 0 : (1 << 7)) | length;
        } else {
            throw new IllegalArgumentException();
        }
    }

    public boolean isTransferInProgress() {
        if (transferInProgress) {
            if (hblankTransfer) {
                var mode = (addressSpace.getByte(StatRegister.ADDRESS) & 0b11);
                if (mode == 0) {
                    return !hBlankChunkCompleted;
                } else {
                    hBlankChunkCompleted = false;
                    return false;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private void startTransfer(int reg) {
        hBlankChunkCompleted = false;
        hblankTransfer = (reg & (1 << 7)) != 0;
        length = reg & 0x7f;

        src = (hdma1234.getByte(HDMA1) << 8) | (hdma1234.getByte(HDMA2) & 0xf0);
        dst = ((hdma1234.getByte(HDMA3) & 0x1f) << 8) | (hdma1234.getByte(HDMA4) & 0xf0);
        src = src & 0xfff0;
        dst = (dst & 0x1fff) | 0x8000;

        transferInProgress = true;
    }

    private void stopTransfer() {
        transferInProgress = false;
    }

    @Override
    public Memento<Hdma> saveToMemento() {
        return new HdmaMemento(hdma1234.saveToMemento(), transferInProgress, hblankTransfer, length, src, dst, tick);
    }

    @Override
    public void restoreFromMemento(Memento<Hdma> memento) {
        if (!(memento instanceof HdmaMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        hdma1234.restoreFromMemento(mem.hdma1234);
        this.transferInProgress = mem.transferInProgress;
        this.hblankTransfer = mem.hblankTransfer;
        this.length = mem.length;
        this.src = mem.src;
        this.dst = mem.dst;
        this.tick = mem.tick;
    }

    public record HdmaMemento(Memento<Ram> hdma1234, boolean transferInProgress,
                              boolean hblankTransfer, int length, int src, int dst, int tick
    ) implements Memento<Hdma> {
    }

}
