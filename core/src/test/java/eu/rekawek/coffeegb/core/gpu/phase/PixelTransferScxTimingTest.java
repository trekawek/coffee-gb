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

public class PixelTransferScxTimingTest {

    @Test
    public void lcdEnableLineForwardScxChangeRestartsStartupSampler() {
        int unchanged = runLine(true, true, 3, 3, -7);
        int advanced = runLine(true, true, 3, 7, -7);

        assertEquals("four new fine-scroll phases plus the two-dot sampler restart",
                unchanged + 6, advanced);
        assertEquals("ordinary lines do not use the LCD-enable startup latch",
                runLine(true, false, 3, 3, -7),
                runLine(true, false, 3, 7, -7));
    }

    @Test
    public void cgbStartupLatchClosesOneDotBeforeDmg() {
        int cgbUnchanged = runLine(true, true, 0, 0, -4);
        assertEquals(cgbUnchanged, runLine(true, true, 0, 7, -4));

        int dmgUnchanged = runLine(false, true, 0, 0, -4);
        assertEquals(dmgUnchanged + 9, runLine(false, true, 0, 7, -4));
    }

    @Test
    public void lcdEnableLineIdentityRoundTripsThroughMemento() {
        Harness harness = new Harness(true);
        harness.registers.put(GpuRegister.SCX, 3);
        harness.transfer.start(0, true);
        advanceToPosition(harness.transfer, -7);
        Memento<PixelTransfer> startupState = harness.transfer.saveToMemento();

        harness.registers.put(GpuRegister.SCX, 7);
        int firstRun = finish(harness.transfer);

        harness.registers.put(GpuRegister.SCX, 3);
        harness.transfer.restoreFromMemento(startupState);
        harness.registers.put(GpuRegister.SCX, 7);
        assertEquals(firstRun, finish(harness.transfer));
    }

    private static int runLine(
            boolean gbc,
            boolean lcdEnableFirstLine,
            int initialScx,
            int writtenScx,
            int writePosition) {
        Harness harness = new Harness(gbc);
        harness.registers.put(GpuRegister.SCX, initialScx);
        harness.transfer.start(0, lcdEnableFirstLine);
        int ticks = advanceToPosition(harness.transfer, writePosition);
        harness.registers.put(GpuRegister.SCX, writtenScx);
        return ticks + finish(harness.transfer);
    }

    private static int advanceToPosition(PixelTransfer transfer, int position) {
        int ticks = 0;
        while (transfer.getPosition() != position) {
            transfer.tick();
            ticks++;
        }
        return ticks;
    }

    private static int finish(PixelTransfer transfer) {
        int ticks = 1;
        while (transfer.tick()) {
            ticks++;
        }
        return ticks;
    }

    private static class Harness {

        private final GpuRegisterValues registers = new GpuRegisterValues();

        private final PixelTransfer transfer;

        private Harness(boolean gbc) {
            registers.setGbc(gbc);
            Lcdc lcdc = new Lcdc();
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
