package eu.rekawek.coffeegb.core.gpu;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Executable, deliberately bounded hypothesis extracted from the DMG gate graph.
 *
 * <p>This is not a replacement PPU or a conformance model. It contains only the
 * observable cone exercised by one pinned external gate simulation: the LY ripple
 * chain, its terminal bit detector and sampled reset, independent mode-1/mode-2
 * storage, LY/LYC comparison, and the transparent STAT/LYC write latches. The tests
 * pin causal transition order, not the simulator's guessed propagation delays.</p>
 */
public class DmgStatGateConeTest {

    @Test
    public void ordinaryLineKeepsLyMode2AndCoincidenceOnSeparateReceivers() {
        Cone cone = new Cone(0, 1);

        cone.lineClock();
        cone.setReadableMode2();
        cone.sampleCoincidence();

        assertEquals(Arrays.asList(
                "line-clock",
                "ly=1",
                "lyc-match=1",
                "mode2=1",
                "coincidence=1"), cone.events);
    }

    @Test
    public void sampledBitDetectorResetsRippleBeforeTheModeHandoff() {
        Cone cone = new Cone(152, 1);
        cone.mode1 = true;
        cone.vclk2 = true;

        cone.lineClock();
        assertTrue(cone.terminalDetector());
        // The detector is a partial decode, not an eight-bit equality comparator.
        assertTrue(Cone.terminalDetector(RippleCounter.bits(155)));
        assertFalse(Cone.terminalDetector(RippleCounter.bits(152)));

        cone.sampleTerminalOnNypeFalling();
        assertTrue(cone.mode1);

        cone.nextLineClock();
        cone.setReadableMode2();
        assertTrue(cone.mode1);
        cone.sampleMode1OnNypeRising();

        assertEquals(Arrays.asList(
                "line-clock",
                "ly=153",
                "terminal-sample=1",
                "vertical-reset-n=0",
                "ly=0",
                "next-line-clock",
                "mode2=1",
                "mode1=0",
                "oam-source=1"), cone.events);
    }

    @Test
    public void statWriteGlitchFallsOutOfPrechargeAndTransparentLatches() {
        Cone cone = new Cone(1, 0);
        cone.hblank = true;

        cone.writeStat(0);

        assertEquals(Arrays.asList(
                "ff41-write-gate=1",
                "stat-enables=1111",
                "stat-level=1",
                "stat-if=1",
                "stat-enables=0000",
                "stat-level=0",
                "ff41-write-gate=0"), cone.events);
        assertTrue(cone.statIf);
    }

    @Test
    public void lycWriteReachesComparatorBeforeClockedCoincidence() {
        Cone cone = new Cone(2, 0);

        cone.writeLyc(2);
        assertFalse(cone.coincidence);
        cone.sampleCoincidence();

        assertEquals(Arrays.asList(
                "ff45-write-gate=1",
                "lyc=255",
                "lyc=2",
                "lyc-match=1",
                "ff45-write-gate=0",
                "coincidence=1"), cone.events);
    }

    private static final class Cone {

        private final RippleCounter ly;

        private final TransparentLatch[] statEnables = latches(4);

        private final TransparentLatch[] lycBits = latches(8);

        private final List<String> events = new ArrayList<>();

        private boolean hblank;

        private boolean mode1;

        private boolean mode2;

        private boolean vclk2;

        private boolean oamSource;

        private boolean coincidence;

        private boolean comparatorMatch;

        private boolean statLevel;

        private boolean statIf;

        private Cone(int line, int lyc) {
            this.ly = new RippleCounter(line);
            setLatchBankSilently(lycBits, lyc);
            comparatorMatch = line == lyc;
        }

        private void lineClock() {
            mark("line-clock");
            ly.clock();
            mark("ly=" + ly.value());
            updateComparator();
        }

        private void nextLineClock() {
            mark("next-line-clock");
        }

        private boolean terminalDetector() {
            return terminalDetector(ly.bits);
        }

        private static boolean terminalDetector(boolean[] bits) {
            return bits[7] && bits[4] && bits[3] && bits[0];
        }

        private void sampleTerminalOnNypeFalling() {
            if (!terminalDetector()) {
                return;
            }
            mark("terminal-sample=1");
            mark("vertical-reset-n=0");
            ly.reset();
            mark("ly=0");
            updateComparator();
        }

        private void setReadableMode2() {
            if (!mode2) {
                mode2 = true;
                mark("mode2=1");
            }
        }

