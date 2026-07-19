package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class Dma implements AddressSpace, Serializable, Originator<Dma> {

    private static final int PAUSE_ENTRY_CLOCKS = 8;

    private final AddressSpace addressSpace;

    private final AddressSpace oam;

    private final SpeedMode speedMode;

    private boolean transferInProgress;

    private boolean restarted;

    private int from;

    private int ticks;

    // CPU clocks elapsed since the transfer started. Keep this separate from
    // fixed-rate PPU ticks so changing KEY1 during a copy cannot retroactively
    // rescale the portion that has already completed.
    private int transferClocks;

    // HALT stops the OAM-DMA clock after its two-M-cycle clock-gating latency;
    // STOP and a CGB speed switch gate it immediately.
    private boolean cpuClockPaused;

    private int pauseEntryClocks;

    // Index of the next OAM byte written by the transfer. The PPU sees the DMA's
    // current two-byte OAM bus row while the copy is running, not an atomic snapshot.
    private int currentByte;

    private int regValue;

    public Dma(AddressSpace addressSpace, AddressSpace oam, SpeedMode speedMode) {
        this.addressSpace = new DmaAddressSpace(addressSpace, speedMode.isGbc());
        this.speedMode = speedMode;
        this.oam = oam;
        // FF46 most commonly powers up as 00 on CGB and FF on DMG.
        // Doc Cosmos reads FF46 to choose the phase of its title-screen scroller.
        regValue = speedMode.isGbc() ? 0x00 : 0xff;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff46;
    }

    public void tick() {
        tick(false);
    }

    public void tick(boolean cpuClockPaused) {
        tick(cpuClockPaused, true);
    }

    public void tick(boolean cpuClockPaused, boolean haltEntryLatency) {
        if (cpuClockPaused && !this.cpuClockPaused) {
            pauseEntryClocks = haltEntryLatency ? PAUSE_ENTRY_CLOCKS : 0;
        }
        this.cpuClockPaused = cpuClockPaused;
        if (transferInProgress) {
            if (cpuClockPaused && pauseEntryClocks == 0) {
                return;
            }
            ticks++;
            for (int i = 0; i < speedMode.getSpeedMode(); i++) {
                if (cpuClockPaused && pauseEntryClocks == 0) {
                    break;
                }
                transferClocks++;
                if (cpuClockPaused) {
                    pauseEntryClocks--;
                }
                if (transferClocks >= 8 && transferClocks <= 644
                        && transferClocks % 4 == 0) {
                    oam.setByte(0xfe00 + currentByte,
                            addressSpace.getByte(from + currentByte));
                    currentByte++;
                }
                if (transferClocks >= 648) {
                    transferInProgress = false;
                    restarted = false;
                    ticks = 0;
                    transferClocks = 0;
                    currentByte = 0;
                    break;
                }
            }
        }
    }

    @Override
    public void setByte(int address, int value) {
        from = value * 0x100;
        restarted = isOamBlocked();
        ticks = 0;
        transferClocks = 0;
        cpuClockPaused = false;
        pauseEntryClocks = 0;
        currentByte = 0;
        transferInProgress = true;
        regValue = value;
    }

    @Override
    public int getByte(int address) {
        return regValue;
    }

    public boolean isOamBlocked() {
        return restarted || (transferInProgress && ticks >= 5);
    }

    public boolean isTransferInProgress() {
        return transferInProgress;
    }

    public boolean isCpuAccessBlocked(int address, boolean gbc) {
        if (!isOamBlocked() || address == 0xff46 || (address >= 0xfe00 && address < 0xff00)
                || (address >= 0xff80 && address < 0xffff)) {
            return false;
        }
        int cpuBus = getBus(address, gbc);
        // On DMG, an invalid $E0-$FF copy source follows echo RAM and therefore
        // owns the shared main bus. CGB instead classifies its cartridge-RAM alias
        // ($A0-$BF, on the cartridge bus).
        int sourceAddress;
        if (speedMode.isGbc()) {
            sourceAddress = DmaAddressSpace.mapAddress(from, true);
        } else {
            // The invalid top 8 KiB select the DMG's main bus even though the
            // value driven on it is derived from the partially decoded address.
            sourceAddress = from >= 0xe000 ? 0xc000 : from;
        }
        return cpuBus >= 0 && cpuBus == getBus(sourceAddress, gbc);
    }

    public int getCpuBusValue() {
        int byteIndex = Math.max(0, Math.min(0x9f, (transferClocks - 5) / 4));
        if (!speedMode.isGbc() && from >= 0xe000) {
            // The DMG copy engine follows echo RAM here, but a conflicting CPU
            // read sees the source page after the DMA unit's partial decode.
            return ((from + byteIndex) >> 8) & 0x9f;
        }
        return addressSpace.getByte(from + byteIndex);
    }

    int getUnblockedDmgHighBusValue(int address) {
        if (!speedMode.isGbc() && isOamBlocked()
                && from >= 0x8000 && from < 0xa000
                && transferClocks < 636
                && address >= 0xe000 && address < 0xfe00) {
            // During a VRAM-source DMA, the otherwise unblocked DMG high range
            // exposes its partially decoded $80-$9D page on the bus. The source
            // bus releases during the transfer tail (Mooneye call/ret timing),
            // while OAM itself remains blocked until the transfer completes.
            return (address >> 8) & 0x9f;
        }
        return -1;
    }

    int mapUnblockedCpuAddress(int address) {
        if (!speedMode.isGbc() || !isOamBlocked()
                || address < 0xc000 || address >= 0xfe00) {
            return address;
        }
        int sourceAddress = DmaAddressSpace.mapAddress(from, true);
        if (getBus(sourceAddress, true) != 0) {
            return address;
        }
        // A cartridge-bus OAM DMA drives A12 while the CPU still has access to
        // the CGB's separate WRAM bus. Consequently, the source page chooses
        // which $C/$D (and echoed $E/$F) half the CPU actually addresses.
        return (address & ~0x1000) | (sourceAddress & 0x1000);
    }

    /** Reads OAM as seen by the PPU while an OAM DMA owns its bus. */
    public int getOamByteForPpu(int address) {
        if (transferInProgress && currentByte > 0 && currentByte < 0xa0) {
            int busAddress = (currentByte & ~1) | (address & 1);
            return oam.getByte(0xfe00 + busAddress);
        }
        return oam.getByte(address);
    }

    private static int getBus(int address, boolean gbc) {
        if (address < 0x8000 || (address >= 0xa000 && address < 0xc000)) {
            return 0; // cartridge
        } else if (address >= 0x8000 && address < 0xa000) {
            return 1; // VRAM
        } else if (address >= 0xc000 && address < 0xfe00) {
            return gbc ? 2 : 0; // WRAM has its own bus only on CGB
        } else {
            return -1; // OAM, I/O and HRAM are not on a DMA source bus
        }
    }

    @Override
    public Memento<Dma> saveToMemento() {
        return new DmaMemento(transferInProgress, restarted, from, ticks, transferClocks,
                cpuClockPaused, pauseEntryClocks, currentByte, regValue);
    }

    @Override
    public void restoreFromMemento(Memento<Dma> memento) {
        if (!(memento instanceof DmaMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.transferInProgress = mem.transferInProgress;
        this.restarted = mem.restarted;
        this.from = mem.from;
        this.ticks = mem.ticks;
        this.transferClocks = mem.transferClocks;
        this.cpuClockPaused = mem.cpuClockPaused;
        this.pauseEntryClocks = mem.pauseEntryClocks;
        this.currentByte = mem.currentByte;
        this.regValue = mem.regValue;
    }

    public record DmaMemento(boolean transferInProgress, boolean restarted, int from, int ticks,
                             int transferClocks, boolean cpuClockPaused, int pauseEntryClocks,
                             int currentByte,
                             int regValue) implements Memento<Dma> {
    }
}
