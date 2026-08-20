package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Differential operation tests for the unused E2a scalar timing-FIFO models. */
public class TimingFifoDifferentialTest {

    private static final int DMG_BGP = 0xe4;

    private static final int DMG_OBP0 = 0x1b;

    private static final int DMG_OBP1 = 0xb1;

    @Test
    public void dmgAdversarialTraceCoversSuppressedFirstEntryAndRepairs() {
        DmgPair pair = new DmgPair();
        runDmg(pair, dmgAdversarialTrace());
        roundTripDmg(pair, dmgRoundTripPerturbations(pair));
    }

    @Test
    public void dmgSeededGeneratedTracesMatchAfterEveryOperation() {
        for (long seed : new long[] {0x5eedL, 0x51a7L, 0x2006L}) {
            DmgPair pair = new DmgPair();
            assertDmg(pair, "seed " + seed + " initial");
            Random random = new Random(seed);
            for (int step = 0; step < 1_200; step++) {
                applyDmg(pair, randomDmg(random, pair.reference), "seed " + seed + " step " + step);
                if (step % 173 == 0) {
                    roundTripDmg(pair, dmgRoundTripPerturbations(pair));
                }
            }
        }
    }

    @Test
    public void cgbAdversarialTraceCoversRetainedBackgroundAndSuppression() {
        for (boolean dmgCompat : new boolean[] {false, true}) {
            CgbPair pair = new CgbPair(dmgCompat);
            runCgb(pair, cgbAdversarialTrace());
            roundTripCgb(pair, cgbRoundTripPerturbations(pair));
        }
    }

    @Test
    public void cgbSeededGeneratedTracesMatchAfterEveryOperation() {
        for (boolean dmgCompat : new boolean[] {false, true}) {
            for (long seed : new long[] {0xc0ffeeL, 0xc0b0L, 0x60L}) {
                CgbPair pair = new CgbPair(dmgCompat);
                assertCgb(pair, "compat " + dmgCompat + " seed " + seed + " initial");
                Random random = new Random(seed ^ (dmgCompat ? 0x44L : 0));
                for (int step = 0; step < 1_200; step++) {
                    applyCgb(
                            pair,
                            randomCgb(random, pair.reference),
                            "compat " + dmgCompat + " seed " + seed + " step " + step);
                    if (step % 173 == 0) {
                        roundTripCgb(pair, cgbRoundTripPerturbations(pair));
                    }
                }
            }
        }
    }

