package eu.rekawek.coffeegb.core.experimental.joypad;

import eu.rekawek.coffeegb.core.signal.Dff;

import static eu.rekawek.coffeegb.core.signal.SrLatch.Dominance.CLEAR;

/**
 * Test-only transcription of the DMG-CPU-B JOYP interrupt and wake-up cone.
 *
 * <p><strong>Evidence label: static schematic/netlist connectivity.</strong> In
 * {@code dmg_cpu_b/ff00_joyp.kicad_sch}, KERY ORs the four active-low P10-P13 pad inputs.
 * BATU, ACEF, AGEM, and APUG are resettable D flip-flops clocked in parallel by BOGA's
 * {@code CLK_1MHz}; their data path is KERY -> BATU -> ACEF -> AGEM -> APUG. ASOK computes
 * {@code BATU & APUG}, and its rising edge clocks the Joypad IF latch ULak. AWOB is a separate
 * transparent latch enabled by {@code CLK_1MHz} and drives the CPU wake input directly.
 *
 * <p>The source snapshot is the {@code dmg-schematics} repository at revision
 * {@value #SCHEMATIC_REVISION}. The matching raw-netlist statements are in
 * {@code netlist/io/right.nl}, {@code netlist/right-col/{a,b,k}.nl}, and
 * {@code netlist/top-center-row/b.nl}. This cone intentionally stops at the rising ASOK wire;
 * FF0F bus arbitration and CPU STOP/HALT behavior belong to other islands.</p>
 */
final class DmgJoypadInterruptGateCone {

    static final String SCHEMATIC_REVISION =
            "02399f96e0893783c130cf6f03fad7a1148ae60a";

    record Observation(
            boolean kery,
            boolean batu,
            boolean acef,
            boolean agem,
            boolean apug,
            boolean asok,
            boolean asokRising,
            boolean cpuWakeup) {
    }

    private final Dff batu = new Dff(CLEAR, false);
    private final Dff acef = new Dff(CLEAR, false);
    private final Dff agem = new Dff(CLEAR, false);
    private final Dff apug = new Dff(CLEAR, false);

    /** AWOB.q; unlike the interrupt filter this is a transparent, unreset latch. */
    private boolean cpuWakeup;

    /**
     * Resolves one settled input vector.
     *
     * @param lowInputLines bit {@code n} is one when physical P1n is low
     * @param clk1MhzHigh current BOGA output level, used as AWOB's transparent enable
     * @param clk1MhzRising whether this vector contains BOGA's rising edge
     */
    Observation step(int lowInputLines, boolean clk1MhzHigh, boolean clk1MhzRising) {
        if ((lowInputLines & ~0x0f) != 0) {
            throw new IllegalArgumentException("P10-P13 mask exceeds four bits");
        }
        if (clk1MhzRising && !clk1MhzHigh) {
            throw new IllegalArgumentException("a rising edge must finish high");
        }

        boolean kery = lowInputLines != 0;
        boolean oldAsok = asok();

        // All four DFFs see the same edge and therefore capture the pre-edge Q vector.
        batu.resolve(kery, clk1MhzRising, false, false);
        acef.resolve(batu.q(), clk1MhzRising, false, false);
        agem.resolve(acef.q(), clk1MhzRising, false, false);
        apug.resolve(agem.q(), clk1MhzRising, false, false);
        batu.commit();
        acef.commit();
        agem.commit();
        apug.commit();

        // AWOB follows KERY throughout BOGA's high level, then retains while it is low.
        if (clk1MhzHigh) {
            cpuWakeup = kery;
        }

        boolean newAsok = asok();
        return new Observation(kery, batu.q(), acef.q(), agem.q(), apug.q(),
                newAsok, !oldAsok && newAsok, cpuWakeup);
    }

    private boolean asok() {
        return batu.q() && apug.q();
    }
}
