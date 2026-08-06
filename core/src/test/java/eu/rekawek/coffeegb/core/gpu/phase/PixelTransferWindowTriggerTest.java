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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PixelTransferWindowTriggerTest {

    @Test
    public void triggerRequiresEnabledWindowAtMatchingLineAndSurvivesDisable() {
        Harness h = new Harness();
        h.registers.put(GpuRegister.LY, 128);
        h.registers.put(GpuRegister.WY, 128);

        h.transfer.checkWindowY();
        assertFalse(h.transfer.isWindowYTriggered());

        h.lcdc.set(0xb1); // enable window while LY == WY
        h.transfer.checkWindowY();
        assertTrue(h.transfer.isWindowYTriggered());

        h.lcdc.set(0x91);
        h.transfer.checkWindowY();
        assertTrue(h.transfer.isWindowYTriggered());
    }

    @Test
    public void triggerResetsWithFrameAndRoundTripsThroughMemento() {
        Harness h = new Harness();
        h.registers.put(GpuRegister.LY, 42);
        h.registers.put(GpuRegister.WY, 42);
        h.lcdc.set(0xb1);
        h.transfer.checkWindowY();
        Memento<PixelTransfer> triggered = h.transfer.saveToMemento();

        h.transfer.resetWindowLineCounter();
        assertFalse(h.transfer.isWindowYTriggered());

        h.transfer.restoreFromMemento(triggered);
        assertTrue(h.transfer.isWindowYTriggered());
    }

    @Test
    public void persistentMasterOnlySamplesAtLineEdgeCheckpoints() {
        Harness h = new Harness();
        h.registers.put(GpuRegister.LY, 7);
        h.registers.put(GpuRegister.WY, 7);
        h.lcdc.set(0xb1);

        h.transfer.checkWindowY(7, 100);
        assertFalse("a mid-line equality must not set the persistent master",
                h.transfer.isWindowYTriggered());

        h.transfer.checkWindowY(7, 450);
        assertTrue("dot 450 samples the current LY/WY equality",
                h.transfer.isWindowYTriggered());

        h.registers.put(GpuRegister.WY, 0xff);
        h.lcdc.set(0x91);
        h.transfer.checkWindowY(7, 451);
        assertTrue("the line-edge master survives later WY and LCDC changes",
                h.transfer.isWindowYTriggered());
    }

    @Test
    public void secondaryWyComparatorUpdatesAfterItsRequestedDelay() {
        Harness h = new Harness(true);
        h.registers.put(GpuRegister.LY, 2);
        h.registers.put(GpuRegister.WY, 0xff);
        h.lcdc.set(0xb1);

        h.transfer.scheduleWindowYWrite(2, 2);
        h.registers.put(GpuRegister.WY, 2);

        h.transfer.checkWindowY(2, 100);
        assertFalse(h.transfer.isWindowYMatch());
        h.transfer.checkWindowY(2, 101);
        assertFalse(h.transfer.isWindowYMatch());
        h.transfer.checkWindowY(2, 102);
        assertTrue("the delayed WY copy can satisfy mode 3 without setting the master",
                h.transfer.isWindowYMatch());
        assertFalse(h.transfer.isWindowYTriggered());
    }

    @Test
    public void cgbLineZeroWriteCollisionAndPendingWyRoundTripThroughMemento() {
        Harness h = new Harness(true);
        h.registers.put(GpuRegister.LY, 0);
        h.registers.put(GpuRegister.WY, 0);
        h.lcdc.set(0xb1);

        h.transfer.scheduleWindowYWrite(0xff, 6);
        h.registers.put(GpuRegister.WY, 0xff);
        Memento<PixelTransfer> beforeLineZeroCheckpoint = h.transfer.saveToMemento();

        h.transfer.checkWindowY(153, 454);
        assertTrue("the CGB line-zero checkpoint sees pre-write WY on a collision",
                h.transfer.isWindowYTriggered());

        h.transfer.resetWindowLineCounter();
        h.transfer.restoreFromMemento(beforeLineZeroCheckpoint);
        h.transfer.checkWindowY(153, 454);
        assertTrue("the pending write and collision latch must survive restore",
                h.transfer.isWindowYTriggered());
    }

    @Test
    public void cgbWx166MatchAdvancesCounterWithoutCarryingIntoNextScanline() {
        Harness h = new Harness(true);
        h.registers.put(GpuRegister.LY, 0);
        h.registers.put(GpuRegister.WY, 0);
        h.registers.put(GpuRegister.WX, 166);
        h.lcdc.set(0xb1);
        h.transfer.checkWindowY();

        h.transfer.start();
        int ticks = 0;
        int remainingTicks = 1000;
        while (h.transfer.tick() && remainingTicks-- > 0) {
            ticks++;
            // Run the line through the final visible-pixel comparator match.
        }
        assertFalse("the end-of-line match should settle in HBlank", h.transfer.isWindowActivationPending());
        assertEquals("WX=166 advances the internal window Y counter",
                0, h.transfer.getWindowLineCounter());
        assertTrue("the final two startup states belong to the readable HBlank latch",
                h.transfer.isCgbWindowStartActive());

        Harness noTerminalMatch = new Harness(true);
        noTerminalMatch.registers.put(GpuRegister.LY, 0);
        noTerminalMatch.registers.put(GpuRegister.WY, 0);
        noTerminalMatch.registers.put(GpuRegister.WX, 167);
        noTerminalMatch.lcdc.set(0xb1);
        noTerminalMatch.transfer.checkWindowY();
        noTerminalMatch.transfer.start();
        int ordinaryTicks = 0;
        while (noTerminalMatch.transfer.tick()) {
            ordinaryTicks++;
        }
        assertEquals("the terminal window start extends the physical transfer by four dots",
                ordinaryTicks + 4, ticks);

        h.registers.put(GpuRegister.LY, 1);
        h.transfer.start();
        assertFalse("a pending activation is scanline-local", h.transfer.isWindowActivationPending());
    }

    @Test
    public void objectMatchingWindowActivationWaitsForFreshWindowTileData() {
        int beforeMatch = runWindowObjectLine(5);
        int onMatch = runWindowObjectLine(6);
        int afterMatch = runWindowObjectLine(7);

        // Hardware takes five more dots when the X=6 object and WX=5 window activate
        // together. The neighbouring X=7 phase is four dots longer than X=5. Starting
        // the object against the pre-window FIFO instead makes X=6 two dots shorter.
        assertEquals(beforeMatch + 5, onMatch);
        assertEquals(beforeMatch + 4, afterMatch);
    }

    @Test
    public void cgbDisabledWindowStartPlotsOneRetainedBackgroundPixelPerState() {
        Harness h = startCgbWindow();
        int startPosition = h.transfer.getPosition();

        h.transfer.tick();
        assertEquals("an enabled StartWindowDraw state stalls pixel output",
                startPosition, h.transfer.getPosition());

        h.lcdc.set(0x91);
        h.transfer.tick();
        assertEquals("a disabled StartWindowDraw state plots one retained background pixel",
                startPosition + 1, h.transfer.getPosition());
        assertFalse("clearing LCDC.5 clears the accepted CGB window state",
                h.transfer.isWindowActive());
        assertTrue("the line still records the startup cost",
                h.transfer.hasActivatedWindowOnLine());

        int remainingTicks = 20;
        while (h.transfer.isCgbWindowStartActive() && remainingTicks-- > 0) {
            h.transfer.tick();
        }
        assertTrue("the six-state startup must finish", remainingTicks > 0);
        assertFalse("the CGB returns to the background after the startup sequence",
                h.transfer.isWindowActive());
    }

    @Test
    public void cgbDisabledWindowStartRoundTripsThroughMemento() {
        Harness h = startCgbWindow();
        h.lcdc.set(0x91);
        h.transfer.tick();
        Memento<PixelTransfer> disabledStart = h.transfer.saveToMemento();

        int firstTicks = finishWindowStart(h.transfer);
        int firstPosition = h.transfer.getPosition();

        h.transfer.restoreFromMemento(disabledStart);
        int restoredTicks = finishWindowStart(h.transfer);
        assertEquals(firstTicks, restoredTicks);
        assertEquals(firstPosition, h.transfer.getPosition());
        assertFalse(h.transfer.isWindowActive());
    }

    @Test
    public void normalCgbWindowEnableEdgeIsInhibitedAndRoundTripsThroughMemento() {
        Harness h = new Harness(true);
        h.registers.put(GpuRegister.LY, 0);
        h.registers.put(GpuRegister.WY, 0);
        h.registers.put(GpuRegister.WX, 0);
        h.lcdc.set(0x91);
        h.transfer.checkWindowY();
        h.transfer.start();

        while (h.transfer.getPosition() < -7) {
            h.transfer.tick();
        }
        h.lcdc.set(0xb1);
        Memento<PixelTransfer> risingEdge = h.transfer.saveToMemento();

        h.transfer.tick();
        assertFalse("the comparator dot sees the pre-write LCDC.5 value",
                h.transfer.hasActivatedWindowOnLine());

        h.transfer.restoreFromMemento(risingEdge);
        h.transfer.tick();
        assertFalse("the inhibited edge must survive save-state restoration",
                h.transfer.hasActivatedWindowOnLine());
    }

    @Test
    public void doubleSpeedScx5ReenableUsesHeldWindowComparator() {
        Harness h = new Harness(true, 2);
        h.registers.put(GpuRegister.LY, 0);
        h.registers.put(GpuRegister.WY, 0);
        h.registers.put(GpuRegister.WX, 7);
        h.registers.put(GpuRegister.SCX, 5);
        h.lcdc.set(0xb1);
        h.transfer.checkWindowY();
        h.lcdc.set(0x91);
        h.transfer.start();

        int remainingTicks = 100;
        while (h.transfer.getPosition() < 1 && remainingTicks-- > 0) {
            h.transfer.tick();
        }
        assertTrue("the shifted output machine must reach the held f4 comparator",
                remainingTicks > 0);

        h.lcdc.set(0xb1);
        h.transfer.tick();
        assertTrue("SCX=5 holds X=WX for the double-speed re-enable edge",
                h.transfer.isWindowActivationPending());

        h.transfer.tick();
        assertTrue("the held comparator must commit a window activation",
                h.transfer.hasActivatedWindowOnLine());
    }

    private static Harness startCgbWindow() {
        Harness h = new Harness(true);
        h.registers.put(GpuRegister.LY, 0);
        h.registers.put(GpuRegister.WY, 0);
        h.registers.put(GpuRegister.WX, 7);
        h.lcdc.set(0xb1);
        h.transfer.checkWindowY();
        h.transfer.start();

        int remainingTicks = 100;
        while (!h.transfer.isCgbWindowStartActive() && remainingTicks-- > 0) {
            h.transfer.tick();
        }
        assertTrue("the WX comparator must enter StartWindowDraw", remainingTicks > 0);
        return h;
    }

    private static int finishWindowStart(PixelTransfer transfer) {
        int ticks = 0;
        while (transfer.isCgbWindowStartActive() && ticks < 20) {
            transfer.tick();
            ticks++;
        }
        assertTrue("the six-state startup must finish", ticks < 20);
        return ticks;
    }

    private static int runWindowObjectLine(int objectX) {
        Harness h = new Harness();
        h.registers.put(GpuRegister.LY, 0);
        h.registers.put(GpuRegister.WY, 0);
        h.registers.put(GpuRegister.WX, 5);
        h.lcdc.set(0xb3);
        h.transfer.checkWindowY();
        h.sprites[0].enable(objectX, 16, 0xfe00);

        h.transfer.start();
        int ticks = 1;
        while (h.transfer.tick()) {
            ticks++;
        }
        return ticks;
    }

    private static class Harness {

        private final GpuRegisterValues registers = new GpuRegisterValues();
        private final Lcdc lcdc = new Lcdc();
        private final SpritePosition[] sprites = new SpritePosition[10];
        private final PixelTransfer transfer;

        private Harness() {
            this(false);
        }

        private Harness(boolean gbc) {
            this(gbc, 1);
        }

        private Harness(boolean gbc, int speed) {
            registers.setGbc(gbc);
            lcdc.setGbc(gbc);
            for (int i = 0; i < sprites.length; i++) {
                sprites[i] = new SpritePosition();
            }
            SpeedMode speedMode = new SpeedMode(gbc) {
                @Override
                public int getSpeedMode() {
                    return speed;
                }
            };
            transfer = new PixelTransfer(
                    new Display(gbc),
                    new Ram(0x8000, 0x2000),
                    gbc ? new Ram(0x8000, 0x2000) : null,
                    new Ram(0xfe00, 0xa0),
                    lcdc,
                    registers,
                    gbc,
                    new ColorPalette(0xff68),
                    new ColorPalette(0xff6a),
                    sprites,
                    null,
                    speedMode,
                    0);
        }
    }
}
