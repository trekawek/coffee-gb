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
    public void cgbWx166MatchAdvancesCounterWithoutCarryingIntoNextScanline() {
        Harness h = new Harness(true);
        h.registers.put(GpuRegister.LY, 0);
        h.registers.put(GpuRegister.WY, 0);
        h.registers.put(GpuRegister.WX, 166);
        h.lcdc.set(0xb1);
        h.transfer.checkWindowY();

        h.transfer.start();
        int guard = 1000;
        while (h.transfer.tick() && guard-- > 0) {
            // Run the line through the final visible-pixel comparator match.
        }
        assertFalse("the end-of-line match should settle in HBlank", h.transfer.isWindowActivationPending());
        assertEquals("WX=166 advances the internal window Y counter",
                0, h.transfer.getWindowLineCounter());

        h.registers.put(GpuRegister.LY, 1);
        h.transfer.start();
        assertFalse("a pending activation is scanline-local", h.transfer.isWindowActivationPending());
    }

    private static class Harness {

        private final GpuRegisterValues registers = new GpuRegisterValues();
        private final Lcdc lcdc = new Lcdc();
        private final PixelTransfer transfer;

        private Harness() {
            this(false);
        }

        private Harness(boolean gbc) {
            registers.setGbc(gbc);
            lcdc.setGbc(gbc);
            SpritePosition[] sprites = new SpritePosition[10];
            for (int i = 0; i < sprites.length; i++) {
                sprites[i] = new SpritePosition();
            }
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
                    new SpeedMode(gbc),
                    0);
        }
    }
}
