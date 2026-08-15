package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import eu.rekawek.coffeegb.core.signal.Dff;
import eu.rekawek.coffeegb.core.signal.SrLatch;

/**
 * The small DMG LCDC.7 reset and panel-clock cone, kept detached from production.
 *
 * <p>This is a literal Boolean reduction of the relevant nodes in the offline DMG-CPU-B
 * netlist. XONA stores FF40.7. XEBE is the inverse of the active-high hard reset, XODO is
 * {@code NAND(XONA, XEBE)}, and XAPO inverts XODO to make {@code ppu_reset_n}. LCDC.7 is
 * consequently not a command interpreted by a raster machine: it is one input of the
 * asynchronous reset tree.
 *
 * <p>The LCD panel clocks do not simply stop while that reset is asserted. KAHE selects
 * VCLK while FF40.7 is high and the inverted 8192 Hz divider output while it is low; KUPA
 * similarly selects the ordinary gate clock or the inverted 4096 Hz divider output. These
 * slow off-state clocks keep the physical LCD driven independently of the reset PPU datapath.
 *
 * <p><strong>Evidence label: external-netlist Boolean root plus fitted consumer fanout.</strong>
 * The XONA/XEBE/XODO/XAPO reduction and panel-clock muxes are netlist observations. This class does
 * not prove that every candidate Java scanout/fetch stage has a physical reset pin on XAPO; that
 * fanout must be traced independently or falsified with LCD-off phase sweeps.
 */
final class DmgLcdPowerClockTopology {

    /** XONA.Q, the stored FF40.7 value. */
    private final Dff ff40D7 = new Dff(SrLatch.Dominance.CLEAR, false);

    /** Active-low package reset after the PPU hard-reset buffer. */
    private boolean ppuHardResetN = true;

    /** On-state panel clock sources. */
    private boolean vclk;

    private boolean gateClock;

    /** Free-running divider nodes selected while LCDC.7 is low. */
    private boolean divider8192;

    private boolean divider4096;

    DmgLcdPowerClockTopology(boolean lcdEnabled) {
        ff40D7.restore(lcdEnabled);
    }

    /** Drives the FF40.7 data latch. An asserted hard reset dominates the write input. */
    void writeLcdc7(boolean enabled) {
        ff40D7.resolve(enabled, true, false, !ppuHardResetN);
        ff40D7.commit();
    }

    /** Drives the package-reset side of XONA/XEBE. */
    void drivePpuHardResetN(boolean high) {
        ppuHardResetN = high;
        if (!high) {
            ff40D7.resolve(false, false, false, true);
            ff40D7.commit();
        }
    }

    void drivePanelClockSources(
            boolean vclk, boolean gateClock, boolean divider8192, boolean divider4096) {
        this.vclk = vclk;
        this.gateClock = gateClock;
        this.divider8192 = divider8192;
        this.divider4096 = divider4096;
    }

    boolean ff40D7() {
        return ff40D7.q();
    }

    /** XEBE = NOT(PPU_HARD_RESET). */
    boolean xebe() {
        return ppuHardResetN;
    }

    /** XODO = NAND(FF40_D7, XEBE). */
    boolean xodo() {
        return !(ff40D7.q() && xebe());
    }

    /** XAPO = NOT(XODO). This is the shared active-low PPU reset tree input. */
    boolean ppuResetN() {
        return !xodo();
    }

    /** CPL pad after KAHE/KYMO and the output-pad inversion. */
    boolean cplPad() {
        return ff40D7.q() ? vclk : !divider8192;
    }

    /** CPG pad after KUPA/KOFO and the output-pad inversion. */
    boolean cpgPad() {
        return ff40D7.q() ? gateClock : !divider4096;
    }
}
