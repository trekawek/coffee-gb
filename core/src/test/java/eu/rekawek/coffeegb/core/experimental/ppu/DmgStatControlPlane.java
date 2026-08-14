package eu.rekawek.coffeegb.core.experimental.ppu;

import java.util.EnumSet;
import java.util.Set;

/**
 * Detached DMG PPU control-plane experiment.
 *
 * <p>This is intentionally not a renderer. A pixel pipeline contributes only its terminal pixel
 * dot. From that edge, the control island clocks independent readable-mode, OAM/VRAM gate, HBlank
 * request, LY/LYC, and STAT-line latches. State changes are first resolved into a {@link Next}
 * vector and then committed together; the only local delta iteration is the documented line-start
 * comparator edge after retiring line sources have settled.
 */
final class DmgStatControlPlane {

    static final int MODE_HBLANK = 0;

    static final int MODE_VBLANK = 1;

    static final int MODE_OAM = 2;

    static final int MODE_TRANSFER = 3;

    static final int STAT_M0 = 0x08;

    static final int STAT_M1 = 0x10;

    static final int STAT_M2 = 0x20;

    static final int STAT_LYC = 0x40;

    /**
     * A condition is a falsifier until it can be expressed as another causal input/latch rather
     * than as raster- or opcode-specific code in this island.
     */
    enum Falsifier {
        POWER_ON_PARTIAL_LINE_PHASE,
        MODE0_PHYSICAL_EDGE_VS_PRODUCTION_PREDICTION,
        MID_TRANSFER_WINDOW_CANCEL_READABLE_MODE_PULSE,
        DIVERGENT_TIMING_AND_OUTPUT_PIPELINE_TAIL,
        TERMINAL_WX166_X167_IF_READ_PHASE,
        HALT_WAKE_CPU_READ_MUX,
        CENTRAL_IF_ACKNOWLEDGE_CAPTURE_WINDOW
    }

    private final int pixelEndDot;

    private final EnumSet<Falsifier> unresolved = EnumSet.allOf(Falsifier.class);

    private boolean lcdEnabled;

    private boolean firstLine;

    private int line;

    private int dot;

    /** The ripple/counter value; DMG's line-153 CPU mux can hide it. */
    private int lyCounter;

    /** LY sampled by the comparator. */
    private int registeredLy;

    private int lyc;

    private int statEnable;

    private int readableMode;

    private boolean coincidence;

    /** Settled comparator contribution; it retires later than the CPU-readable flag. */
    private boolean intCoincidence;

    /** Holds the line-start comparison edge until intCoincidence settles four dots later. */
    private boolean coincidenceEdgeHold;

    private int coincidenceSettleDots;

    private boolean internalVblank;

    /** Eight-dot ordinary M2 pulse: previous-line 452..455 and new-line 0..3. */
    private int mode2PulseDots;

    /** FF41.M2 sampled on the leading edge; a later write cannot arm this occurrence. */
    private boolean mode2PulseArmed;

    private boolean mode0Source;

    private boolean statLine;

    private boolean lcdcIf;

    private boolean vblankIf;

    private boolean oamReadLocked;

    private boolean oamWriteLocked;

    private boolean vramReadLocked;

    private boolean vramWriteLocked;

    private DmgStatControlPlane(int pixelEndDot) {
        if (pixelEndDot < 80 || pixelEndDot >= 448) {
            throw new IllegalArgumentException("pixelEndDot must be in 80..447");
        }
        this.pixelEndDot = pixelEndDot;
    }

    static DmgStatControlPlane steadyFrame(int pixelEndDot, int lyc, int statEnable) {
        DmgStatControlPlane ppu = new DmgStatControlPlane(pixelEndDot);
        ppu.lcdEnabled = true;
        ppu.line = 0;
        ppu.dot = 0;
        ppu.lyCounter = 0;
        ppu.registeredLy = 0;
        ppu.lyc = lyc & 0xff;
        ppu.statEnable = statEnable & 0x78;
        ppu.readableMode = MODE_OAM;
        ppu.coincidence = ppu.lyc == 0;
        ppu.intCoincidence = false;
        ppu.coincidenceEdgeHold = ppu.coincidence;
        ppu.coincidenceSettleDots = 4;
        ppu.mode2PulseDots = 4;
        ppu.mode2PulseArmed = (ppu.statEnable & STAT_M2) != 0;
        ppu.oamReadLocked = true;
        ppu.oamWriteLocked = true;
        ppu.settleStatLine(false);
        return ppu;
    }

