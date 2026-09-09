package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

public class PerformanceSteadyOutputTileTest {
    @Test public void orderedReadsPixelsAndFullFifoStateMatchEightOriginalDots() throws Exception {
        for (int lcdc : new int[]{0x91, 0x80, 0x99, 0x88})
            for (int scx = 0; scx < 8; scx++)
                for (int attrs : new int[]{0, 8, 0x20, 0x40, 0x80, 0xff})
                    for (int position : new int[]{0, 7, 144, 152}) {
                        TileFixture a = new TileFixture(lcdc, scx, attrs, position);
                        TileFixture b = new TileFixture(lcdc, scx, attrs, position);
                        assertTrue(b.fetcher.tryAdvanceSteadyBackgroundOutputTile(position));
                        for (int dot = 1; dot <= 8; dot++) {
                            a.fifo.outputTick(); a.fifo.putPixelToScreen();
                            a.fetcher.advanceSteadyBackground(position + dot);
                        }
                        String label = "lcdc=" + lcdc + " scx=" + scx + " attr=" + attrs + " p=" + position;
                        assertStateEquals(label + " FIFO", a.fifo.captureState(), b.fifo.captureState());
                        assertStateEquals(label + " Fetcher", a.fetcher.captureState(), b.fetcher.captureState());
                        assertStateEquals(label + " Display", a.display.captureState(), b.display.captureState());
                        assertEquals(label + " every pixel and live FIFO at publication", a.pixels, b.pixels);
                        assertEquals(label + " every ordered VRAM transaction", a.reads, b.reads);
                        assertEquals(List.of(104L, 104L, 106L, 108L), b.reads.stream().map(Read::tick).toList());
                        assertEquals(List.of(4, 4, 2, 0), b.reads.stream().map(Read::size).toList());
                        for (String name : new String[]{"tileMapX", "xBasePosition", "xBaseObjectFetch", "tileMapOffset"})
                            assertEquals(label + " live " + name, field(a.fetcher, name), field(b.fetcher, name));
                    }
    }

    @Test public void unsuitableOutputTileEntriesRejectWithoutStateOrPublication() throws Exception {
        for (int kind = 0; kind < 10; kind++) {
            TileFixture f = new TileFixture(0x91, 0, 0, 0);
            int position = 0;
            switch (kind) {
                case 0 -> position = -1;
                case 1 -> position = 153;
                case 2 -> ((IntQueue)field(f.fifo, "background")).size = 7;
                case 3 -> ((IntQueue)field(f.fifo, "background")).size = 9;
                case 4 -> ((IntQueue)field(f.fifo, "clearedBackground")).size = 1;
                case 5 -> ((SpriteFifo)field(f.fifo, "spriteFifo")).size = 1;
                case 6 -> f.fifo.setDmgCompat(true);
                case 7 -> f.fifo.setRenderOutput(false);
                case 8 -> set(f.fifo, "delaySize", 2);
                case 9 -> {
                    set(f.fifo, "delaySize", 1);
                    ((int[])field(f.fifo, "delayEntry"))[0] = 64;
                }
            }
            var fifo = f.fifo.captureState(); var fetch = f.fetcher.captureState();
            assertFalse("guard=" + kind, f.fetcher.tryAdvanceSteadyBackgroundOutputTile(position));
            assertStateEquals("inert FIFO guard=" + kind, fifo, f.fifo.captureState());
            assertStateEquals("inert fetch guard=" + kind, fetch, f.fetcher.captureState());
            assertTrue(f.reads.isEmpty()); assertTrue(f.pixels.isEmpty());
        }
    }

