package eu.rekawek.coffeegb.core.signal.experimental;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Test-only OAM control/bit-line experiment.
 *
 * <p><strong>Evidence label: external gate trace for the coarse mechanism plus a fitted exact-data
 * hypothesis.</strong> The trace supports sticky word lines, carry-skewed address rails, retained
 * bit lines, and read feedback. The directional sample/feedback split and regular-row carry
 * aperture are inferred to fit verified behavior; the exact-data differential uses production
 * {@code SpriteBug} as its oracle. It is therefore not independent evidence that the fitted split
 * exists in silicon.
 *
 * <p>The model intentionally has no corruption operation and no row-number cases. A scan address
 * advances through a two-stage transparent carry window. Word lines are sticky inside that
 * window, so the intermediate binary addresses become ordinary SRAM selections. The data port has
 * four retained bit lines, a common-line keeper, and separate sample and feedback gates. The latter
 * distinction is the single extra degree of freedom missing from {@link DynamicSramExperimentTest}'s
 * deliberately symmetric common-line model.
 *
 * <p>Provenance is intentionally precise. In the external DMG gate oracle at revision
 * {@code ee559e1}, scope {@code dmg_cpu_b_gameboy.dmg.oam_a_inst.sram_inst} exposes address rails
 * {@code a/a_n}, sticky {@code wl_new/wl}, column select {@code col}, the four retained bit lines,
 * common line {@code com}, and the independent {@code wldrv_pch_n}/{@code bl_pch_n} controls. The
 * row-2 trace showed {@code a: 1 -> 0 -> 2} and {@code wl: 0x2 -> 0x3 -> 0x7} without bit-line
 * precharge; the column decoder then changed {@code col: 0x4 -> 0 -> 1}. The column pins are driven
 * by the external netlist's {@code wafa/wyxy/wexe/wazu} decoder cells. Those observations establish
 * sticky selection, carry-skewed address rails, and column sampling. They do <em>not</em> establish
 * directional feedback: the split sample/feedback gate below is the minimal fitted topology that
 * removes the symmetric model's executable preservation falsifier, not a claimed netlist fact.
 */
final class OamSramControlCone {

    enum Falsifier {
        ROW_16_TO_ROW_0_ALIAS_FEEDBACK,
        FIRST_AND_LAST_SCAN_LATCHES,
        EXACT_TRANSISTOR_DELAY_APERTURE,
        READ_CORRUPTION_VALUE_CONE,
        CGB_OAM_TOPOLOGY
    }

    private static final int COLUMN_COUNT = 4;

    private static final int WORD_MASK = 0xffff;

    private final int[][] memory;

    private final int[] bitLines = new int[COLUMN_COUNT];

    private int validBitLines;

    private int selectedRows;

    private int keeper;

    private boolean keeperValid;

    OamSramControlCone(int[][] words) {
        memory = new int[words.length][COLUMN_COUNT];
        for (int row = 0; row < words.length; row++) {
            if (words[row].length != COLUMN_COUNT) {
                throw new IllegalArgumentException("each OAM row must contain four words");
            }
            for (int column = 0; column < COLUMN_COUNT; column++) {
                memory[row][column] = words[row][column] & WORD_MASK;
            }
        }
    }

    /**
     * Applies the physical control shape of a blocked write while the PPU owns the OAM port.
     *
     * <p>The target's first word first charges the output keeper. The preceding row then claims
     * the four precharged local bit lines. Selecting the target with those lines retained copies
     * the row. Finally columns zero and two both sample onto the common line while only column zero
     * has its feedback gate open. Common-line disagreement therefore resolves through the retained
     * target value, but the contributing third column is not overwritten. Separate sample and
     * feedback gates are an inferred minimum: the available oracle exposes only a symmetric
     * behavioral SRAM boundary, not these two physical enables.
     */
    void blockedWriteDuringScan(int targetRow) {
        if (targetRow < 1 || targetRow >= memory.length) {
            return;
        }

        prechargeWordAndBitLines();
        selectRow(targetRow);
        sampleAndFeedback(1, 1);

        prechargeWordAndBitLines();
        selectRow(targetRow - 1);

        prechargeWordLines();
        selectRow(targetRow);

        sampleAndFeedback((1 << 0) | (1 << 2), 1 << 0);
    }

    int word(int row, int column) {
        return memory[row][column];
    }

    int[] row(int row) {
        return memory[row].clone();
    }

    static Set<Falsifier> falsifiers() {
        return Collections.unmodifiableSet(EnumSet.allOf(Falsifier.class));
    }

    /**
     * Word-line selections produced by an increment into {@code scanRow}.
     *
     * <p>The old address is already selected. At most two low address rails clear while the
     * decoder remains transparent; the final address then arrives. This is a circuit aperture,
     * not a test of {@code scanRow % 4}.
     */
    static List<Integer> stickyRowsDuringScanIncrement(int scanRow) {
        if (scanRow < 1 || scanRow > 19) {
            throw new IllegalArgumentException("regular OAM scan rows are 1..19");
        }

        List<Integer> rows = new ArrayList<>();
        int transientAddress = scanRow - 1;
        rows.add(transientAddress);
        for (int bit = 0; bit < 2 && (transientAddress & (1 << bit)) != 0; bit++) {
            transientAddress &= ~(1 << bit);
            rows.add(transientAddress);
        }
        rows.add(scanRow);
        return Collections.unmodifiableList(rows);
    }

    private void prechargeWordAndBitLines() {
        selectedRows = 0;
        validBitLines = 0;
    }

    private void prechargeWordLines() {
        selectedRows = 0;
    }

    private void selectRow(int row) {
        selectedRows |= 1 << row;
        int firstSelectedRow = Integer.numberOfTrailingZeros(selectedRows);
        for (int column = 0; column < COLUMN_COUNT; column++) {
            int mask = 1 << column;
            if ((validBitLines & mask) == 0) {
                bitLines[column] = memory[firstSelectedRow][column];
                validBitLines |= mask;
            }
        }
        writeBackRetainedBitLines();
    }

    private void sampleAndFeedback(int sampleColumns, int feedbackColumns) {
        int drivenColumns = sampleColumns & validBitLines;
        if (drivenColumns == 0) {
            return;
        }

        int drivenHigh = 0;
        int drivenLow = 0;
        for (int column = 0; column < COLUMN_COUNT; column++) {
            if ((drivenColumns & (1 << column)) != 0) {
                drivenHigh |= bitLines[column];
                drivenLow |= ~bitLines[column] & WORD_MASK;
            }
        }
        int contention = drivenHigh & drivenLow;
        if (contention != 0 && !keeperValid) {
            throw new IllegalStateException("common-line contention without retained charge");
        }
        int resolved = (drivenHigh & ~contention) | (keeper & contention);

        int receivingColumns = feedbackColumns & drivenColumns;
        for (int column = 0; column < COLUMN_COUNT; column++) {
            if ((receivingColumns & (1 << column)) != 0) {
                bitLines[column] = resolved;
            }
        }
        keeper = resolved;
        keeperValid = true;
        writeBackRetainedBitLines();
    }

    private void writeBackRetainedBitLines() {
        for (int row = 0; row < memory.length; row++) {
            if ((selectedRows & (1 << row)) == 0) {
                continue;
            }
            for (int column = 0; column < COLUMN_COUNT; column++) {
                if ((validBitLines & (1 << column)) != 0) {
                    memory[row][column] = bitLines[column];
                }
            }
        }
    }
}