    static DmgStatControlPlane lcdOff(int pixelEndDot, int lyc, boolean frozenCoincidence) {
        DmgStatControlPlane ppu = new DmgStatControlPlane(pixelEndDot);
        ppu.lyc = lyc & 0xff;
        ppu.coincidence = frozenCoincidence;
        ppu.intCoincidence = frozenCoincidence;
        return ppu;
    }

    void tick() {
        if (!lcdEnabled) {
            return;
        }

        Next next = resolveNext();

        // At a line-start comparator capture the outgoing mode source settles first. This gives
        // the M0->LYC transition a real low/high edge when M2 is disabled, while an enabled M2
        // pulse naturally blocks it. No source-specific interrupt branch is required.
        if (next.captureCoincidence) {
            settleStatLine(rawStatLineBeforeComparatorCapture(next), false);
        }

        commit(next);
        settleStatLine(rawStatLine(), false);
    }

    private Next resolveNext() {
        Next n = new Next(this);
        int lineLength = firstLine ? 455 : 456;
        n.dot = dot + 1;
        n.lineRollover = n.dot == lineLength;
        if (n.lineRollover) {
            n.dot = 0;
            n.line = (line + 1) % 154;
            n.firstLine = false;
            n.mode0Source = false;
            if (n.line == 144) {
                n.internalVblank = true;
                n.vblankIf = true;
            } else if (n.line == 0) {
                n.internalVblank = false;
                // There is no visible-line tail in line 153, so the frame M2 pulse begins here.
                n.mode2PulseDots = Math.max(n.mode2PulseDots, 4);
                n.mode2PulseArmed = (statEnable & STAT_M2) != 0;
                n.lineEdgePulseStarted = true;
            }
            if (n.line < 144) {
                n.readableMode = MODE_OAM;
                n.oamWriteLocked = true;
            } else {
                n.readableMode = MODE_VBLANK;
                n.oamReadLocked = false;
                n.oamWriteLocked = false;
                n.vramReadLocked = false;
                n.vramWriteLocked = false;
            }
            n.registeredLy = n.line == 153 ? 153 : n.lyCounter;
            n.coincidence = n.registeredLy == lyc;
            if (line == 153 && n.line == 0) {
                // LY already became zero at line 153 dot 8. The frame rollover samples the
                // same value, so the comparator wire never discharges and LYC=0 cannot edge
                // a second time here.
                n.intCoincidence = n.coincidence;
                n.coincidenceEdgeHold = false;
                n.coincidenceSettleDots = 0;
            } else {
                n.captureCoincidence = true;
                n.intCoincidence = false;
                n.coincidenceEdgeHold = n.coincidence;
                n.coincidenceSettleDots = 4;
            }
        }

        if (n.mode2PulseDots > 0 && !n.lineEdgePulseStarted) {
            n.mode2PulseDots--;
            if (n.mode2PulseDots == 0) {
                n.mode2PulseArmed = false;
            }
        }

        int earlyLineEdge = firstLine ? 451 : 452;
        if (!n.lineRollover && n.dot == earlyLineEdge) {
            if (line != 153) {
                n.lyCounter = line + 1;
                n.coincidence = false;
                n.coincidenceEdgeHold = false;
                n.coincidenceSettleDots = 0;
            }

            // The early M2 strobe exists on active lines, including line 143. Its pulse crosses
            // the nominal boundary and ends after dot 3 of the following line.
            if (line < 144) {
                n.mode2PulseDots = 8;
                n.mode2PulseArmed = (statEnable & STAT_M2) != 0;
                n.lineEdgePulseStarted = true;
            }

            // OAM read ownership starts early only when the following line performs a scan.
            if (line < 143 || line == 153 || firstLine) {
                n.oamReadLocked = true;
            }
        }

        if (n.line == 153 && n.dot == 4) {
            n.coincidence = false;
            n.intCoincidence = false;
            n.coincidenceEdgeHold = false;
            n.coincidenceSettleDots = 0;
        } else if (n.line == 153 && n.dot == 8) {
            n.lyCounter = 0;
            n.registeredLy = 0;
            n.coincidence = lyc == 0;
            n.intCoincidence = false;
            n.coincidenceEdgeHold = n.coincidence;
            n.coincidenceSettleDots = 4;
            n.captureCoincidence = true;
        } else if (!n.captureCoincidence && n.coincidenceSettleDots > 0) {
            n.coincidenceSettleDots--;
            if (n.coincidenceSettleDots == 0) {
                n.intCoincidence = n.coincidence;
                n.coincidenceEdgeHold = false;
            }
        }

        if (n.line < 144) {
            int transferStart = n.firstLine ? 79 : 80;
            if (n.dot == transferStart) {
                n.readableMode = MODE_TRANSFER;
                n.oamReadLocked = true;
                n.oamWriteLocked = true;
                n.vramReadLocked = true;
                n.vramWriteLocked = true;
            } else if (!n.firstLine && n.dot == 76) {
                n.vramReadLocked = true;
                n.oamWriteLocked = false;
            }

            if (n.dot == pixelEndDot + 1) {
                n.readableMode = MODE_HBLANK;
                n.oamReadLocked = false;
                n.oamWriteLocked = false;
                n.vramReadLocked = false;
                n.vramWriteLocked = false;
            }
            if (n.dot == pixelEndDot + 4) {
                n.mode0Source = true;
            }
        }

        if (n.line == 153 && n.dot == 452) {
            // The readable mode mux is independent of the internal VBlank source.
            n.readableMode = MODE_HBLANK;
        }
        return n;
    }

