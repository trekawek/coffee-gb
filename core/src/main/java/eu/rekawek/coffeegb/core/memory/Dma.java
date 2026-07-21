package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class Dma implements AddressSpace, Serializable, Originator<Dma> {

    private static final int PAUSE_ENTRY_CLOCKS = 8;

    private static final int DMG_SOURCE_BUS_RELEASE_CLOCKS = 636;

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

    // OAM source changes are applied after the reader has sampled the current
    // PPU tick. Record ownership before advancing the DMA clock so the reader
    // can propagate the old source through that position first.
    private boolean oamOwnedForPpuBeforeTick;

    private boolean oamOwnedForPpu;

    // Restarting an active transfer changes the CPU-visible DMA timing immediately,
    // but it must not disconnect a PPU reader that the old transfer already owned.
    private boolean ppuOamOwnedThroughRestart;

    // HALT stops the OAM-DMA clock after its two-M-cycle clock-gating latency;
    // STOP and a CGB speed switch gate it immediately.
    private boolean cpuClockPaused;

    private int pauseEntryClocks;

    // Index of the next OAM byte written by the transfer. The PPU sees the DMA's
    // current two-byte OAM bus row while the copy is running, not an atomic snapshot.
    private int currentByte;

    private int regValue;

    // Interrupt dispatch drives its two stack bytes one OAM-DMA slot later than
    // an ordinary CPU write. Keep the value until that still-pending copy edge.
    private boolean cpuInterruptStackWrite;

    private int pendingInterruptWriteByte = -1;

    private int pendingInterruptWriteValue;

    // VRAM DMA can drive the OAM-DMA destination bus during one copy edge. This
    // sample is supplied and consumed within a single Gameboy scheduler tick.
    private int vramDmaBusAddress = -1;

    private int vramDmaBusValue;

    // Once VRAM DMA has won a shared OAM copy edge, the CPU continues to observe
    // the preceding OAM source-bus phase for the remainder of this transfer.
    private boolean vramDmaBusCollisionObserved;

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
        oamOwnedForPpuBeforeTick = oamOwnedForPpu;
        if (cpuClockPaused && !this.cpuClockPaused) {
            pauseEntryClocks = haltEntryLatency ? PAUSE_ENTRY_CLOCKS : 0;
        }
        this.cpuClockPaused = cpuClockPaused;
        if (transferInProgress) {
            if (cpuClockPaused && pauseEntryClocks == 0) {
                updatePpuOamOwnership();
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
                    int vramDmaOamIndex = vramDmaBusAddress & 0xff;
                    if (vramDmaBusAddress >= 0 && vramDmaOamIndex < 0xa0) {
                        // The shared bus addresses OAM with the low byte of the VRAM-DMA
                        // source, rather than OAM DMA's ordinary sequential destination.
                        oam.setByte(0xfe00 + vramDmaOamIndex, vramDmaBusValue);
                        vramDmaBusCollisionObserved = true;
                        if (pendingInterruptWriteByte == currentByte) {
                            pendingInterruptWriteByte = -1;
                        }
                    } else {
                        int value = addressSpace.getByte(from + currentByte);
                        if (pendingInterruptWriteByte == currentByte) {
                            value = applyCpuWriteCollision(value, pendingInterruptWriteValue);
                            pendingInterruptWriteByte = -1;
                        }
                        oam.setByte(0xfe00 + currentByte, value);
                    }
                    currentByte++;
                }
                if (transferClocks >= 648) {
                    finishTransfer();
                    break;
                }
            }
        }
        updatePpuOamOwnership();
        vramDmaBusAddress = -1;
    }

    private void finishTransfer() {
        transferInProgress = false;
        restarted = false;
        ppuOamOwnedThroughRestart = false;
        ticks = 0;
        transferClocks = 0;
        currentByte = 0;
        pendingInterruptWriteByte = -1;
    }

    /**
     * A speed switch disconnects an OAM-DMA transfer whose final copy edge has
     * completed, without waiting for the ordinary four-clock release tail.
     */
    public void onSpeedSwitch() {
        if (transferInProgress && currentByte >= 0xa0) {
            finishTransfer();
        }
    }

    public void setVramDmaBusSample(Hdma.SourceBusSample sample) {
        if (sample == null) {
            vramDmaBusAddress = -1;
        } else {
            vramDmaBusAddress = sample.address();
            vramDmaBusValue = sample.value();
        }
    }

    @Override
    public void setByte(int address, int value) {
        from = value * 0x100;
        restarted = isOamBlocked();
        ppuOamOwnedThroughRestart = oamOwnedForPpu;
        ticks = 0;
        transferClocks = 0;
        cpuClockPaused = false;
        pauseEntryClocks = 0;
        currentByte = 0;
        pendingInterruptWriteByte = -1;
        vramDmaBusAddress = -1;
        vramDmaBusCollisionObserved = false;
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

    /** The source currently connected to the PPU's persistent OAM reader. */
    public boolean ownsOamForPpu() {
        return oamOwnedForPpu;
    }

    public boolean ownedOamForPpuBeforeTick() {
        return oamOwnedForPpuBeforeTick;
    }

    private void updatePpuOamOwnership() {
        boolean normalSpeedCgb = speedMode.isGbc() && speedMode.getSpeedMode() == 1;
        int acquisitionClocks = normalSpeedCgb ? 7 : 8;
        int releaseClocks = normalSpeedCgb ? 647 : 648;
        boolean owned = (ppuOamOwnedThroughRestart && transferClocks < acquisitionClocks)
                || (transferInProgress
                && transferClocks >= acquisitionClocks
                && transferClocks < releaseClocks);
        oamOwnedForPpu = owned;
    }

    public boolean isTransferInProgress() {
        return transferInProgress;
    }

    public boolean isCpuAccessBlocked(int address, boolean gbc) {
        if (!isCpuBusConflictActive() || address == 0xff46 || (address >= 0xfe00 && address < 0xff00)
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

    /**
     * The PPU loses OAM slightly before the DMA engine starts driving its source bus.
     * Keep the two conditions separate: CPU accesses only contend once the first byte
     * has reached OAM (or throughout the hand-over when an active DMA is restarted).
     */
    private boolean isCpuBusConflictActive() {
        return restarted || (transferInProgress && currentByte > 0);
    }

    public int getCpuBusValue() {
        if (!speedMode.isGbc() && from >= 0xe000
                && transferClocks < DMG_SOURCE_BUS_RELEASE_CLOCKS) {
            // The invalid top pages own the DMG main bus, but its incomplete address
            // decode drives the source page rather than the copied byte. The last
            // three transfer slots release that decode and expose the OAM latch.
            int byteIndex = Math.max(0, Math.min(0x9f, (transferClocks - 5) / 4));
            return ((from + byteIndex) >> 8) & 0x9f;
        }
        int oamAddress = getActiveOamAddress();
        int value = oam.getByte(oamAddress);
        if (speedMode.isGbc() && getSourceType() == SourceType.VRAM) {
            // A CGB read collision on the VRAM bus returns the byte currently driven
            // into OAM and then discharges that OAM-DMA latch to zero.
            oam.setByte(oamAddress, 0);
        }
        return value;
    }

    /** Applies a blocked CPU write to the byte currently driven by OAM DMA. */
    void onCpuBusWrite(int value) {
        int oamAddress;
        if (cpuInterruptStackWrite && transferInProgress && currentByte < 0xa0) {
            oamAddress = 0xfe00 + currentByte;
            pendingInterruptWriteByte = currentByte;
            pendingInterruptWriteValue = value;
        } else {
            oamAddress = getActiveOamAddress();
        }
        SourceType sourceType = getSourceType();
        if (speedMode.isGbc()) {
            if (sourceType == SourceType.VRAM) {
                // Neither writer wins a CGB VRAM-bus collision; the OAM latch sees 00.
                oam.setByte(oamAddress, 0);
            } else if (sourceType != SourceType.WRAM) {
                // Cartridge-bus writes replace the byte being transferred. A WRAM
                // source owns the separate WRAM bus and therefore drops the CPU write.
                oam.setByte(oamAddress, value);
            }
        } else if (sourceType == SourceType.WRAM) {
            // The DMG's WRAM driver and CPU write driver are both active-low.
            oam.setByte(oamAddress, oam.getByte(oamAddress) & value);
        } else {
            oam.setByte(oamAddress, value);
        }
    }

    private int applyCpuWriteCollision(int sourceValue, int cpuValue) {
        SourceType sourceType = getSourceType();
        if (speedMode.isGbc()) {
            if (sourceType == SourceType.VRAM) {
                return 0;
            } else if (sourceType == SourceType.WRAM) {
                return sourceValue;
            } else {
                return cpuValue;
            }
        }
        return sourceType == SourceType.WRAM ? sourceValue & cpuValue : cpuValue;
    }

    /** Marks the CPU bus phase used by the interrupt dispatch stack sequence. */
    public void setCpuInterruptStackWrite(boolean cpuInterruptStackWrite) {
        this.cpuInterruptStackWrite = cpuInterruptStackWrite;
    }

    private int getActiveOamAddress() {
        // The source-bus phase advances one clock after the OAM write edge. This is
        // intentionally not just currentByte - 1: interrupt stack writes can land in
        // that one-clock window and contend with the following byte.
        int sourceBusPhase = vramDmaBusCollisionObserved ? 9 : 5;
        int byteIndex = Math.max(0, Math.min(0x9f,
                (transferClocks - sourceBusPhase) / 4));
        return 0xfe00 + byteIndex;
    }

    int getUnblockedDmgHighBusValue(int address) {
        if (!speedMode.isGbc() && isCpuBusConflictActive()
                && from >= 0x8000 && from < 0xa000
                && transferClocks < DMG_SOURCE_BUS_RELEASE_CLOCKS
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
        if (!speedMode.isGbc() || !isCpuBusConflictActive()
                || address < 0xc000 || address >= 0xfe00) {
            return address;
        }
        int sourceAddress = DmaAddressSpace.mapAddress(from, true);
        if (getBus(sourceAddress, true) != 0) {
            return address;
        }
        // A cartridge-bus OAM DMA drives A12 while the CPU still has access to
        // the CGB's separate WRAM bus. Consequently, the source page chooses
        // which physical $C/$D half the CPU actually addresses. Normalize echo
        // addresses first; merely toggling A12 on $E/$F can incorrectly spill the
        // top of the echo range into $FE00-$FFFF.
        return 0xc000 | (sourceAddress & 0x1000) | (address & 0x0fff);
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

    private SourceType getSourceType() {
        if (from < 0x8000) {
            return SourceType.ROM;
        } else if (from < 0xa000) {
            return SourceType.VRAM;
        } else if (from < 0xc000) {
            return SourceType.SRAM;
        } else if (!speedMode.isGbc() || from < 0xe000) {
            // On DMG, E0-FF follow the WRAM/echo decode. CGB treats those pages as
            // an invalid cartridge-bus source instead.
            return SourceType.WRAM;
        } else {
            return SourceType.INVALID;
        }
    }

    private enum SourceType {
        ROM,
        VRAM,
        SRAM,
        WRAM,
        INVALID
    }

    @Override
    public Memento<Dma> saveToMemento() {
        return new DmaMemento(transferInProgress, restarted, from, ticks, transferClocks,
                oamOwnedForPpuBeforeTick, oamOwnedForPpu, ppuOamOwnedThroughRestart,
                cpuClockPaused, pauseEntryClocks, currentByte, regValue,
                pendingInterruptWriteByte, pendingInterruptWriteValue,
                vramDmaBusCollisionObserved);
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
        this.oamOwnedForPpuBeforeTick = mem.oamOwnedForPpuBeforeTick;
        this.oamOwnedForPpu = mem.oamOwnedForPpu;
        this.ppuOamOwnedThroughRestart = mem.ppuOamOwnedThroughRestart;
        this.cpuClockPaused = mem.cpuClockPaused;
        this.pauseEntryClocks = mem.pauseEntryClocks;
        this.currentByte = mem.currentByte;
        this.regValue = mem.regValue;
        this.pendingInterruptWriteByte = mem.pendingInterruptWriteByte;
        this.pendingInterruptWriteValue = mem.pendingInterruptWriteValue;
        this.vramDmaBusAddress = -1;
        this.vramDmaBusCollisionObserved = mem.vramDmaBusCollisionObserved;
        this.cpuInterruptStackWrite = false;
    }

    public record DmaMemento(boolean transferInProgress, boolean restarted, int from, int ticks,
                             int transferClocks, boolean oamOwnedForPpuBeforeTick,
                             boolean oamOwnedForPpu, boolean ppuOamOwnedThroughRestart,
                             boolean cpuClockPaused, int pauseEntryClocks,
                             int currentByte,
                             int regValue, int pendingInterruptWriteByte,
                             int pendingInterruptWriteValue,
                             boolean vramDmaBusCollisionObserved) implements Memento<Dma> {
    }
}
