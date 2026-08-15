package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import eu.rekawek.coffeegb.core.signal.Dff;
import eu.rekawek.coffeegb.core.signal.SrLatch;

import static eu.rekawek.coffeegb.core.signal.SrLatch.Dominance.CLEAR;

/**
 * Small, test-only transcription of the DMG window-source activation latch.
 *
 * <p><strong>Evidence label: external-netlist-shaped control cone.</strong> At dmg-sim revision
 * {@code ee559e1}, {@code pyco} samples {@code wxy_match} on {@code roco}, {@code nunu} samples
 * {@code pyco} on {@code mehe}, and the NOR latch {@code pynu} is set by {@code nunu}. Two
 * inverters expose {@code pynu} as {@code in_window}. The latch reset is {@code xofo =
 * NAND(ff40_d5, xahy, ppu_reset_n)}, so a falling LCDC.5 is an ordinary asynchronous reset of an
 * already-active window source, not a renderer callback or pixel repair. {@code ff40_d5} is the
 * output of the FF40 CPU-write latch ({@code wymo}); there is no sampled receiver between that Q
 * and {@code xofo}. Also, {@code xahy} reduces to {@code (anel || !start_oam_parsing) &&
 * ppu_reset_n}. It can independently reset the source at the OAM-parser boundary, but cannot
 * delay the reset caused by a low {@code ff40_d5}.
 *
 * <p>The source anchors are {@code dmg_cpu_b.sv:25651-25760,25890-25902,26955,27253-27360,
 * 36439-36442}. The direct FF40 path is at {@code 34020-34031,35505-35520}; the {@code xahy}
 * reduction is at {@code 4180-4225,6030-6075,8890-8950,9590-9630,35790-35793}. This class
 * preserves only that Boolean/storage topology. The exact {@code ppu_wr} aperture and downstream
 * fetch/data-valid stages remain outside this cone. In particular, the production renderer's
 * observed eight-dot window-path retirement cannot be assigned to this source latch: its
 * asynchronous reset path contains no PPU-clocked stage.
 */
final class DmgWindowSourceLatchCone {

    record Inputs(
            boolean wxMatch,
            boolean matchCaptureEdge,
            boolean startCaptureEdge,
            boolean lcdcWindowEnable,
            boolean xahy,
            boolean ppuResetN) {

        static Inputs idleEnabled() {
            return new Inputs(false, false, false, true, true, true);
        }

        Inputs withMatch(boolean value) {
            return new Inputs(value, matchCaptureEdge, startCaptureEdge, lcdcWindowEnable,
                    xahy, ppuResetN);
        }

        Inputs onMatchEdge() {
            return new Inputs(wxMatch, true, startCaptureEdge, lcdcWindowEnable,
                    xahy, ppuResetN);
        }

        Inputs onStartEdge() {
            return new Inputs(wxMatch, matchCaptureEdge, true, lcdcWindowEnable,
                    xahy, ppuResetN);
        }

        Inputs withLcdcWindowEnable(boolean value) {
            return new Inputs(wxMatch, matchCaptureEdge, startCaptureEdge, value,
                    xahy, ppuResetN);
        }

        Inputs withXahy(boolean value) {
            return new Inputs(wxMatch, matchCaptureEdge, startCaptureEdge, lcdcWindowEnable,
                    value, ppuResetN);
        }

        Inputs withPpuResetN(boolean value) {
            return new Inputs(wxMatch, matchCaptureEdge, startCaptureEdge, lcdcWindowEnable,
                    xahy, value);
        }
    }

    record Observation(
            boolean matchStage,
            boolean startStage,
            boolean inWindow,
            boolean activated,
            boolean deactivated) {
    }

    record State(boolean matchStage, boolean startStage, boolean inWindow) {
    }

    private final Dff matchStage = new Dff(CLEAR, false);

    private final Dff startStage = new Dff(CLEAR, false);

    private final SrLatch inWindow = new SrLatch(CLEAR, false);

    Observation step(Inputs inputs) {
        if (inputs == null) {
            throw new NullPointerException("inputs");
        }
        boolean oldInWindow = inWindow.q();

        // Edge-triggered storage captures the settled pre-edge vector atomically.
        matchStage.resolve(
                inputs.wxMatch(), inputs.matchCaptureEdge(), false, !inputs.ppuResetN());
        startStage.resolve(
                matchStage.q(), inputs.startCaptureEdge(), false, !inputs.ppuResetN());
        matchStage.commit();
        startStage.commit();

        // The NOR latch then settles from the post-edge DFF outputs. Its q output is reset
        // dominant when xofo is high.
        boolean xofo = !(inputs.lcdcWindowEnable()
                && inputs.xahy()
                && inputs.ppuResetN());
        inWindow.resolve(startStage.q(), xofo);
        inWindow.commit();

        return new Observation(
                matchStage.q(),
                startStage.q(),
                inWindow.q(),
                !oldInWindow && inWindow.q(),
                oldInWindow && !inWindow.q());
    }

    State capture() {
        return new State(matchStage.q(), startStage.q(), inWindow.q());
    }

    void restore(State state) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        matchStage.restore(state.matchStage());
        startStage.restore(state.startStage());
        inWindow.restore(state.inWindow());
    }
}
