package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import java.util.EnumSet;
import java.util.Set;

/**
 * Bounded, test-only composition of the DMG LCDC.1 object path.
 *
 * <p><strong>Evidence label: external-netlist-shaped data/control cone.</strong> In dmg-sim
 * revision {@value #NETLIST_REVISION}, FF40.D1 is the {@code xylo} latch output. Its only direct
 * consumers are {@code aror}, which gates the ten OAM-X match terms, and {@code woxa}/{@code
 * xula}, which mask bit seven of the two object shift banks at the output mixer. It is not an
 * input of the VRAM byte latches or the {@code sprite_px_a[0..7]}/{@code sprite_px_b[0..7]}
 * shift registers.
 *
 * <p>A CPU-reachable no-delay probe at the same pinned revision observes two write apertures. A
 * D1 fall before the first object-byte transaction withdraws the match and launches no data. A
 * D1 fall after the low byte has committed, coincident with the high-byte address, withdraws the
 * match and output immediately while the high byte, shift-bank load, and subsequent pixel clocks
 * still retire. A no-delay OBJ-stays-enabled control has the same read, latch, load, and shift
 * times. The delayed-cell probe covers only that late disabled aperture and preserves its causal
 * ordering despite propagation glitches. The assembly supplies only CPU-visible setup and
 * writes; the wrapper passively observes the model's own internal strobes.
 *
 * <p>The Java {@link Inputs} are a semantic transcription of that ownership boundary, not an
 * independent external validation. There is no three-dot replay, position edit, FIFO patch, or
 * timestamp compensation in this composition: control can invalidate only a future transaction,
 * while a committed byte flight owns its remaining captures.
 *
 * <p>This is evidence about one reverse-engineered DMG-B simulation topology, not a measurement
 * of silicon and not an absolute-timing oracle. The delayed-cell run is used only to check that
 * propagation delays do not reverse the causal ordering; its picosecond timestamps and glitches
 * are deliberately not represented here. Probe coverage is finite: DMG mode, LY 1, one sprite
 * in OAM slot 0 at screen X 32, row 0 of tile 1, and the two no-delay write apertures above; only
 * the late aperture was repeated with delayed cells. The build uses dmg-sim's simplified OAM
 * storage macro, so a change in that macro's interaction with fetch control is an explicit
 * falsifier rather than an assumed equivalence.
 *
 * <p>The relevant external anchors are {@code dmg_cpu_b.sv:5751-5757,12142-12148,
 * 13456-13462,16182-16187,26383-27191,26619-26675,28118-28124,34940-34945,
 * 36839-36844,37142-37151}. The external model has two physical shift banks rather than a
 * software object FIFO. This cone preserves that ownership boundary only; OAM selection,
 * priority/overlap, X flip, exact read strobes, background stalls, LCD phasing, and CGB remain
 * outside it.
 */
final class DmgObjectFlightGateCone {

    static final String NETLIST_REVISION = "ee559e1d963e1cc522df512e3bae1b4e5ff96fb5";

    enum Evidence {
        STATIC_THREE_CONSUMER_FF40_D1_CONE,
        IVERILOG_NODELAY_TWO_APERTURE_CPU_PROBE,
        IVERILOG_DEFAULT_DELAY_LATE_CPU_PHASE_PROBE,
        IVERILOG_NODELAY_ENABLED_CONTROL_TRACE
    }

    enum InputBoundary {
        DMG_LCD_ENABLED_MODE3,
        OAM_X_COMPARATOR_LEVEL,
        VRAM_LOW_BYTE_CAPTURE,
        VRAM_HIGH_BYTE_CAPTURE,
        SHIFT_BANK_LOAD_EDGE,
        PIXEL_SHIFT_CLOCK,
        CPU_VISIBLE_FF40_D1
    }

    enum Falsifier {
        PRE_LOW_D1_FALL_STILL_LAUNCHES_A_BYTE,
        POST_LOW_D1_FALL_RECALLS_THE_HIGH_BYTE,
        D1_LOW_STOPS_AN_ALREADY_LOADED_SHIFT_BANK,
        D1_LOW_LEAKS_AN_OBJECT_OUTPUT_BIT,
        A_DIFFERENT_OAM_SLOT_BYPASSES_AROR,
        A_DIFFERENT_X_ROW_OR_TILE_CHANGES_RETIREMENT,
        XFLIP_OR_PRIORITY_USES_A_DIFFERENT_RETIREMENT_PATH,
        AN_UNPROBED_CPU_WRITE_APERTURE_REORDERS_THE_CAPTURE,
        DEFAULT_DELAY_EARLY_APERTURE_DOES_NOT_CANCEL,
        SIMPLIFIED_OAM_MACRO_CHANGES_FETCH_CONTROL,
        PHYSICAL_DMG_DIFFERS_FROM_THE_REVERSE_ENGINEERED_MODEL,
        CGB_TOPOLOGY_DIFFERS
    }