    @Test public void nativeProfilesEveryScrollAndRestoredOutputSplitMatchOriginalReplay() throws Exception {
        for (HardwareProfile profile : new HardwareProfile[]{HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0})
            for (int speed : new int[]{1, 2}) for (int scx = 0; scx < 8; scx++)
                try (Gameboy a = session(profile, speed, scx); Gameboy b = session(profile, speed, scx)) {
                    PixelTransfer scalar = output(a), bulk = output(b);
                    var sa = a.getGpu().captureState(); var sb = b.getGpu().captureState();
                    Fetcher fetch = (Fetcher) field(bulk, "fetcher");
                    while (bulk.getPosition() < 0 || fetch.getState() != Fetcher.GET_TILE_T1)
                        bulk.advanceSteadyBackgroundOutputSpan(1);
                    assertTrue(profile.id() + " real output tile proof", fetch.tryAdvanceSteadyBackgroundOutputTile(bulk.getPosition()));
                    int total = 172 + scx;
                    for (int split = 0; split <= total; split++) {
                        a.getGpu().restoreState(detach(sa)); b.getGpu().restoreState(detach(sb));
                        for (int i = 0; i < split; i++) scalar.advanceSteadyBackgroundOutputSpan(1);
                        bulk.advanceSteadyBackgroundOutputSpan(split);
                        String label = profile.id() + " x" + speed + " scx=" + scx + " split=" + split;
                        assertStateEquals(label + " prefix GPU/display", a.getGpu().captureState(), b.getGpu().captureState());
                        a.getGpu().restoreState(a.getGpu().captureState());
                        b.getGpu().restoreState(b.getGpu().captureState());
                        for (int i = split; i < total; i++) scalar.advanceSteadyBackgroundOutputSpan(1);
                        bulk.advanceSteadyBackgroundOutputSpan(total - split);
                        assertStateEquals(label + " restored GPU/display", a.getGpu().captureState(), b.getGpu().captureState());
                        assertEquals(160, bulk.getPosition());
                    }
                    assertStateEquals("whole machine", a.captureStateWithoutTimeSource(), b.captureStateWithoutTimeSource());
                }
    }

