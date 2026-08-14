package eu.rekawek.coffeegb.core.signal.experimental;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * A test-only transcription of the essential dynamic behavior in dmg-sim's generic_sram.sv.
 *
 * <p>This is deliberately not production code. It tests whether OAM corruption can emerge from
 * ordinary SRAM controls before Coffee GB commits to replacing its hardware-verified formulas.
 */
public class DynamicSramExperimentTest {

    @Test
    public void keeperAndBitLineConflictProduceTheCompleteMajorityTruthTable() {
        for (int current = 0; current < 2; current++) {
            for (int previous = 0; previous < 2; previous++) {
                for (int previousThird = 0; previousThird < 2; previousThird++) {
                    StickyDynamicSram sram = writeCorruptionSetup(
                            1, current, previous, previousThird);

                    applyWriteCorruptionAddressSequence(sram);

                    int expected = (current & previous)
                            | (current & previousThird)
                            | (previous & previousThird);
                    assertEquals(expected, sram.read(CURRENT_ROW, FIRST_COLUMN));
                }
            }
        }
    }

    @Test
    public void sameControlSequenceProducesTheCurrentSpriteBugExampleWithoutItsFormula() {
        StickyDynamicSram sram = writeCorruptionSetup(
                16, 0xaaaa, 0xcccc, 0xf0f0);

        applyWriteCorruptionAddressSequence(sram);

        // SpriteBugTest's hardware-verified ((a ^ c) & (b ^ c)) ^ c result.
        assertEquals(0xe8e8, sram.read(CURRENT_ROW, FIRST_COLUMN));
    }

    @Test
    public void stickyWordLinesCopyASelectedRowWithoutAProgrammedCopyOperation() {
        StickyDynamicSram sram = new StickyDynamicSram(2, 4, 8);
        for (int column = 0; column < 4; column++) {
            sram.write(PREVIOUS_ROW, column, 0x31 + column);
            sram.write(CURRENT_ROW, column, 0xa1 + column);
        }

        sram.prechargeWordAndBitLines();
        sram.addressColumns(0);
        sram.addressRow(PREVIOUS_ROW);
        // Changing address without word-line precharge leaves both rows enabled. The bit-lines
        // were claimed by the preceding row first, so they overwrite the newly selected row.
        sram.addressRow(CURRENT_ROW);

        for (int column = 0; column < 4; column++) {
            assertEquals(0x31 + column, sram.read(CURRENT_ROW, column));
        }
    }

    @Test
    public void sramAloneAlsoCorruptsTheContributingColumnSoIsNotYetAReplacement() {
        StickyDynamicSram sram = writeCorruptionSetup(
                16, 0xaaaa, 0xcccc, 0xf0f0);

        applyWriteCorruptionAddressSequence(sram);

        // generic_sram.sv resolves contention by feeding the keeper back into every selected
        // bit-line. That makes the majority value emerge in column 0, but also changes column 2.
        // SpriteBug's verified write behavior instead preserves/copies 0xf0f0 there. A faithful
        // replacement therefore needs the real OAM control waveform (or another storage latch),
        // not a special-case exception in this generic SRAM.
        assertEquals(0xe8e8, sram.read(CURRENT_ROW, THIRD_COLUMN));
        assertNotEquals(0xf0f0, sram.read(CURRENT_ROW, THIRD_COLUMN));
        assertEquals(0xf0f0, sram.read(PREVIOUS_ROW, THIRD_COLUMN));
    }

    private static final int PREVIOUS_ROW = 0;

    private static final int CURRENT_ROW = 1;

    private static final int FIRST_COLUMN = 0;

    private static final int THIRD_COLUMN = 2;

    private static StickyDynamicSram writeCorruptionSetup(
            int width, int current, int previous, int previousThird) {
        StickyDynamicSram sram = new StickyDynamicSram(2, 4, width);
        sram.write(CURRENT_ROW, FIRST_COLUMN, current);
        sram.write(PREVIOUS_ROW, FIRST_COLUMN, previous);
        sram.write(PREVIOUS_ROW, THIRD_COLUMN, previousThird);
        return sram;
    }