    record Inputs(
            boolean objEnable,
            boolean oamXComparator,
            boolean lowByteCapture,
            boolean highByteCapture,
            boolean shiftBankLoad,
            boolean pixelShiftClock,
            int vramData) {

        Inputs {
            if ((vramData & ~0xff) != 0) {
                throw new IllegalArgumentException("VRAM data must be a byte");
            }
        }

        static Inputs idleEnabled() {
            return new Inputs(true, false, false, false, false, false, 0xff);
        }

        Inputs withObjEnable(boolean value) {
            return new Inputs(value, oamXComparator, lowByteCapture, highByteCapture,
                    shiftBankLoad, pixelShiftClock, vramData);
        }

        Inputs withComparator(boolean value) {
            return new Inputs(objEnable, value, lowByteCapture, highByteCapture,
                    shiftBankLoad, pixelShiftClock, vramData);
        }

        Inputs captureLow(int value) {
            return new Inputs(objEnable, oamXComparator, true, false,
                    shiftBankLoad, pixelShiftClock, value);
        }

        Inputs captureHigh(int value) {
            return new Inputs(objEnable, oamXComparator, false, true,
                    shiftBankLoad, pixelShiftClock, value);
        }

        Inputs onLoadEdge() {
            return new Inputs(objEnable, oamXComparator, lowByteCapture, highByteCapture,
                    true, pixelShiftClock, vramData);
        }

        Inputs onPixelClock() {
            return new Inputs(objEnable, oamXComparator, lowByteCapture, highByteCapture,
                    shiftBankLoad, true, vramData);
        }
    }

    record Observation(
            boolean objEnable,
            boolean matchGate,
            boolean matchToken,
            boolean lowByteValid,
            boolean highByteValid,
            boolean shiftBankLoaded,
            int planeA,
            int planeB,
            int outputA,
            int outputB) {
    }

    record State(
            boolean matchToken,
            boolean lowByteValid,
            int lowByte,
            boolean highByteValid,
            int highByte,
            int planeA,
            int planeB) {
    }

    private boolean matchToken;

    private boolean lowByteValid;

    private int lowByte;

    private boolean highByteValid;

    private int highByte;

    private int planeA;

    private int planeB;

    static Set<Evidence> evidence() {
        return Set.copyOf(EnumSet.allOf(Evidence.class));
    }

    static Set<InputBoundary> inputBoundaries() {
        return Set.copyOf(EnumSet.allOf(InputBoundary.class));
    }

    static Set<Falsifier> falsifiers() {
        return Set.copyOf(EnumSet.allOf(Falsifier.class));
    }

    Observation step(Inputs inputs) {
        if (inputs == null) {
            throw new NullPointerException("inputs");
        }

        boolean matchGate = inputs.objEnable() && inputs.oamXComparator();
        if (matchGate && !lowByteValid && !highByteValid) {
            matchToken = true;
        }

        // AROR can withdraw a comparator which has not launched a byte. Once the low-byte
        // receiver has captured, no FF40.D1 fanout exists in the remaining data path.
        if (!inputs.objEnable() && !lowByteValid) {
            matchToken = false;
        }
        if (inputs.lowByteCapture() && matchToken) {
            lowByte = inputs.vramData();
            lowByteValid = true;
            matchToken = false;
        }
        if (inputs.highByteCapture() && lowByteValid) {
            highByte = inputs.vramData();
            highByteValid = true;
        }

        boolean shiftBankLoaded = inputs.shiftBankLoad() && lowByteValid && highByteValid;
        if (shiftBankLoaded) {
            // The external names A/B correspond to the second/first tile bytes in the probe.
            planeA = highByte;
            planeB = lowByte;
            lowByteValid = false;
            highByteValid = false;
        } else if (inputs.pixelShiftClock()) {
            planeA = planeA << 1 & 0xff;
            planeB = planeB << 1 & 0xff;
        }

        // Woxa/Xula are ordinary output AND gates. They do not clear either shift bank.
        int outputA = inputs.objEnable() ? planeA >>> 7 : 0;
        int outputB = inputs.objEnable() ? planeB >>> 7 : 0;
        return new Observation(inputs.objEnable(), matchGate, matchToken,
                lowByteValid, highByteValid, shiftBankLoaded,
                planeA, planeB, outputA, outputB);
    }

    State capture() {
        return new State(matchToken, lowByteValid, lowByte, highByteValid, highByte,
                planeA, planeB);
    }

    void restore(State state) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        checkByte(state.lowByte(), "low byte");
        checkByte(state.highByte(), "high byte");
        checkByte(state.planeA(), "plane A");
        checkByte(state.planeB(), "plane B");
        matchToken = state.matchToken();
        lowByteValid = state.lowByteValid();
        lowByte = state.lowByte();
        highByteValid = state.highByteValid();
        highByte = state.highByte();
        planeA = state.planeA();
        planeB = state.planeB();
    }

    private static void checkByte(int value, String name) {
        if ((value & ~0xff) != 0) {
            throw new IllegalArgumentException(name + " must be a byte");
        }
    }
}