    @Test
    public void payloadVariationsCollapseToTheSameScalarProjection() {
        DmgPair dmgA = new DmgPair();
        DmgPair dmgB = new DmgPair();
        List<Op> dmgAOperations = List.of(
                enqueue8(new int[] {0, 0, 0, 0, 0, 0, 0, 0}, 0x00),
                overlay(new int[] {0, 0, 0, 0, 0, 0, 0, 0}, 0, 0x00, 0),
                bare(Kind.PUT),
                bare(Kind.OUTPUT),
                bare(Kind.OUTPUT),
                bare(Kind.OUTPUT),
                refreshBg(new int[] {0, 0, 0, 0, 0, 0, 0, 0},
                        new int[] {1, 1, 1, 1, 1, 1, 1, 1}, 1),
                refreshOverlay(new int[] {0, 0, 0, 0, 0, 0, 0, 0},
                        new int[] {1, 1, 1, 1, 1, 1, 1, 1}, 0, 0x00));
        List<Op> dmgBOperations = List.of(
                enqueue8(new int[] {3, 2, 1, 3, 2, 1, 3, 2}, 0xff),
                overlay(new int[] {3, 2, 1, 3, 2, 1, 3, 2}, 0, 0xff, 39),
                bare(Kind.PUT),
                bare(Kind.OUTPUT),
                bare(Kind.OUTPUT),
                bare(Kind.OUTPUT),
                refreshBg(new int[] {3, 2, 1, 3, 2, 1, 3, 2},
                        new int[] {0, 0, 0, 0, 0, 0, 0, 0}, 1),
                refreshOverlay(new int[] {3, 2, 1, 3, 2, 1, 3, 2},
                        new int[] {0, 0, 0, 0, 0, 0, 0, 0}, 0, 0xff));
        for (int i = 0; i < dmgAOperations.size(); i++) {
            applyDmg(dmgA, dmgAOperations.get(i), "DMG payload A " + i);
            applyDmg(dmgB, dmgBOperations.get(i), "DMG payload B " + i);
            assertProjection(
                    referenceProjection(dmgA.reference),
                    referenceProjection(dmgB.reference),
                    "DMG payload projection " + i);
            assertProjection(
                    dmgA.timing.projection(),
                    dmgB.timing.projection(),
                    "DMG scalar payload projection " + i);
        }

        for (boolean dmgCompat : new boolean[] {false, true}) {
            CgbPair cgbA = new CgbPair(dmgCompat);
            CgbPair cgbB = new CgbPair(dmgCompat);
            List<Op> cgbAOperations = List.of(
                    enqueue8(new int[] {0, 0, 0, 0, 0, 0, 0, 0}, 0x00),
                    overlay(new int[] {0, 0, 0, 0, 0, 0, 0, 0}, 0, 0x00, 0),
                    bare(Kind.CLEAR_BG),
                    enqueue8(new int[] {1, 1, 1, 1, 1, 1, 1, 1}, 0x04),
                    bare(Kind.PUT_CLEARED),
                    bare(Kind.OUTPUT),
                    refreshBg(new int[] {0, 0, 0, 0, 0, 0, 0, 0},
                            new int[] {3, 3, 3, 3, 3, 3, 3, 3}, 0),
                    refreshOverlay(new int[] {0, 0, 0, 0, 0, 0, 0, 0},
                            new int[] {3, 3, 3, 3, 3, 3, 3, 3}, 0, 0x00));
            List<Op> cgbBOperations = List.of(
                    enqueue8(new int[] {3, 2, 1, 3, 2, 1, 3, 2}, 0xff),
                    overlay(new int[] {3, 2, 1, 3, 2, 1, 3, 2}, 0, 0xff, 39),
                    bare(Kind.CLEAR_BG),
                    enqueue8(new int[] {2, 2, 2, 2, 2, 2, 2, 2}, 0x7f),
                    bare(Kind.PUT_CLEARED),
                    bare(Kind.OUTPUT),
                    refreshBg(new int[] {3, 2, 1, 3, 2, 1, 3, 2},
                            new int[] {0, 0, 0, 0, 0, 0, 0, 0}, 0),
                    refreshOverlay(new int[] {3, 2, 1, 3, 2, 1, 3, 2},
                            new int[] {0, 0, 0, 0, 0, 0, 0, 0}, 0, 0xff));
            for (int i = 0; i < cgbAOperations.size(); i++) {
                applyCgb(cgbA, cgbAOperations.get(i), "CGB payload A " + i);
                applyCgb(cgbB, cgbBOperations.get(i), "CGB payload B " + i);
                assertProjection(
                        referenceProjection(cgbA.reference),
                        referenceProjection(cgbB.reference),
                        "CGB payload projection " + i);
                assertProjection(
                        cgbA.timing.projection(),
                        cgbB.timing.projection(),
                        "CGB scalar payload projection " + i);
            }
        }
    }

    @Test
    public void capturedDmgTimestampStateIsDefensivelyOwned() {
        DmgPair pair = new DmgPair();
        pair.timing.enqueue8Pixels(new int[8], TileAttributes.EMPTY);
        pair.timing.putPixelToScreen();
        TimingDmgPixelFifo.State state = pair.timing.captureState();

        long[] exposed = state.delayStamp();
        exposed[0] = Long.MAX_VALUE;
        assertEquals(0, pair.timing.projection().delayStamp()[0]);

        pair.timing.restoreState(state);
        assertEquals(0, pair.timing.projection().delayStamp()[0]);
    }

