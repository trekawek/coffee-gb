package eu.rekawek.coffeegb.core.experimental.apu;

import eu.rekawek.coffeegb.core.sound.VolumeEnvelope;
import org.junit.Test;

import java.util.EnumSet;

import static eu.rekawek.coffeegb.core.experimental.apu.DmgEnvelopeWriteRipple.Falsifier.CGB_ENVELOPE_PROFILE;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgEnvelopeWriteRipple.Falsifier.RETAINED_PERIOD_TIMER_PHASE_AFTER_ZERO_TO_NONZERO;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgEnvelopeWriteRipple.Falsifier.SUB_T_WRITE_APERTURE_TIMING;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgEnvelopeWriteRipple.Falsifier.WRITE_WHILE_EG_TICK_HIGH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exhaustive active-write differential for the four DMG envelope TFFNL cells. */
public class DmgEnvelopeWriteRippleTest {

    @Test
    public void fourPhysicalTffsMatchEveryUnlockedProductionWrite() {
        for (int volume = 0; volume < 16; volume++) {
            for (boolean oldDirectionUp : booleans()) {
                for (int oldPeriod = 0; oldPeriod < 8; oldPeriod++) {
                    int oldNr12 = nr12(volume, oldDirectionUp, oldPeriod);
                    for (boolean nextDirectionUp : booleans()) {
                        for (int nextPeriod = 0; nextPeriod < 8; nextPeriod++) {
                            // Bits 7..4 only feed the next parallel load; vary them independently.
                            int nextNr12 = nr12(15 - volume, nextDirectionUp, nextPeriod);

                            VolumeEnvelope production = activeProduction(oldNr12);
                            production.setNr2(nextNr12, true);

                            var old = DmgEnvelopeWriteRipple.State.loaded(
                                    oldNr12, volume, false);
                            var resolution = DmgEnvelopeWriteRipple.resolveWrite(old,
                                    DmgEnvelopeWriteRipple.WriteSignals.activeWrite(nextNr12));
                            String label = label(volume, oldDirectionUp, oldPeriod,
                                    nextDirectionUp, nextPeriod);
                            assertEquals(label, production.getVolume(),
                                    resolution.next().visibleVolume());
                            assertEquals(label, nextNr12, resolution.next().nr12());
                            assertEquals(label, oldDirectionUp != nextDirectionUp,
                                    resolution.directionClockRewire());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void stoppedBoundaryWritesRemapButCannotClockTheBaseTff() {
        for (boolean oldDirectionUp : booleans()) {
            int boundary = oldDirectionUp ? 15 : 0;
            for (int oldPeriod = 0; oldPeriod < 8; oldPeriod++) {
                int oldNr12 = nr12(boundary, oldDirectionUp, oldPeriod);
                for (boolean nextDirectionUp : booleans()) {
                    for (int nextPeriod = 0; nextPeriod < 8; nextPeriod++) {
                        int nextNr12 = nr12(7, nextDirectionUp, nextPeriod);

                        VolumeEnvelope production = activeProduction(oldNr12);
                        production.clockTick(); // boundary detector sets EG_STOP
                        production.setNr2(nextNr12, true);

                        var old = DmgEnvelopeWriteRipple.State.loaded(
                                oldNr12, boundary, true);
                        var resolution = DmgEnvelopeWriteRipple.resolveWrite(old,
                                DmgEnvelopeWriteRipple.WriteSignals.activeWrite(nextNr12));
                        assertEquals(label(boundary, oldDirectionUp, oldPeriod,
                                        nextDirectionUp, nextPeriod),
                                production.getVolume(), resolution.next().visibleVolume());
                        assertTrue(resolution.next().stop());
                        assertFalse(resolution.periodZeroFallingEdge());
                        assertFalse(resolution.writeAperturePulse());
                    }
                }
            }
        }
    }

    @Test
    public void sameValueEightWriteIsAnAperturePulseNotAValueSpecialCase() {
        int oldNr12 = nr12(5, true, 0);
        VolumeEnvelope production = activeProduction(oldNr12);
        production.setNr2(oldNr12, true);

        var resolution = DmgEnvelopeWriteRipple.resolveWrite(
                DmgEnvelopeWriteRipple.State.loaded(oldNr12, 5, false),
                DmgEnvelopeWriteRipple.WriteSignals.activeWrite(oldNr12));
        assertTrue(resolution.writeAperturePulse());
        assertFalse(resolution.periodZeroFallingEdge());
        assertEquals(6, resolution.next().visibleVolume());
        assertEquals(production.getVolume(), resolution.next().visibleVolume());
    }

    @Test
    public void directionChangesAreOnlyLiveRippleClockRewiring() {
        for (int volume = 0; volume < 16; volume++) {
            int down = nr12(volume, false, 1);
            int up = nr12(volume, true, 1);

            var downToUp = DmgEnvelopeWriteRipple.resolveWrite(
                    DmgEnvelopeWriteRipple.State.loaded(down, volume, false),
                    DmgEnvelopeWriteRipple.WriteSignals.activeWrite(up));
            assertEquals((14 - volume) & 0x0f, downToUp.next().visibleVolume());

            var upToDown = DmgEnvelopeWriteRipple.resolveWrite(
                    DmgEnvelopeWriteRipple.State.loaded(up, volume, false),
                    DmgEnvelopeWriteRipple.WriteSignals.activeWrite(down));
            assertEquals((16 - volume) & 0x0f, upToDown.next().visibleVolume());
        }
    }

    @Test
    public void upperVolumeBitsOnlyFeedTheNextRestartParallelLoad() {
        int oldNr12 = nr12(3, false, 2);
        int nextNr12 = nr12(12, false, 2);
        var written = DmgEnvelopeWriteRipple.resolveWrite(
                DmgEnvelopeWriteRipple.State.loaded(oldNr12, 3, false),
                DmgEnvelopeWriteRipple.WriteSignals.activeWrite(nextNr12));
        assertEquals(3, written.next().visibleVolume());

        var triggered = DmgEnvelopeWriteRipple.trigger(written.next());
        assertEquals(12, triggered.visibleVolume());
        assertFalse(triggered.stop());
    }

    @Test
    public void zeroToNonzeroClocksNowButRetainedTimerPhaseIsASeparateCone() {
        int oldNr12 = nr12(8, false, 0);
        int nextNr12 = nr12(2, false, 3);
        var resolution = DmgEnvelopeWriteRipple.resolveWrite(
                DmgEnvelopeWriteRipple.State.loaded(oldNr12, 8, false),
                DmgEnvelopeWriteRipple.WriteSignals.activeWrite(nextNr12));

        assertTrue(resolution.periodZeroFallingEdge());
        assertEquals(7, resolution.next().visibleVolume());
        assertTrue(resolution.falsifiers().contains(
                RETAINED_PERIOD_TIMER_PHASE_AFTER_ZERO_TO_NONZERO));
    }

    @Test
    public void productionPendingBooleanIsNotFoldedIntoTheLocalWriteCone() {
        int oldNr12 = nr12(8, false, 0);
        int nextNr12 = nr12(2, false, 1);
        VolumeEnvelope production = activeProduction(oldNr12);
        production.setNr2(nextNr12, true);
        assertEquals(7, production.getVolume());

        var resolution = DmgEnvelopeWriteRipple.resolveWrite(
                DmgEnvelopeWriteRipple.State.loaded(oldNr12, 8, false),
                DmgEnvelopeWriteRipple.WriteSignals.activeWrite(nextNr12));
        assertEquals(7, resolution.next().visibleVolume());
        production.apuClockTick(1);
        assertEquals(6, production.getVolume());
        // The DMG cone instead retains JOVA/KENU/KERA and HORU phase. Until those cells are
        // modeled, copying this unconditional follow-up would only mirror the scheduler.
        assertEquals(7, resolution.next().visibleVolume());
        assertTrue(resolution.falsifiers().contains(
                RETAINED_PERIOD_TIMER_PHASE_AFTER_ZERO_TO_NONZERO));
    }

    @Test
    public void egTickCollisionAndNonDmgTimingAreExplicitFalsifiers() {
        int nr12 = nr12(8, true, 0);
        var collision = DmgEnvelopeWriteRipple.resolveWrite(
                DmgEnvelopeWriteRipple.State.loaded(nr12, 8, false),
                new DmgEnvelopeWriteRipple.WriteSignals(true, nr12, true));
        assertTrue(collision.falsifiers().contains(WRITE_WHILE_EG_TICK_HIGH));
        assertEquals(EnumSet.of(SUB_T_WRITE_APERTURE_TIMING, CGB_ENVELOPE_PROFILE),
                DmgEnvelopeWriteRipple.profileFalsifiers());
    }

    private static VolumeEnvelope activeProduction(int nr12) {
        VolumeEnvelope production = new VolumeEnvelope();
        production.start();
        production.setNr2(nr12, false);
        production.trigger();
        return production;
    }

    private static int nr12(int initialVolume, boolean directionUp, int period) {
        return initialVolume << 4 | (directionUp ? 0x08 : 0) | period;
    }

    private static String label(
            int volume,
            boolean oldDirectionUp,
            int oldPeriod,
            boolean nextDirectionUp,
            int nextPeriod) {
        return "volume=" + volume + ", direction=" + oldDirectionUp + "->" + nextDirectionUp
                + ", period=" + oldPeriod + "->" + nextPeriod;
    }

    private static boolean[] booleans() {
        return new boolean[]{false, true};
    }
}
