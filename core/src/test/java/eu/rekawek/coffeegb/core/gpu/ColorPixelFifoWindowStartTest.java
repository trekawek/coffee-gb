package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memento.Memento;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ColorPixelFifoWindowStartTest {

    @Test
    public void retainedBackgroundAndFreshWindowFifoRoundTripIndependently() {
        ColorPixelFifo fifo = createFifo();
        fifo.enqueue8Pixels(new int[] {0, 1, 2, 3, 0, 1, 2, 3}, TileAttributes.EMPTY);

        fifo.clearBg();
        fifo.enqueue8Pixels(new int[] {3, 2, 1, 0, 3, 2, 1, 0}, TileAttributes.EMPTY);
        assertEquals(8, fifo.getClearedBgLength());
        assertEquals(8, fifo.getLength());

        Memento<ColorPixelFifo> windowStart = fifo.saveToMemento();
        fifo.dropClearedBgPixel();
        fifo.dropPixel();
        assertEquals(7, fifo.getClearedBgLength());
        assertEquals(7, fifo.getLength());

        fifo.restoreFromMemento(windowStart);
        assertEquals(8, fifo.getClearedBgLength());
        assertEquals(8, fifo.getLength());

        fifo.discardClearedBg();
        assertEquals(0, fifo.getClearedBgLength());
        assertEquals(8, fifo.getLength());
    }

    private static ColorPixelFifo createFifo() {
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.setGbc(true);
        Lcdc lcdc = new Lcdc();
        lcdc.setGbc(true);
        lcdc.set(0x91);
        return new ColorPixelFifo(
                new Display(true),
                lcdc,
                new ColorPalette(0xff68),
                new ColorPalette(0xff6a),
                registers,
                new SpeedMode(true));
    }
}
