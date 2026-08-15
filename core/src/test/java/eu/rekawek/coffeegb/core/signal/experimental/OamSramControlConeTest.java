package eu.rekawek.coffeegb.core.signal.experimental;

import eu.rekawek.coffeegb.core.gpu.SpriteBug;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OamSramControlConeTest {

    @Test
    public void fittedTopologyPublishesItsFiniteFalsifierBoundary() {
        assertEquals(EnumSet.allOf(OamSramControlCone.Falsifier.class),
                OamSramControlCone.falsifiers());
    }

    @Test
    public void twoTransparentCarryStagesDeriveEveryRegularReadCopyFootprint() {
        for (int scanRow = 1; scanRow <= 19; scanRow++) {
            Set<Integer> productionFootprint = possibleChangedRows(scanRow);
            Set<Integer> signalFootprint =
                    new LinkedHashSet<>(OamSramControlCone.stickyRowsDuringScanIncrement(scanRow));

            // Row 16 has a separate wrap/alias feedback after the ordinary scan-address hazard.
            if (scanRow == 16) {
                assertEquals(setOf(15, 14, 12, 16, 0), productionFootprint);
                assertEquals(setOf(15, 14, 12, 16), signalFootprint);
            } else {
                assertEquals("scan row " + scanRow, productionFootprint, signalFootprint);
            }
        }
    }

    @Test
    public void carryApertureExplainsModuloFamiliesWithoutModuloLogic() {
        assertEquals(asList(0, 1), OamSramControlCone.stickyRowsDuringScanIncrement(1));
        assertEquals(asList(1, 0, 2), OamSramControlCone.stickyRowsDuringScanIncrement(2));
        assertEquals(asList(3, 2, 0, 4), OamSramControlCone.stickyRowsDuringScanIncrement(4));
        assertEquals(asList(7, 6, 4, 8), OamSramControlCone.stickyRowsDuringScanIncrement(8));
        assertEquals(asList(11, 10, 8, 12), OamSramControlCone.stickyRowsDuringScanIncrement(12));
        assertEquals(asList(15, 14, 12, 16), OamSramControlCone.stickyRowsDuringScanIncrement(16));
    }

    @Test
    public void directionalFeedbackMakesBlockedWriteCorruptionEmergent() {
        for (int current = 0; current < 2; current++) {
            for (int previous = 0; previous < 2; previous++) {
                for (int previousThird = 0; previousThird < 2; previousThird++) {
                    int[][] words = {
                            {previous, 0, previousThird, 0},
                            {current, 1, 1, 1}
                    };
                    OamSramControlCone cone = new OamSramControlCone(words);

                    cone.blockedWriteDuringScan(1);

                    int majority = (current & previous)
                            | (current & previousThird)
                            | (previous & previousThird);
                    assertArrayEquals(
                            new int[]{majority, 0, previousThird, 0}, cone.row(1));
                    assertArrayEquals(
                            new int[]{previous, 0, previousThird, 0}, cone.row(0));
                }
            }
        }
    }

    @Test
    public void wordParallelConeMatchesProductionBlockedWriteForRandomOam() {
        Random random = new Random(0x0a4c0ffeL);
        for (int iteration = 0; iteration < 4096; iteration++) {
            int[][] words = new int[20][4];
            Ram production = new Ram(0xfe00, 0xa0);
            for (int row = 0; row < 20; row++) {
                for (int column = 0; column < 4; column++) {
                    int value = random.nextInt(0x10000);
                    words[row][column] = value;
                    setWord(production, row, column, value);
                }
            }
            int targetRow = 1 + random.nextInt(19);
            OamSramControlCone cone = new OamSramControlCone(words);

            SpriteBug.corruptOamWrite(production, targetRow);
            cone.blockedWriteDuringScan(targetRow);

            for (int row = 0; row < 20; row++) {
                for (int column = 0; column < 4; column++) {
                    assertEquals(
                            "iteration " + iteration + ", row " + row + ", column " + column,
                            word(production, row, column), cone.word(row, column));
                }
            }
        }
    }

    @Test
    public void symmetricFeedbackModelCannotReachTheExactWriteMapping() {
        SymmetricReachability reachability = new SymmetricReachability();

        assertFalse(reachability.exactWriteMappingIsReachable());
        assertEquals(5968, reachability.reachableStateCount());
        assertTrue(reachability.majorityButCorruptedSourceColumnIsReachable());
    }

    private static Set<Integer> possibleChangedRows(int scanRow) {
        Set<Integer> changed = new LinkedHashSet<>();
        Random random = new Random(0x51a00000L + scanRow);
        for (int iteration = 0; iteration < 1024; iteration++) {
            Ram before = randomOam(random);
            Ram after = copy(before);
            SpriteBug.corruptOamRead(after, 0xfe00, scanRow);
            for (int row = 0; row < 20; row++) {
                if (!rowsEqual(before, after, row)) {
                    changed.add(row);
                }
            }
        }
        return changed;
    }

    private static Ram randomOam(Random random) {
        Ram oam = new Ram(0xfe00, 0xa0);
        for (int address = 0xfe00; address < 0xfea0; address++) {
            oam.setByte(address, random.nextInt(0x100));
        }
        return oam;
    }

    private static Ram copy(Ram source) {
        Ram copy = new Ram(0xfe00, 0xa0);
        for (int address = 0xfe00; address < 0xfea0; address++) {
            copy.setByte(address, source.getByte(address));
        }
        return copy;
    }

    private static boolean rowsEqual(Ram first, Ram second, int row) {
        for (int offset = 0; offset < 8; offset++) {
            int address = 0xfe00 + row * 8 + offset;
            if (first.getByte(address) != second.getByte(address)) {
                return false;
            }
        }
        return true;
    }

    private static Set<Integer> setOf(Integer... rows) {
        return new LinkedHashSet<>(Arrays.asList(rows));
    }

    private static void setWord(Ram oam, int row, int column, int value) {
        int address = 0xfe00 + row * 8 + column * 2;
        oam.setByte(address, value);
        oam.setByte(address + 1, value >>> 8);
    }

    private static int word(Ram oam, int row, int column) {
        int address = 0xfe00 + row * 8 + column * 2;
        return oam.getByte(address) | (oam.getByte(address + 1) << 8);
    }

    /**
     * Exhaustive symbolic reachability for the older symmetric common-line hypothesis.
     *
     * <p>Two rows and the two relevant columns are enough. Each long is the complete truth table
     * over the four initial cells, so one search state represents all sixteen data patterns. The
     * closure includes independent word-line and bit-line precharge, sticky selection of either
     * row, and every column-select mask. It deliberately has no SRAM write-data input: the gate
     * waveform shows that input inactive during the observed corruption.
     */
    private static final class SymmetricReachability {

        private static final int COLUMNS = 2;

        private static final int ASSIGNMENTS = 1 << (2 * COLUMNS);

        private static final long TRUTH_MASK = (1L << ASSIGNMENTS) - 1;

        private static final int PRECHARGE_ALL = 0;

        private static final int PRECHARGE_WORD_LINES = 1;

        private static final int PRECHARGE_BIT_LINES = 2;

        private static final int SELECT_PREVIOUS = 3;

        private static final int SELECT_CURRENT = 4;

        private final Set<State> reachable;

        private final long[] targetMemory;

        private SymmetricReachability() {
            long[] initialMemory = new long[2 * COLUMNS];
            for (int variable = 0; variable < initialMemory.length; variable++) {
                initialMemory[variable] = truthTableForVariable(variable);
            }
            long previousFirst = initialMemory[0];
            long previousThird = initialMemory[1];
            long currentFirst = initialMemory[2];
            long majority = (currentFirst & previousFirst)
                    | (currentFirst & previousThird)
                    | (previousFirst & previousThird);
            targetMemory = new long[]{
                    previousFirst, previousThird, majority, previousThird
            };
            reachable = close(new State(
                    initialMemory, new long[COLUMNS], 0, 0, 0, false, 0));
        }

        private boolean exactWriteMappingIsReachable() {
            for (State state : reachable) {
                if (Arrays.equals(targetMemory, state.memory)) {
                    return true;
                }
            }
            return false;
        }

        private int reachableStateCount() {
            return reachable.size();
        }

        private boolean majorityButCorruptedSourceColumnIsReachable() {
            long previousFirst = targetMemory[0];
            long majority = targetMemory[2];
            for (State state : reachable) {
                if (state.memory[0] == previousFirst
                        && state.memory[2] == majority
                        && state.memory[3] == majority) {
                    return true;
                }
            }
            return false;
        }

        private static Set<State> close(State initial) {
            Set<State> seen = new HashSet<>();
            ArrayDeque<State> pending = new ArrayDeque<>();
            seen.add(initial);
            pending.add(initial);

            int operationCount = 5 + (1 << COLUMNS);
            while (!pending.isEmpty()) {
                State state = pending.removeFirst();
                for (int operation = 0; operation < operationCount; operation++) {
                    State next = apply(state, operation);
                    if (next != null && seen.add(next)) {
                        pending.addLast(next);
                    }
                }
            }
            return seen;
        }

        private static State apply(State state, int operation) {
            long[] memory = state.memory.clone();
            long[] bitLines = state.bitLines.clone();
            int validBitLines = state.validBitLines;
            int selectedRows = state.selectedRows;
            int selectedColumns = state.selectedColumns;
            boolean keeperValid = state.keeperValid;
            long keeper = state.keeper;

            switch (operation) {
                case PRECHARGE_ALL:
                    selectedRows = 0;
                    validBitLines = 0;
                    break;
                case PRECHARGE_WORD_LINES:
                    selectedRows = 0;
                    break;
                case PRECHARGE_BIT_LINES:
                    validBitLines = 0;
                    break;
                case SELECT_PREVIOUS:
                    selectedRows |= 1;
                    break;
                case SELECT_CURRENT:
                    selectedRows |= 2;
                    break;
                default:
                    selectedColumns = operation - 5;
                    break;
            }

            if (selectedRows != 0) {
                int firstSelectedRow = Integer.numberOfTrailingZeros(selectedRows);
                for (int column = 0; column < COLUMNS; column++) {
                    int mask = 1 << column;
                    if ((validBitLines & mask) == 0) {
                        bitLines[column] = memory[firstSelectedRow * COLUMNS + column];
                        validBitLines |= mask;
                    }
                }
            }

            int drivenColumns = selectedColumns & validBitLines;
            if (drivenColumns != 0) {
                long drivenHigh = 0;
                long drivenLow = 0;
                for (int column = 0; column < COLUMNS; column++) {
                    if ((drivenColumns & (1 << column)) != 0) {
                        drivenHigh |= bitLines[column];
                        drivenLow |= ~bitLines[column] & TRUTH_MASK;
                    }
                }
                long contention = drivenHigh & drivenLow;
                if (contention != 0 && !keeperValid) {
                    return null;
                }
                long resolved = (drivenHigh & ~contention) | (keeper & contention);
                for (int column = 0; column < COLUMNS; column++) {
                    if ((drivenColumns & (1 << column)) != 0) {
                        bitLines[column] = resolved;
                    }
                }
                keeper = resolved;
                keeperValid = true;
            }

            if (selectedRows != 0) {
                for (int row = 0; row < 2; row++) {
                    if ((selectedRows & (1 << row)) == 0) {
                        continue;
                    }
                    for (int column = 0; column < COLUMNS; column++) {
                        if ((validBitLines & (1 << column)) != 0) {
                            memory[row * COLUMNS + column] = bitLines[column];
                        }
                    }
                }
            }
            return new State(
                    memory, bitLines, validBitLines, selectedRows, selectedColumns,
                    keeperValid, keeper);
        }

        private static long truthTableForVariable(int variable) {
            long truthTable = 0;
            for (int assignment = 0; assignment < ASSIGNMENTS; assignment++) {
                if ((assignment & (1 << variable)) != 0) {
                    truthTable |= 1L << assignment;
                }
            }
            return truthTable;
        }

        private static final class State {

            private final long[] memory;

            private final long[] bitLines;

            private final int validBitLines;

            private final int selectedRows;

            private final int selectedColumns;

            private final boolean keeperValid;

            private final long keeper;

            private State(
                    long[] memory, long[] bitLines, int validBitLines, int selectedRows,
                    int selectedColumns, boolean keeperValid, long keeper) {
                this.memory = memory;
                this.bitLines = bitLines;
                this.validBitLines = validBitLines;
                this.selectedRows = selectedRows;
                this.selectedColumns = selectedColumns;
                this.keeperValid = keeperValid;
                this.keeper = keeper;
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }
                if (!(obj instanceof State)) {
                    return false;
                }
                State that = (State) obj;
                return validBitLines == that.validBitLines
                        && selectedRows == that.selectedRows
                        && selectedColumns == that.selectedColumns
                        && keeperValid == that.keeperValid
                        && keeper == that.keeper
                        && Arrays.equals(memory, that.memory)
                        && Arrays.equals(bitLines, that.bitLines);
            }

            @Override
            public int hashCode() {
                int result = Arrays.hashCode(memory);
                result = 31 * result + Arrays.hashCode(bitLines);
                result = 31 * result + validBitLines;
                result = 31 * result + selectedRows;
                result = 31 * result + selectedColumns;
                result = 31 * result + Boolean.hashCode(keeperValid);
                result = 31 * result + Long.hashCode(keeper);
                return result;
            }
        }
    }
}