    private void commit(Next n) {
        firstLine = n.firstLine;
        line = n.line;
        dot = n.dot;
        lyCounter = n.lyCounter;
        registeredLy = n.registeredLy;
        readableMode = n.readableMode;
        coincidence = n.coincidence;
        intCoincidence = n.intCoincidence;
        coincidenceEdgeHold = n.coincidenceEdgeHold;
        coincidenceSettleDots = n.coincidenceSettleDots;
        internalVblank = n.internalVblank;
        mode2PulseDots = n.mode2PulseDots;
        mode2PulseArmed = n.mode2PulseArmed;
        mode0Source = n.mode0Source;
        vblankIf = n.vblankIf;
        oamReadLocked = n.oamReadLocked;
        oamWriteLocked = n.oamWriteLocked;
        vramReadLocked = n.vramReadLocked;
        vramWriteLocked = n.vramWriteLocked;
    }

    void writeStat(int value) {
        int newEnable = value & 0x78;
        if (!lcdEnabled) {
            statEnable = newEnable;
            settleStatLine(false);
            return;
        }

        int transientEnable = 0x78;
        settleStatLine(rawStatLine(transientEnable), false);
        statEnable = newEnable;
        settleStatLine(rawStatLine(), false);
    }

    void writeLyc(int value) {
        lyc = value & 0xff;
        if (!lcdEnabled) {
            return;
        }
        coincidence = registeredLy == lyc;
        intCoincidence = coincidence;
        coincidenceEdgeHold = false;
        coincidenceSettleDots = 0;
        settleStatLine(rawStatLine(), false);
    }

    void clearLcdcIf() {
        // IF is a clear-dominant latch. Keeping statLine high does not create another edge.
        settleStatLine(rawStatLine(), true);
    }

    void clearVblankIf() {
        vblankIf = false;
    }

    void disableLcd() {
        if (!lcdEnabled) {
            return;
        }
        lcdEnabled = false;
        firstLine = false;
        line = 0;
        dot = 0;
        lyCounter = 0;
        registeredLy = 0;
        readableMode = MODE_HBLANK;
        internalVblank = false;
        mode2PulseDots = 0;
        mode2PulseArmed = false;
        mode0Source = false;
        coincidenceEdgeHold = false;
        coincidenceSettleDots = 0;
        oamReadLocked = false;
        oamWriteLocked = false;
        vramReadLocked = false;
        vramWriteLocked = false;
        settleStatLine(false, false);
    }

    void enableLcd() {
        if (lcdEnabled) {
            return;
        }
        lcdEnabled = true;
        firstLine = true;
        line = 0;
        dot = -1;
        lyCounter = 0;
        registeredLy = 0;
        readableMode = MODE_HBLANK;
        coincidence = lyc == 0;
        intCoincidence = coincidence;
        coincidenceEdgeHold = false;
        coincidenceSettleDots = 0;
        internalVblank = false;
        mode2PulseDots = 0;
        mode2PulseArmed = false;
        mode0Source = false;
        settleStatLine(rawStatLine(), false);
    }