    private static void runDmg(DmgPair pair, List<Op> operations) {
        assertDmg(pair, "initial");
        for (int i = 0; i < operations.size(); i++) {
            applyDmg(pair, operations.get(i), "adversarial step " + i);
        }
    }

    private static void runCgb(CgbPair pair, List<Op> operations) {
        assertCgb(pair, "initial");
        for (int i = 0; i < operations.size(); i++) {
            applyCgb(pair, operations.get(i), "adversarial step " + i);
        }
    }

    private static void applyDmg(DmgPair pair, Op op, String context) {
        switch (op.kind) {
            case ENQUEUE8 -> {
                pair.reference.enqueue8Pixels(op.line, TileAttributes.valueOf(op.attribute));
                pair.timing.enqueue8Pixels(op.line, TileAttributes.valueOf(op.attribute));
            }
            case ENQUEUE_ONE -> {
                pair.reference.enqueuePixel(op.value);
                pair.timing.enqueuePixel(op.value);
            }
            case OVERLAY -> {
                TileAttributes attributes = TileAttributes.valueOf(op.attribute);
                pair.reference.setOverlay(op.line, op.offset, attributes, op.oamIndex);
                pair.timing.setOverlay(op.line, op.offset, attributes, op.oamIndex);
            }
            case PUT -> {
                pair.reference.putPixelToScreen();
                pair.timing.putPixelToScreen();
            }
            case INSERT -> {
                pair.reference.putInsertedPixel();
                pair.timing.putInsertedPixel();
            }
            case DROP -> {
                pair.reference.dropPixel();
                pair.timing.dropPixel();
            }
            case DEQUEUE -> {
                pair.reference.dequeuePixel();
                pair.timing.dequeuePixel();
            }
            case REWIND -> {
                pair.reference.rewindOnePixel();
                pair.timing.rewindOnePixel();
            }
            case OUTPUT -> {
                pair.reference.outputTick();
                pair.timing.outputTick();
            }
            case START_LINE -> {
                pair.reference.startLine();
                pair.timing.startLine();
            }
            case CLEAR_OUTPUT -> {
                pair.reference.clearOutput();
                pair.timing.clearOutput();
            }
            case CLEAR -> {
                pair.reference.clear();
                pair.timing.clear();
            }
            case CLEAR_BG -> {
                pair.reference.clearBg();
                pair.timing.clearBg();
            }
            case DISCARD_CLEARED -> {
                pair.reference.discardClearedBg();
                pair.timing.discardClearedBg();
            }
            case REFRESH_BG -> {
                pair.reference.refreshBgPixels(op.oldLine, op.newLine, op.popped);
                pair.timing.refreshBgPixels(op.oldLine, op.newLine, op.popped);
            }
            case REFRESH_OVERLAY -> {
                TileAttributes attributes = TileAttributes.valueOf(op.attribute);
                pair.reference.refreshOverlay(op.oldLine, op.newLine, op.offset, attributes);
                pair.timing.refreshOverlay(op.oldLine, op.newLine, op.offset, attributes);
            }
            default -> throw new AssertionError("Unsupported DMG operation " + op.kind);
        }
        assertDmg(pair, context + " / " + op.kind);
    }