        private void sampleMode1OnNypeRising() {
            boolean vblankDecode = ly.bits[7] && ly.bits[4];
            if (mode1 != vblankDecode) {
                mode1 = vblankDecode;
                mark("mode1=" + bit(mode1));
            }
            boolean nextOamSource = !mode1 && vclk2;
            if (oamSource != nextOamSource) {
                oamSource = nextOamSource;
                mark("oam-source=" + bit(oamSource));
            }
        }

        private void writeStat(int requested) {
            mark("ff41-write-gate=1");
            passBus(statEnables, true, byteBits(0xff, 3, 4));
            mark("stat-enables=" + latchBits(statEnables));
            updateStatLine();

            passBus(statEnables, true, byteBits(requested, 3, 4));
            mark("stat-enables=" + latchBits(statEnables));
            updateStatLine();

            passBus(statEnables, false, null);
            mark("ff41-write-gate=0");
        }

        private void updateStatLine() {
            boolean next = (statEnables[0].q && hblank)
                    || (statEnables[1].q && mode1)
                    || (statEnables[2].q && oamSource)
                    || (statEnables[3].q && coincidence);
            if (statLevel == next) {
                return;
            }
            statLevel = next;
            mark("stat-level=" + bit(statLevel));
            if (statLevel && !statIf) {
                statIf = true;
                mark("stat-if=1");
            }
        }

        private void writeLyc(int requested) {
            mark("ff45-write-gate=1");
            passBus(lycBits, true, byteBits(0xff, 0, 8));
            mark("lyc=" + latchValue(lycBits));
            updateComparator();

            passBus(lycBits, true, byteBits(requested, 0, 8));
            mark("lyc=" + latchValue(lycBits));
            updateComparator();

            passBus(lycBits, false, null);
            mark("ff45-write-gate=0");
        }

        private void updateComparator() {
            boolean next = ly.value() == latchValue(lycBits);
            if (comparatorMatch != next) {
                comparatorMatch = next;
                mark("lyc-match=" + bit(comparatorMatch));
            }
        }

        private void sampleCoincidence() {
            if (coincidence != comparatorMatch) {
                coincidence = comparatorMatch;
                mark("coincidence=" + bit(coincidence));
                updateStatLine();
            }
        }

        private void mark(String event) {
            events.add(event);
        }

        private static TransparentLatch[] latches(int count) {
            TransparentLatch[] result = new TransparentLatch[count];
            for (int i = 0; i < count; i++) {
                result[i] = new TransparentLatch();
            }
            return result;
        }

        private static void passBus(TransparentLatch[] latches, boolean gate, boolean[] bits) {
            for (int i = 0; i < latches.length; i++) {
                latches[i].drive(gate, gate && bits[i]);
            }
        }

        private static void setLatchBankSilently(TransparentLatch[] latches, int value) {
            boolean[] bits = byteBits(value, 0, latches.length);
            passBus(latches, true, bits);
            passBus(latches, false, null);
        }

        private static boolean[] byteBits(int value, int firstBit, int count) {
            boolean[] result = new boolean[count];
            for (int i = 0; i < count; i++) {
                result[i] = (value & (1 << (firstBit + i))) != 0;
            }
            return result;
        }

        private static String latchBits(TransparentLatch[] latches) {
            StringBuilder result = new StringBuilder(latches.length);
            for (int i = latches.length - 1; i >= 0; i--) {
                result.append(bit(latches[i].q));
            }
            return result.toString();
        }

        private static int latchValue(TransparentLatch[] latches) {
            int result = 0;
            for (int i = 0; i < latches.length; i++) {
                if (latches[i].q) {
                    result |= 1 << i;
                }
            }
            return result;
        }

        private static int bit(boolean value) {
            return value ? 1 : 0;
        }
    }

    private static final class TransparentLatch {

        private boolean q;

        private void drive(boolean gate, boolean d) {
            if (gate) {
                q = d;
            }
        }
    }

    private static final class RippleCounter {

        private final boolean[] bits;

        private RippleCounter(int value) {
            bits = bits(value);
        }

        private void clock() {
            for (int i = 0; i < bits.length; i++) {
                bits[i] = !bits[i];
                if (bits[i]) {
                    return;
                }
            }
        }

        private void reset() {
            Arrays.fill(bits, false);
        }

        private int value() {
            int result = 0;
            for (int i = 0; i < bits.length; i++) {
                if (bits[i]) {
                    result |= 1 << i;
                }
            }
            return result;
        }

        private static boolean[] bits(int value) {
            boolean[] result = new boolean[8];
            for (int i = 0; i < result.length; i++) {
                result[i] = (value & (1 << i)) != 0;
            }
            return result;
        }
    }
}