    private boolean rawStatLine() {
        return rawStatLine(statEnable);
    }

    private boolean rawStatLine(int enable) {
        return lcdEnabled && (((enable & STAT_LYC) != 0
                && (intCoincidence || coincidenceEdgeHold))
                || (enable & STAT_M0) != 0 && mode0Source
                || (enable & STAT_M1) != 0 && internalVblank
                || (enable & STAT_M2) != 0 && mode2PulseDots > 0 && mode2PulseArmed);
    }

    private boolean rawStatLineBeforeComparatorCapture(Next n) {
        return lcdEnabled && (((statEnable & STAT_LYC) != 0 && n.intCoincidence)
                || (statEnable & STAT_M0) != 0 && n.mode0Source
                || (statEnable & STAT_M1) != 0 && n.internalVblank
                || (statEnable & STAT_M2) != 0
                && n.mode2PulseDots > 0 && n.mode2PulseArmed);
    }

    private void settleStatLine(boolean newLine, boolean clearIf) {
        boolean rising = newLine && !statLine;
        statLine = newLine;
        if (clearIf) {
            lcdcIf = false;
        } else if (rising) {
            lcdcIf = true;
        }
    }

    private void settleStatLine(boolean clearIf) {
        settleStatLine(rawStatLine(), clearIf);
    }

    int visibleLy() {
        return !lcdEnabled ? 0 : line == 153 ? 0 : lyCounter;
    }

    int line() {
        return line;
    }

    int dot() {
        return dot;
    }

    boolean firstLine() {
        return firstLine;
    }

    int readableMode() {
        return readableMode;
    }

    boolean coincidence() {
        return coincidence;
    }

    boolean coincidenceContribution() {
        return intCoincidence;
    }

    boolean coincidenceEdgeHeld() {
        return coincidenceEdgeHold;
    }

    boolean mode0Source() {
        return mode0Source;
    }

    boolean mode1Source() {
        return internalVblank;
    }

    boolean mode2Source() {
        return mode2PulseDots > 0;
    }

    boolean statLine() {
        return statLine;
    }

    boolean lcdcIf() {
        return lcdcIf;
    }

    boolean vblankIf() {
        return vblankIf;
    }

    boolean oamReadLocked() {
        return oamReadLocked;
    }

    boolean oamWriteLocked() {
        return oamWriteLocked;
    }

    boolean vramReadLocked() {
        return vramReadLocked;
    }

    boolean vramWriteLocked() {
        return vramWriteLocked;
    }

    Set<Falsifier> unresolvedFalsifiers() {
        return Set.copyOf(unresolved);
    }

    private static final class Next {

        private boolean firstLine;
        private int line;
        private int dot;
        private int lyCounter;
        private int registeredLy;
        private int readableMode;
        private boolean coincidence;
        private boolean intCoincidence;
        private boolean coincidenceEdgeHold;
        private int coincidenceSettleDots;
        private boolean internalVblank;
        private int mode2PulseDots;
        private boolean mode2PulseArmed;
        private boolean mode0Source;
        private boolean vblankIf;
        private boolean oamReadLocked;
        private boolean oamWriteLocked;
        private boolean vramReadLocked;
        private boolean vramWriteLocked;
        private boolean lineRollover;
        private boolean captureCoincidence;
        private boolean lineEdgePulseStarted;

        private Next(DmgStatControlPlane q) {
            firstLine = q.firstLine;
            line = q.line;
            dot = q.dot;
            lyCounter = q.lyCounter;
            registeredLy = q.registeredLy;
            readableMode = q.readableMode;
            coincidence = q.coincidence;
            intCoincidence = q.intCoincidence;
            coincidenceEdgeHold = q.coincidenceEdgeHold;
            coincidenceSettleDots = q.coincidenceSettleDots;
            internalVblank = q.internalVblank;
            mode2PulseDots = q.mode2PulseDots;
            mode2PulseArmed = q.mode2PulseArmed;
            mode0Source = q.mode0Source;
            vblankIf = q.vblankIf;
            oamReadLocked = q.oamReadLocked;
            oamWriteLocked = q.oamWriteLocked;
            vramReadLocked = q.vramReadLocked;
            vramWriteLocked = q.vramWriteLocked;
        }
    }
}