    private static void applyCgb(CgbPair pair, Op op, String context) {
        switch (op.kind) {
            case ENQUEUE8 -> {
                pair.reference.enqueue8Pixels(op.line, TileAttributes.valueOf(op.attribute));
                pair.timing.enqueue8Pixels(op.line, TileAttributes.valueOf(op.attribute));
            }
            case ENQUEUE_ONE, INSERT, REFRESH_BG, REFRESH_OVERLAY -> {
                // These are no-ops in the production CGB implementation through PixelFifo's
                // default methods. Exercise them to keep the scalar model's boundary honest.
                invokeCgbNoOp(pair.reference, pair.timing, op);
            }
            case OVERLAY -> {
                TileAttributes attributes = TileAttributes.valueOf(op.attribute);
                pair.reference.setOverlay(op.line, op.offset, attributes, op.oamIndex);
                pair.timing.setOverlay(op.line, op.offset, attributes, op.oamIndex);
            }
            case PUT -> {
                pair.reference.putPixelToScreen();
                pair.timing.putPixelToScreen();
            }
            case PUT_CLEARED -> {
                pair.reference.putClearedBgToScreen();
                pair.timing.putClearedBgToScreen();
            }
            case DROP -> {
                pair.reference.dropPixel();
                pair.timing.dropPixel();
            }
            case DROP_CLEARED -> {
                pair.reference.dropClearedBgPixel();
                pair.timing.dropClearedBgPixel();
            }
            case REWIND -> {
                pair.reference.rewindOnePixel();
                pair.timing.rewindOnePixel();
            }
            case OUTPUT -> {
                pair.reference.outputTick();
                pair.timing.outputTick();
            }
            case START_LINE -> {
                pair.reference.startLine();
                pair.timing.startLine();
            }
            case CLEAR_OUTPUT -> {
                pair.reference.clearOutput();
                pair.timing.clearOutput();
            }
            case CLEAR -> {
                pair.reference.clear();
                pair.timing.clear();
            }
            case CLEAR_BG -> {
                pair.reference.clearBg();
                pair.timing.clearBg();
            }
            case DISCARD_CLEARED -> {
                pair.reference.discardClearedBg();
                pair.timing.discardClearedBg();
            }
            default -> throw new AssertionError("Unsupported CGB operation " + op.kind);
        }
        assertCgb(pair, context + " / " + op.kind);
    }

    private static void invokeCgbNoOp(ColorPixelFifo reference, TimingColorPixelFifo timing, Op op) {
        switch (op.kind) {
            case ENQUEUE_ONE -> {
                reference.enqueuePixel(op.value);
                timing.enqueuePixel(op.value);
            }
            case INSERT -> {
                reference.putInsertedPixel();
                timing.putInsertedPixel();
            }
            case REFRESH_BG -> {
                reference.refreshBgPixels(op.oldLine, op.newLine, op.popped);
                timing.refreshBgPixels(op.oldLine, op.newLine, op.popped);
            }
            case REFRESH_OVERLAY -> {
                TileAttributes attributes = TileAttributes.valueOf(op.attribute);
                reference.refreshOverlay(op.oldLine, op.newLine, op.offset, attributes);
                timing.refreshOverlay(op.oldLine, op.newLine, op.offset, attributes);
            }
            default -> throw new AssertionError(op.kind);
        }
    }

    private static void roundTripDmg(DmgPair pair, List<Op> perturbations) {
        ComponentState<DmgPixelFifo> referenceState = pair.reference.captureState();
        DmgPixelFifo.RuntimeState referenceRuntime = pair.reference.captureRuntimeState();
        TimingDmgPixelFifo.State timingState = pair.timing.captureState();
        for (int i = 0; i < perturbations.size(); i++) {
            applyDmg(pair, perturbations.get(i), "DMG round-trip perturbation " + i);
        }

        pair.reference.restoreState(referenceState);
        pair.reference.restoreRuntimeState(referenceRuntime);
        pair.timing.restoreState(timingState);
        assertDmg(pair, "DMG round-trip restore");
    }

    private static void roundTripCgb(CgbPair pair, List<Op> perturbations) {
        ComponentState<ColorPixelFifo> referenceState = pair.reference.captureState();
        TimingColorPixelFifo.State timingState = pair.timing.captureState();
        for (int i = 0; i < perturbations.size(); i++) {
            applyCgb(pair, perturbations.get(i), "CGB round-trip perturbation " + i);
        }

        pair.reference.restoreState(referenceState);
        pair.timing.restoreState(timingState);
        assertCgb(pair, "CGB round-trip restore");
    }

