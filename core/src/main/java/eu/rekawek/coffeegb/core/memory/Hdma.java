package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
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

    // VRAM DMA's request hand-off is distinct from the 32-tick data burst. HBlank
    // arbitration takes four scheduler ticks at normal speed and three at double
    // speed; general-purpose DMA has its own startup timing.
    private static final int NORMAL_SPEED_GDMA_STARTUP_TICKS = 6;

    private static final int NORMAL_SPEED_HBLANK_STARTUP_TICKS = 4;

    private static final int DOUBLE_SPEED_GDMA_STARTUP_TICKS = 2;

    private static final int DOUBLE_SPEED_HBLANK_STARTUP_TICKS = 3;

    private final AddressSpace addressSpace;

    private final SpeedMode speedMode;

    private Mode gpuMode;

    private boolean transferInProgress;

    private boolean hblankTransfer;

    private boolean lcdEnabled;

    private int length;

    private int src;

    private int dst;

    private int tick;

    private int sourceBytesTransferred;

    private int cpuBusValue = 0xff;

    public Hdma(AddressSpace addressSpace) {
        this(addressSpace, new SpeedMode(false));
    }

    public Hdma(AddressSpace addressSpace, SpeedMode speedMode) {
        this.addressSpace = addressSpace;
        this.speedMode = speedMode;
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
            addressSpace.setByte(dst + j, readSourceByte(src + j));
            sourceBytesTransferred++;
        }
        src = (src + 0x10) & 0xffff;
        dst = (dst + 0x10) & 0xffff;
        tick = hblankTransfer ? -startupTicks() : 0;
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
                sourceBytesTransferred = 0;
                break;
            case HDMA2:
                src = (src & 0xff00) | (value & 0xf0);
                sourceBytesTransferred = 0;
                break;
            case HDMA3:
                dst = 0x8000 | ((value & 0x1f) << 8) | (dst & 0xff);
                break;
            case HDMA4:
                dst = (dst & 0xff00) | (value & 0xf0);
                break;
            case HDMA5:
                if (transferInProgress && (value & (1 << 7)) == 0) {
                    stopTransfer(value);
                } else {
                    startTransfer(value);
                }
                break;
        }
    }

    private int readSourceByte(int address) {
        if (address >= 0x8000 && address < 0xa000) {
            // This source range is not connected to CGB VRAM DMA. Hardware
            // exposes the instruction-bus residue for its first two byte slots,
            // then the undriven bus settles high.
            return sourceBytesTransferred < 2 ? cpuBusValue : 0xff;
        }
        return addressSpace.getByte(DmaAddressSpace.mapAddress(address, true));
    }

    public void setCpuBusValue(int cpuBusValue) {
        this.cpuBusValue = cpuBusValue & 0xff;
    }

    private int startupTicks() {
        if (hblankTransfer && lcdEnabled) {
            return speedMode.getSpeedMode() == 2
                    ? DOUBLE_SPEED_HBLANK_STARTUP_TICKS
                    : NORMAL_SPEED_HBLANK_STARTUP_TICKS;
        }
        return speedMode.getSpeedMode() == 2
                ? DOUBLE_SPEED_GDMA_STARTUP_TICKS
                : NORMAL_SPEED_GDMA_STARTUP_TICKS;
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
        if (!lcdEnabled && transferInProgress && hblankTransfer) {
            // Disabling the LCD releases the current HDMA request as one final
            // burst. With no following HBlanks, the remaining blocks stay
            // paused until the display is enabled again.
            gpuMode = Mode.HBlank;
        }
    }

    public boolean isTransferInProgress() {
        if (!transferInProgress) {
            return false;
        } else if (hblankTransfer && gpuMode == Mode.HBlank) {
            return true;
        } else return !hblankTransfer;
    }

    /** Whether an HBlank transfer is still armed, including between HBlank bursts. */
    public boolean hasPendingHblankTransfer() {
        return transferInProgress && hblankTransfer;
    }

    private void startTransfer(int reg) {
        hblankTransfer = (reg & (1 << 7)) != 0;
        length = reg & 0x7f;
        transferInProgress = true;
        tick = -startupTicks();
        if (hblankTransfer && !lcdEnabled) {
            // With the LCD off, starting HDMA copies one block immediately. There are
            // no subsequent HBlanks, so the transfer then remains paused.
            gpuMode = Mode.HBlank;
        }
    }

    private void stopTransfer(int reg) {
        transferInProgress = false;
        length = reg & 0x7f;
    }

    @Override
    public Memento<Hdma> saveToMemento() {
        return new HdmaMemento(gpuMode, transferInProgress, hblankTransfer, lcdEnabled, length,
                src, dst, tick, sourceBytesTransferred, cpuBusValue);
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
        this.sourceBytesTransferred = mem.sourceBytesTransferred;
        this.cpuBusValue = mem.cpuBusValue;
    }

    public record HdmaMemento(Mode gpuMode, boolean transferInProgress, boolean hblankTransfer, boolean lcdEnabled,
                              int length, int src, int dst, int tick,
                              int sourceBytesTransferred, int cpuBusValue) implements Memento<Hdma> {
    }

}
