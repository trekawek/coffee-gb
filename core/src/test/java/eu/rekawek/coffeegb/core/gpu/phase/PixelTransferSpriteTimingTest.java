package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.ColorPalette;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PixelTransferSpriteTimingTest {

    @Test
    public void firstSpriteCostDependsOnBackgroundFetcherPhase() {
        int backgroundOnly = lineTicks(false);
        assertEquals(168, backgroundOnly);

        for (int x = 0; x < 168; x++) {
            int expectedCost = Math.max(11 - (x & 7), 6);
            assertEquals("sprite X=" + x,
                    backgroundOnly + expectedCost, lineTicks(false, x));
        }
        assertEquals("a sprite entirely beyond the right edge is not fetched",
                backgroundOnly, lineTicks(false, 168));
    }

    @Test
    public void laterSpritesCostSixDotsUnlessTheyStartANewFetcherTile() {
        int backgroundOnly = lineTicks(false);

        assertEquals(backgroundOnly + 11 + 6, lineTicks(false, 32, 32));
        assertEquals(backgroundOnly + 11 + 6, lineTicks(false, 32, 39));
        assertEquals(backgroundOnly + 11 + 11, lineTicks(false, 32, 40));
    }

    @Test
    public void objectTimingPenaltyTracksStallsAndAbortCatchUp() {
        int backgroundOnly = lineTicks(false);
        Harness ordinary = new Harness(false, true, 16, 24, 32);
        ordinary.transfer.start();
        int ordinaryTicks = runToEnd(ordinary.transfer);
        assertEquals(ordinaryTicks - backgroundOnly,
                ordinary.transfer.getObjectTimingPenalty());

        Harness aborted = new Harness(false, true, 16);
        aborted.transfer.start();
        int elapsed = 0;
        while (!aborted.transfer.isObjectFetchInProgress()) {
            aborted.transfer.tick();
            elapsed++;
        }
        aborted.transfer.tick();
        elapsed++;
        aborted.lcdc.set(0x91);
        int abortedRemaining = runToEnd(aborted.transfer);
        assertEquals(elapsed + abortedRemaining - backgroundOnly,
                aborted.transfer.getObjectTimingPenalty());
    }

    @Test
    public void cgbFetchesSelectedSpritesEvenWhenObjectDisplayIsDisabled() {
        assertEquals(lineTicks(true, true, 24), lineTicks(true, false, 24));
        assertEquals(lineTicks(false), lineTicks(false, false, 24));
    }

    @Test
    public void doubleSpeedScxWriteOnFirstOutputTickUsesPreviousFineScroll() {
        Harness early = doubleSpeedStartupHarness();
        early.transfer.start();
        for (int i = 0; i < 4; i++) {
            if (i == 2) {
                early.registers.put(GpuRegister.SCX, 4);
            }
            early.transfer.tick();
        }
        early.transfer.tick();
        assertEquals(-15, early.transfer.getPosition());

        Harness colliding = doubleSpeedStartupHarness();
        colliding.transfer.start();
        for (int i = 0; i < 4; i++) {
            colliding.transfer.tick();
        }
        colliding.registers.put(GpuRegister.SCX, 4);
        Memento<PixelTransfer> collisionState = colliding.transfer.saveToMemento();
        colliding.transfer.tick();
        assertEquals(-7, colliding.transfer.getPosition());

        Harness restored = doubleSpeedStartupHarness();
        restored.registers.put(GpuRegister.SCX, 4);
        restored.transfer.restoreFromMemento(collisionState);
        restored.transfer.tick();
        assertEquals(-7, restored.transfer.getPosition());
    }

    @Test
    public void everySpriteFetchStateRoundTripsThroughMemento() {
        Harness reference = new Harness(false, true, 16, 24, 32);
        reference.transfer.start();
        List<Memento<PixelTransfer>> states = new ArrayList<>();
        int totalTicks = 0;
        boolean active;
        do {
            states.add(reference.transfer.saveToMemento());
            active = reference.transfer.tick();
            totalTicks++;
        } while (active);

        for (int i = 0; i < states.size(); i++) {
            Harness restored = new Harness(false, true, 16, 24, 32);
            restored.transfer.restoreFromMemento(states.get(i));
            int remainingTicks = runToEnd(restored.transfer);
            assertEquals("state before tick " + i, totalTicks - i, remainingTicks);
        }
    }

    private static int lineTicks(boolean gbc, int... spriteXs) {
        return lineTicks(gbc, true, spriteXs);
    }

    private static int lineTicks(boolean gbc, boolean objectsEnabled, int... spriteXs) {
        Harness harness = new Harness(gbc, objectsEnabled, spriteXs);
        harness.transfer.start();
        return runToEnd(harness.transfer);
    }

    private static int runToEnd(PixelTransfer transfer) {
        int ticks = 0;
        boolean active;
        do {
            active = transfer.tick();
            ticks++;
            if (ticks > 1000) {
                throw new AssertionError("pixel transfer did not finish");
            }
        } while (active);
        return ticks;
    }

    private static Harness doubleSpeedStartupHarness() {
        SpeedMode doubleSpeed = new SpeedMode(true) {
            @Override
            public int getSpeedMode() {
                return 2;
            }
        };
        return new Harness(true, true, doubleSpeed, 4, 8);
    }

    private static class Harness {

        private final GpuRegisterValues registers;

        private final Lcdc lcdc;

        private final PixelTransfer transfer;

        private Harness(boolean gbc, boolean objectsEnabled, int... spriteXs) {
            this(gbc, objectsEnabled, new SpeedMode(gbc), 0, spriteXs);
        }

        private Harness(boolean gbc, boolean objectsEnabled, SpeedMode speedMode,
                        int entryDelay, int... spriteXs) {
            registers = new GpuRegisterValues();
            registers.setGbc(gbc);
            registers.setSpeedMode(speedMode);
            registers.put(GpuRegister.LY, 0);
            lcdc = new Lcdc();
            lcdc.setGbc(gbc);
            lcdc.set(objectsEnabled ? 0x93 : 0x91);

            SpritePosition[] sprites = new SpritePosition[10];
            Ram oam = new Ram(0xfe00, 0xa0);
            for (int i = 0; i < sprites.length; i++) {
                sprites[i] = new SpritePosition();
                if (i < spriteXs.length) {
                    sprites[i].enable(spriteXs[i], 16, 0xfe00 + 4 * i);
                }
            }

            transfer = new PixelTransfer(
                    new Display(gbc),
                    new Ram(0x8000, 0x2000),
                    gbc ? new Ram(0x8000, 0x2000) : null,
                    oam,
                    lcdc,
                    registers,
                    gbc,
                    new ColorPalette(0xff68),
                    new ColorPalette(0xff6a),
                    sprites,
                    null,
                    speedMode,
                    entryDelay);
        }
    }
}