    private static List<Op> dmgAdversarialTrace() {
        List<Op> operations = new ArrayList<>();
        operations.add(enqueue8(new int[] {3, 2, 1, 0, 3, 1, 2, 0}, 0));
        operations.add(overlay(new int[] {3, 0, 2, 0, 1, 0, 0, 0}, 0, 0x90, 0));
        operations.add(bare(Kind.PUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(refreshBg(new int[] {3, 2, 1, 0, 3, 1, 2, 0},
                new int[] {0, 0, 0, 0, 1, 1, 1, 1}, 1));
        operations.add(refreshOverlay(new int[] {3, 0, 2, 0, 1, 0, 0, 0},
                new int[] {1, 0, 3, 0, 2, 0, 0, 0}, 0, 0x90));
        operations.add(bare(Kind.REWIND));
        operations.add(enqueueOne(2));
        operations.add(bare(Kind.PUT));
        operations.add(bare(Kind.INSERT));
        operations.add(bare(Kind.CLEAR_OUTPUT));
        operations.add(bare(Kind.START_LINE));
        operations.add(bare(Kind.CLEAR_BG));
        operations.add(bare(Kind.CLEAR));
        operations.add(enqueue8(new int[] {0, 1, 2, 3, 0, 1, 2, 3}, 0));
        operations.add(overlay(new int[] {0, 3, 0, 2, 0, 1, 0, 0}, 0, 0x10, 0));
        operations.add(bare(Kind.PUT));
        operations.add(bare(Kind.DEQUEUE));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.DROP));
        return operations;
    }

