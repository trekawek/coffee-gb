package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.gpu.Gpu;

public class Hdma implements AddressSpace {

    private static final int HDMA1 = 0xff51;

    private static final int HDMA2 = 0xff52;

    private static final int HDMA3 = 0xff53;

    private static final int HDMA4 = 0xff54;

    private static final int HDMA5 = 0xff55;

    private final AddressSpace addressSpace;

    private final Ram hdma1234 = new Ram(HDMA1, 4);

    private Gpu.Mode gpuMode;

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
        src += 0x10;
        dst += 0x10;
        if (length-- == 0) {
            transferInProgress = false;
            length = 0x7f;
        } else if (hblankTransfer) {
            gpuMode = null; // wait until next HBlank
        }
    }

    @Override
    public void setByte(int address, int value) {
        if (hdma1234.accepts(address)) {
            hdma1234.setByte(address, value);
        } else if (address == HDMA5) {
            if (transferInProgress && (address & (1 << 7)) == 0) {
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

    public void onGpuUpdate(Gpu.Mode newGpuMode) {
        this.gpuMode = newGpuMode;
    }

    public void onLcdSwitch(boolean lcdEnabled) {
        this.lcdEnabled = lcdEnabled;
    }

    public boolean isTransferInProgress() {
        if (!transferInProgress) {
            return false;
        } else if (hblankTransfer && (gpuMode == Gpu.Mode.HBlank || !lcdEnabled)) {
            return true;
        } else if (!hblankTransfer) {
            return true;
        } else {
            return false;
        }
    }

    private void startTransfer(int reg) {
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
}