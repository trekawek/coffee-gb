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

    private static class Harness {

        private final GpuRegisterValues registers = new GpuRegisterValues();
        private final Lcdc lcdc = new Lcdc();
        private final PixelTransfer transfer;

        private Harness() {
            SpritePosition[] sprites = new SpritePosition[10];
            for (int i = 0; i < sprites.length; i++) {
                sprites[i] = new SpritePosition();
            }
            transfer = new PixelTransfer(
                    new Display(false),
                    new Ram(0x8000, 0x2000),
                    null,
                    new Ram(0xfe00, 0xa0),
                    lcdc,
                    registers,
                    false,
                    new ColorPalette(0xff68),
                    new ColorPalette(0xff6a),
                    sprites,
                    null,
                    new SpeedMode(false),
                    0);
        }
    }
}