    private static List<Op> cgbAdversarialTrace() {
        List<Op> operations = new ArrayList<>();
        operations.add(enqueue8(new int[] {1, 2, 3, 0, 1, 2, 3, 0}, 0x85));
        operations.add(overlay(new int[] {3, 0, 2, 0, 1, 0, 0, 0}, 0, 0x83, 4));
        operations.add(bare(Kind.CLEAR_BG));
        operations.add(enqueue8(new int[] {3, 2, 1, 0, 3, 2, 1, 0}, 0x04));
        operations.add(bare(Kind.PUT_CLEARED));
        operations.add(bare(Kind.DROP_CLEARED));
        operations.add(bare(Kind.REWIND));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.PUT_CLEARED));
        operations.add(bare(Kind.PUT));
        operations.add(bare(Kind.DROP));
        operations.add(bare(Kind.CLEAR_BG));
        operations.add(bare(Kind.DISCARD_CLEARED));
        operations.add(enqueue8(new int[] {0, 0, 1, 1, 2, 2, 3, 3}, 0x22));
        operations.add(bare(Kind.PUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(enqueue8(new int[] {3, 3, 2, 2, 1, 1, 0, 0}, 0x42));
        operations.add(bare(Kind.PUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.CLEAR_OUTPUT));
        operations.add(bare(Kind.START_LINE));
        operations.add(bare(Kind.CLEAR));
        operations.add(bare(Kind.ENQUEUE_ONE));
        operations.add(bare(Kind.INSERT));
        operations.add(refreshBg(new int[8], new int[8], 0));
        operations.add(refreshOverlay(new int[8], new int[8], 0, 0));
        return operations;
    }

    private static Op randomDmg(Random random, DmgPixelFifo reference) {
        int active = reference.getLength();
        int delay = intField(reference, "delaySize");
        for (int attempt = 0; attempt < 24; attempt++) {
            switch (random.nextInt(16)) {
                case 0 -> {
                    if (active <= 8) {
                        return enqueue8(randomLine(random), random.nextInt(256));
                    }
                }
                case 1 -> {
                    if (active < 16) {
                        return enqueueOne(random.nextInt(4));
                    }
                }
                case 2 -> {
                    return overlay(randomLine(random), random.nextInt(8), random.nextInt(256), random.nextInt(40));
                }
                case 3 -> {
                    if (active > 0 && delay < 8) {
                        return bare(Kind.PUT);
                    }
                }
                case 4 -> {
                    if (delay < 8) {
                        return bare(Kind.INSERT);
                    }
                }
                case 5 -> {
                    if (active > 0) {
                        return bare(Kind.DROP);
                    }
                }
                case 6 -> {
                    if (active > 0) {
                        return bare(Kind.DEQUEUE);
                    }
                }
                case 7 -> {
                    return bare(Kind.REWIND);
                }
                case 8, 9 -> {
                    return bare(Kind.OUTPUT);
                }
                case 10 -> {
                    return bare(Kind.START_LINE);
                }
                case 11 -> {
                    return bare(Kind.CLEAR_OUTPUT);
                }
                case 12 -> {
                    return bare(Kind.CLEAR);
                }
                case 13 -> {
                    return bare(Kind.CLEAR_BG);
                }
                case 14 -> {
                    return refreshBg(randomLine(random), randomLine(random), random.nextInt(9));
                }
                case 15 -> {
                    return refreshOverlay(randomLine(random), randomLine(random), random.nextInt(8), random.nextInt(256));
                }
                default -> throw new AssertionError();
            }
        }
        return bare(Kind.OUTPUT);
    }

    private static Op randomCgb(Random random, ColorPixelFifo reference) {
        int active = reference.getLength();
        int retained = reference.getClearedBgLength();
        int delay = intField(reference, "delaySize");
        for (int attempt = 0; attempt < 24; attempt++) {
            switch (random.nextInt(17)) {
                case 0 -> {
                    if (active <= 8) {
                        return enqueue8(randomLine(random), random.nextInt(256));
                    }
                }
                case 1 -> {
                    return overlay(randomLine(random), random.nextInt(8), random.nextInt(256), random.nextInt(40));
                }
                case 2 -> {
                    if (active > 0 && delay < 8) {
                        return bare(Kind.PUT);
                    }
                }
                case 3 -> {
                    if (retained > 0 && delay < 8) {
                        return bare(Kind.PUT_CLEARED);
                    }
                }
                case 4 -> {
                    if (active > 0) {
                        return bare(Kind.DROP);
                    }
                }
                case 5 -> {
                    if (retained > 0) {
                        return bare(Kind.DROP_CLEARED);
                    }
                }
                case 6 -> {
                    return bare(Kind.REWIND);
                }
                case 7, 8 -> {
                    return bare(Kind.OUTPUT);
                }
                case 9 -> {
                    return bare(Kind.START_LINE);
                }
                case 10 -> {
                    return bare(Kind.CLEAR_OUTPUT);
                }
                case 11 -> {
                    return bare(Kind.CLEAR);
                }
                case 12 -> {
                    return bare(Kind.CLEAR_BG);
                }
                case 13 -> {
                    return bare(Kind.DISCARD_CLEARED);
                }
                case 14 -> {
                    return enqueueOne(random.nextInt(4));
                }
                case 15 -> {
                    return bare(Kind.INSERT);
                }
                case 16 -> {
                    return refreshBg(randomLine(random), randomLine(random), random.nextInt(9));
                }
                default -> throw new AssertionError();
            }
        }
        return bare(Kind.OUTPUT);
    }

    private static List<Op> dmgRoundTripPerturbations(DmgPair pair) {
        List<Op> operations = new ArrayList<>();
        int active = pair.reference.getLength();
        int delay = intField(pair.reference, "delaySize");
        if (active > 0 && delay < 8) {
            operations.add(bare(Kind.PUT));
            active--;
            delay++;
        }
        if (active > 0) {
            operations.add(bare(Kind.DROP));
            active--;
        }
        if (delay < 8) {
            operations.add(bare(Kind.INSERT));
            delay++;
        }
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.CLEAR_OUTPUT));
        return operations;
    }

    private static List<Op> cgbRoundTripPerturbations(CgbPair pair) {
        List<Op> operations = new ArrayList<>();
        int active = pair.reference.getLength();
        int retained = pair.reference.getClearedBgLength();
        int delay = intField(pair.reference, "delaySize");
        if (active > 0 && delay < 8) {
            operations.add(bare(Kind.PUT));
            active--;
            delay++;
        }
        if (retained > 0 && delay < 8) {
            operations.add(bare(Kind.PUT_CLEARED));
            retained--;
            delay++;
        }
        operations.add(bare(Kind.OUTPUT));
        operations.add(bare(Kind.CLEAR_BG));
        operations.add(bare(Kind.DISCARD_CLEARED));
        return operations;
    }

    private static int[] randomLine(Random random) {
        int[] line = new int[8];
        for (int i = 0; i < line.length; i++) {
            line[i] = random.nextInt(4);
        }
        return line;
    }

    private static Op bare(Kind kind) {
        return new Op(kind, null, null, null, 0, 0, 0, 0, 0);
    }

    private static Op enqueue8(int[] line, int attribute) {
        return new Op(Kind.ENQUEUE8, line, null, null, attribute, 0, 0, 0, 0);
    }

    private static Op enqueueOne(int value) {
        return new Op(Kind.ENQUEUE_ONE, null, null, null, 0, 0, 0, 0, value);
    }

    private static Op overlay(int[] line, int offset, int attribute, int oamIndex) {
        return new Op(Kind.OVERLAY, line, null, null, attribute, offset, 0, oamIndex, 0);
    }

    private static Op refreshBg(int[] oldLine, int[] newLine, int popped) {
        return new Op(Kind.REFRESH_BG, null, oldLine, newLine, 0, 0, popped, 0, 0);
    }

    private static Op refreshOverlay(int[] oldLine, int[] newLine, int fromIndex, int attribute) {
        return new Op(Kind.REFRESH_OVERLAY, null, oldLine, newLine, attribute, fromIndex, 0, 0, 0);
    }

    private static void assertDmg(DmgPair pair, String context) {
        assertProjection(referenceProjection(pair.reference), pair.timing.projection(), context);
    }

    private static void assertCgb(CgbPair pair, String context) {
        assertProjection(referenceProjection(pair.reference), pair.timing.projection(), context);
    }

    private static void assertProjection(
            TimingFifoProjection expected, TimingFifoProjection actual, String context) {
        assertQueue(expected.activeBg(), actual.activeBg(), context + " active background");
        assertQueue(expected.retainedBg(), actual.retainedBg(), context + " retained background");
        assertSprite(expected.sprite(), actual.sprite(), context + " sprite");
        if (expected.delayStamp().length > 0 || actual.delayStamp().length > 0) {
            assertArrayEquals(context + " delay stamps", expected.delayStamp(), actual.delayStamp());
        }
        assertEquals(context + " delay head", expected.delayHead(), actual.delayHead());
        assertEquals(context + " delay size", expected.delaySize(), actual.delaySize());
        assertEquals(context + " output ticks", expected.outputTicks(), actual.outputTicks());
        assertEquals(context + " line pixels", expected.linePixels(), actual.linePixels());
        assertEquals(context + " output count", expected.outCount(), actual.outCount());
        assertEquals(context + " first entry presence", expected.firstEntryPresent(), actual.firstEntryPresent());
    }

    private static void assertQueue(
            TimingQueueProjection expected, TimingQueueProjection actual, String context) {
        assertEquals(context + " capacity", expected.capacity(), actual.capacity());
        assertEquals(context + " size", expected.size(), actual.size());
    }

    private static void assertSprite(
            TimingSpriteProjection expected, TimingSpriteProjection actual, String context) {
        assertEquals(context + " size", expected.size(), actual.size());
        assertEquals(context + " underflow", expected.underflow(), actual.underflow());
    }

    private static TimingFifoProjection referenceProjection(DmgPixelFifo fifo) {
        DmgPixelFifo.RuntimeState runtime = fifo.captureRuntimeState();
        IntQueue pixels = field(fifo, "pixels");
        SpriteFifo sprite = field(fifo, "spriteFifo");
        return new TimingFifoProjection(
                queueProjection(pixels),
                new TimingQueueProjection(TimingQueueLength.CAPACITY, 0),
                spriteProjection(sprite),
                longArrayField(fifo, "delayStamp"),
                intField(fifo, "delayHead"),
                intField(fifo, "delaySize"),
                longField(fifo, "outputTicks"),
                runtime.linePixels(),
                runtime.outCount(),
                runtime.firstEntry() >= 0);
    }

    private static TimingFifoProjection referenceProjection(ColorPixelFifo fifo) {
        IntQueue background = field(fifo, "background");
        IntQueue clearedBackground = field(fifo, "clearedBackground");
        SpriteFifo sprite = field(fifo, "spriteFifo");
        return new TimingFifoProjection(
                queueProjection(background),
                queueProjection(clearedBackground),
                spriteProjection(sprite),
                new long[0],
                intField(fifo, "delayHead"),
                intField(fifo, "delaySize"),
                longField(fifo, "outputTicks"),
                intField(fifo, "linePixels"),
                0,
                false);
    }

    private static TimingQueueProjection queueProjection(IntQueue queue) {
        return new TimingQueueProjection(queue.array.length, queue.size);
    }

    private static TimingSpriteProjection spriteProjection(SpriteFifo sprite) {
        return new TimingSpriteProjection(sprite.size, sprite.underflow);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(object);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int intField(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (int) field.get(object);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long longField(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (long) field.get(object);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long[] longArrayField(Object object, String name) {
        return ((long[]) field(object, name)).clone();
    }

    private enum Kind {
        ENQUEUE8,
        ENQUEUE_ONE,
        OVERLAY,
        PUT,
        PUT_CLEARED,
        INSERT,
        DROP,
        DROP_CLEARED,
        DEQUEUE,
        REWIND,
        OUTPUT,
        START_LINE,
        CLEAR_OUTPUT,
        CLEAR,
        CLEAR_BG,
        DISCARD_CLEARED,
        REFRESH_BG,
        REFRESH_OVERLAY
    }

    private record Op(
            Kind kind,
            int[] line,
            int[] oldLine,
            int[] newLine,
            int attribute,
            int offset,
            int popped,
            int oamIndex,
            int value) {
    }

    private static final class DmgPair {

        private final DmgPixelFifo reference;

        private final TimingDmgPixelFifo timing;

        private DmgPair() {
            GpuRegisterValues registers = new GpuRegisterValues();
            registers.setGbc(false);
            registers.put(GpuRegister.BGP, DMG_BGP);
            registers.put(GpuRegister.OBP0, DMG_OBP0);
            registers.put(GpuRegister.OBP1, DMG_OBP1);
            Lcdc lcdc = new Lcdc();
            lcdc.set(0x93);
            reference = new DmgPixelFifo(new Display(false), lcdc, registers, null);
            reference.setRenderOutput(false);
            timing = new TimingDmgPixelFifo();
        }
    }

    private static final class CgbPair {

        private final ColorPixelFifo reference;

        private final TimingColorPixelFifo timing;

        private CgbPair(boolean dmgCompat) {
            GpuRegisterValues registers = new GpuRegisterValues();
            registers.setGbc(true);
            Lcdc lcdc = new Lcdc();
            lcdc.setGbc(true);
            lcdc.set(0x93);
            SpeedMode speedMode = new SpeedMode(true);
            speedMode.setDmgCompat(dmgCompat);
            reference = new ColorPixelFifo(
                    new Display(true),
                    lcdc,
                    new ColorPalette(0xff68),
                    new ColorPalette(0xff6a),
                    registers,
                    speedMode);
            reference.setRenderOutput(false);
            timing = new TimingColorPixelFifo();
        }
    }
}