    /**
     * Accesses three ordinary cells through the same precharge, keeper, sticky-word-line, and
     * multi-column controls used by generic_sram.sv. No data-dependent decision appears here.
     */
    private static void applyWriteCorruptionAddressSequence(StickyDynamicSram sram) {
        // Let the current row's first cell charge the common-line keeper (operand a).
        sram.prechargeWordAndBitLines();
        sram.addressColumns(0);
        sram.addressRow(CURRENT_ROW);
        sram.addressColumns(1 << FIRST_COLUMN);

        // Load the preceding row onto isolated bit-lines (operands b and c), preserving a in
        // the common-line keeper across precharge.
        sram.prechargeWordAndBitLines();
        sram.addressColumns(0);
        sram.addressRow(PREVIOUS_ROW);

        // Retain those dynamic bit-line values while the word address changes to the current row.
        sram.prechargeWordLines();
        sram.addressRow(CURRENT_ROW);

        // An overlapping low-address decode joins columns 0 and 2. If b == c that level wins;
        // if they disagree, the retained a level resolves the conflict: majority(a, b, c).
        sram.addressColumns((1 << FIRST_COLUMN) | (1 << THIRD_COLUMN));
    }

    /**
     * Small word-parallel model of generic_sram.sv's storage behavior.
     *
     * <p>Word-line selection sticks until precharge. A floating bit-line is claimed by the first
     * enabled row. Multiple selected columns share a keeper-backed common line, and every enabled
     * row continuously takes the resolved bit-line values.
     */
    private static final class StickyDynamicSram {

        private final int rows;

        private final int columns;

        private final int wordMask;

        private final int[][] memory;

        private final int[] bitLines;

        private int validBitLines;

        private int selectedRows;

        private int selectedColumns;

        private int keeper;

        private boolean keeperValid;

        private StickyDynamicSram(int rows, int columns, int width) {
            if (rows < 1 || rows > 30 || columns < 1 || columns > 30
                    || width < 1 || width > 30) {
                throw new IllegalArgumentException();
            }
            this.rows = rows;
            this.columns = columns;
            this.wordMask = (1 << width) - 1;
            this.memory = new int[rows][columns];
            this.bitLines = new int[columns];
        }

        private void write(int row, int column, int value) {
            memory[row][column] = value & wordMask;
        }

        private int read(int row, int column) {
            return memory[row][column];
        }

        private void prechargeWordAndBitLines() {
            selectedRows = 0;
            validBitLines = 0;
        }

        private void prechargeWordLines() {
            selectedRows = 0;
        }

        /** Address decoding is intentionally sticky until word-line precharge. */
        private void addressRow(int row) {
            selectedRows |= 1 << row;
            settle();
        }

        private void addressColumns(int mask) {
            selectedColumns = mask;
            settle();
        }

        private void settle() {
            if (selectedRows != 0) {
                int firstSelectedRow = Integer.numberOfTrailingZeros(selectedRows);
                for (int column = 0; column < columns; column++) {
                    int columnMask = 1 << column;
                    if ((validBitLines & columnMask) == 0) {
                        bitLines[column] = memory[firstSelectedRow][column];
                        validBitLines |= columnMask;
                    }
                }
            }

            int drivenColumns = selectedColumns & validBitLines;
            if (drivenColumns != 0) {
                int drivenHigh = 0;
                int drivenLow = 0;
                for (int column = 0; column < columns; column++) {
                    if ((drivenColumns & (1 << column)) != 0) {
                        drivenHigh |= bitLines[column];
                        drivenLow |= ~bitLines[column] & wordMask;
                    }
                }
                int contention = drivenHigh & drivenLow;
                if (contention != 0 && !keeperValid) {
                    throw new IllegalStateException("unresolved common line without a keeper");
                }
                int resolved = (drivenHigh & ~contention) | (keeper & contention);
                for (int column = 0; column < columns; column++) {
                    if ((drivenColumns & (1 << column)) != 0) {
                        bitLines[column] = resolved;
                    }
                }
                keeper = resolved;
                keeperValid = true;
            }

            for (int row = 0; row < rows; row++) {
                if ((selectedRows & (1 << row)) != 0) {
                    for (int column = 0; column < columns; column++) {
                        if ((validBitLines & (1 << column)) != 0) {
                            memory[row][column] = bitLines[column];
                        }
                    }
                }
            }
        }
    }
}
