package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.ColorPalette;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PixelTransferWindowYDelayTest {

    @Test
    public void settledDelayReturnsMinusOneAcrossRepeatedCalls() {
        Harness h = new Harness();

        assertEquals(-1, h.transfer.advanceWindowYDelay());
        assertEquals(-1, h.transfer.advanceWindowYDelay());
        assertEquals(-1, h.transfer.advanceWindowYDelay());
    }

    @Test
    public void delayedWriteCountsDownReturnsOldWyOnceAndCommitsAtZero() {
        Harness h = new Harness();
        h.registers.put(GpuRegister.WY, 3);
        h.registers.put(GpuRegister.LY, 9);
        h.transfer.scheduleWindowYWrite(9, 2);

        assertFalse(h.transfer.isWindowYMatch());
        assertEquals(3, h.transfer.advanceWindowYDelay());
        assertFalse(h.transfer.isWindowYMatch());
        assertEquals(-1, h.transfer.advanceWindowYDelay());
        assertFalse(h.transfer.isWindowYMatch());
        assertEquals(-1, h.transfer.advanceWindowYDelay());
        assertTrue(h.transfer.isWindowYMatch());
        assertEquals(-1, h.transfer.advanceWindowYDelay());
    }

    @Test
    public void immediateWriteIsSettledAndVisibleWithoutAdvance() {
        Harness h = new Harness();
        h.registers.put(GpuRegister.LY, 11);
        h.transfer.scheduleWindowYWrite(11, 0);

        assertTrue(h.transfer.isWindowYMatch());
        assertEquals(-1, h.transfer.advanceWindowYDelay());
        assertEquals(-1, h.transfer.advanceWindowYDelay());
    }

    @Test
    public void countdownAndClearedCollisionLatchRoundTripThroughState() {
        Harness source = new Harness();
        source.registers.put(GpuRegister.WY, 4);
        source.registers.put(GpuRegister.LY, 12);
        source.transfer.scheduleWindowYWrite(12, 3);
        assertEquals(4, source.transfer.advanceWindowYDelay());
        ComponentState<PixelTransfer> countdown = source.transfer.captureState();

        Harness restored = new Harness();
        restored.registers.put(GpuRegister.LY, 12);
        restored.transfer.restoreState(countdown);

        for (int i = 0; i < 3; i++) {
            assertEquals(source.transfer.advanceWindowYDelay(),
                    restored.transfer.advanceWindowYDelay());
            assertEquals(source.transfer.isWindowYMatch(), restored.transfer.isWindowYMatch());
        }
        assertTrue(restored.transfer.isWindowYMatch());
        assertEquals(-1, restored.transfer.advanceWindowYDelay());
    }

    private static class Harness {

        private final GpuRegisterValues registers = new GpuRegisterValues();

        private final PixelTransfer transfer;

        private Harness() {
            registers.setGbc(true);
            Lcdc lcdc = new Lcdc();
            lcdc.setGbc(true);
            SpritePosition[] sprites = new SpritePosition[10];
            for (int i = 0; i < sprites.length; i++) {
                sprites[i] = new SpritePosition();
            }
            transfer = new PixelTransfer(
                    new Display(true),
                    new Ram(0x8000, 0x2000),
                    new Ram(0x8000, 0x2000),
                    new Ram(0xfe00, 0xa0),
                    lcdc,
                    registers,
                    true,
                    new ColorPalette(0xff68),
                    new ColorPalette(0xff6a),
                    sprites,
                    null,
                    new SpeedMode(true),
                    0);
        }
    }
}