    private static Gameboy session(HardwareProfile profile, int speed, int scx) throws Exception {
        byte[] image = new byte[0x8000]; image[0x100] = (byte) 0xc3; image[0x102] = 1; image[0x143] = (byte) 0x80;
        Gameboy g = new Gameboy.GameboyConfiguration(new Rom(image)).setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setExecutionMode(ExecutionMode.ACCURACY)
                .setSupportBatterySave(false).setRtcTimeSource(() -> 0L).build();
        if (speed == 2) {
            g.getSpeedMode().setByte(0xff4d, 1);
            var stop = SpeedMode.class.getDeclaredMethod("onStop"); stop.setAccessible(true);
            assertEquals(true, stop.invoke(g.getSpeedMode()));
        }
        Gpu gpu = g.getGpu();
        for (int bank = 0; bank < 2; bank++) {
            gpu.setByte(0xff4f, bank);
            for (int address = 0x8000; address < 0xa000; address++)
                gpu.setByte(address, (address * 37 ^ address >>> 3 ^ 0x5a ^ bank * 0x3b) & 255);
        }
        gpu.setByte(0xff4f, 0); gpu.setByte(0xff42, scx * 19 + 3); gpu.setByte(0xff43, scx);
        for (int address = 0; address < 64; address++) {
            gpu.setByte(0xff68, address); gpu.setByte(0xff69, (address * 59 + scx * 7) & 255);
        }
        while (gpu.getLine() != 1 || gpu.getTicksInLine() != 80) g.tick();
        return g;
    }
    private static PixelTransfer output(Gameboy g) throws Exception { return (PixelTransfer) field(g.getGpu(), "pixelMachine"); }
    private static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value);
    }
    @SuppressWarnings("unchecked") private static <T> T detach(T value) throws Exception {
        if (value == null) return null;
        Class<?> c = value.getClass();
        if (c.isArray()) {
            int n = Array.getLength(value); Object copy = Array.newInstance(c.getComponentType(), n);
            if (c.getComponentType().isPrimitive()) System.arraycopy(value, 0, copy, 0, n);
            else for (int i = 0; i < n; i++) Array.set(copy, i, detach(Array.get(value, i)));
            return (T) copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(); for (Object e : list) copy.add(detach(e)); return (T) copy;
        }
        if (c.isRecord()) {
            var fields = c.getRecordComponents(); Class<?>[] types = new Class<?>[fields.length]; Object[] args = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) { types[i] = fields[i].getType(); var m = fields[i].getAccessor(); m.setAccessible(true); args[i] = detach(m.invoke(value)); }
            var constructor = c.getDeclaredConstructor(types); constructor.setAccessible(true); return (T) constructor.newInstance(args);
        }
        return value;
    }
    private record Read(long tick, int size, int bank, int address, int published) {}
    private record Pixel(int color, long tick, int linePixels, int size, int offset, int delayHead,
                         int delaySize, int underflow, int poppedPixel, int poppedPalette, boolean poppedPriority) {}
    private static final class TileFixture {
        final List<Pixel> pixels = new ArrayList<>(); final List<Read> reads = new ArrayList<>();
        final ColorPixelFifo fifo; final Display display; final Fetcher fetcher;
        TileFixture(int control, int scx, int attrs, int position) throws Exception {
            GpuRegisterValues r = new GpuRegisterValues(); r.setGbc(true); r.put(GpuRegister.LY, 37);
            r.put(GpuRegister.SCY, (scx * 19 + position) & 255); r.put(GpuRegister.SCX, scx);
            Lcdc lcdc = new Lcdc(); lcdc.setGbc(true); lcdc.setByte(0xff40, control);
            for (int i = 0; i < 9; i++) lcdc.tickConflicts();
            ColorPalette bg = new ColorPalette(0xff68), obj = new ColorPalette(0xff6a);
            for (int p = 0; p < 8; p++) for (int c = 0; c < 4; c++) bg.getPalette(p)[c] = (p * 4079 + c * 197) & 0x7fff;
            display = new Display(true) {
                @Override void putColorPixel(int color) {
                    try {
                        IntQueue q = (IntQueue) field(fifo, "background"); SpriteFifo s = (SpriteFifo) field(fifo, "spriteFifo");
                        pixels.add(new Pixel(color, (Long)field(fifo,"outputTicks"), (Integer)field(fifo,"linePixels"), q.size, q.offset,
                                (Integer)field(fifo,"delayHead"), (Integer)field(fifo,"delaySize"), s.underflow, s.poppedPixel, s.poppedPalette, s.poppedBgPriority));
                    } catch (Exception e) { throw new AssertionError(e); }
                    super.putColorPixel(color);
                }
            };
            fifo = new ColorPixelFifo(display, lcdc, bg, obj, r, null);
            IntQueue q = (IntQueue) field(fifo, "background");
            for (int i = 0; i < 16; i++) q.array[i] = (i * 11 + attrs) & 63;
            q.size = 8; q.offset = (scx + attrs) & 15;
            SpriteFifo sprite = (SpriteFifo) field(fifo, "spriteFifo");
            sprite.underflow = 12; sprite.head = scx; sprite.poppedPixel = 3; sprite.poppedPalette = 5; sprite.poppedBgPriority = true;
            set(fifo, "outputTicks", 100L); set(fifo, "linePixels", position);
            set(fifo, "delayHead", (scx + attrs) & 7); set(fifo, "delaySize", position & 1);
            int[] entry = (int[])field(fifo, "delayEntry"); long[] stamps = (long[])field(fifo, "delayStamp");
            for (int i = 0; i < 8; i++) { entry[i] = (i * 13 + scx) & 63; stamps[i] = 92 + i; }
            fetcher = new Fetcher(fifo, memory(0, attrs), memory(1, attrs), new Ram(0xfe00, 0xa0), lcdc, r, true); fetcher.startLine();
        }
        private AddressSpace memory(int bank, int attrs) {
            return new AddressSpace() {
                public boolean accepts(int address) { return address >= 0x8000 && address < 0xa000; }
                public void setByte(int address, int value) { throw new AssertionError(); }
                public int getByte(int address) {
                    try { reads.add(new Read((Long)field(fifo,"outputTicks"), fifo.getLength(), bank, address, pixels.size())); }
                    catch (Exception e) { throw new AssertionError(e); }
                    return address >= 0x9800 ? bank == 1 ? attrs : (address * 13 + 0x80) & 255 : (address * 17 + bank * 37) & 255;
                }
            };
        }
    }
}
