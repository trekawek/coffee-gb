package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

public class PerformanceSteadyTimingTileTest {
    @Test
    public void orderedReadsAndEveryFetchedStateMatchEightOriginalSteadyDots() throws Exception {
        for (int lcdc : new int[]{0x91, 0x81, 0x99, 0x89}) {
            for (int scx = 0; scx < 8; scx++) {
                for (int attrs : new int[]{0, 8, 0x20, 0x40, 0x80, 0xff}) {
                    for (int position : new int[]{0, 7, 144, 152}) {
                        TileFixture actual = new TileFixture(lcdc, scx, attrs, position);
                        TileFixture expected = new TileFixture(lcdc, scx, attrs, position);
                        assertTrue(actual.fetcher.tryAdvanceSteadyBackgroundTimingTile(position));
                        for (int dot = 1; dot <= 8; dot++) {
                            expected.fifo.outputTick();
                            expected.fifo.putPixelToScreen();
                            expected.fetcher.advanceSteadyBackground(position + dot);
                        }
                        String label = "lcdc=" + lcdc + " scx=" + scx + " attrs=" + attrs + " p=" + position;
                        assertStateEquals(label + " FIFO", expected.fifo.captureState(), actual.fifo.captureState());
                        assertStateEquals(label + " Fetcher", expected.fetcher.captureState(), actual.fetcher.captureState());
                        assertEquals(label + " ordered read transactions", expected.reads, actual.reads);
                        assertEquals(4, actual.reads.size());
                        assertEquals(List.of(104L, 104L, 106L, 108L),
                                actual.reads.stream().map(Read::outputTick).toList());
                        assertEquals(List.of(4, 4, 2, 0), actual.reads.stream().map(Read::backgroundSize).toList());
                        // These live derived values are not all carried by FetcherState.
                        for (String name : new String[]{"tileMapX", "xBasePosition", "xBaseObjectFetch", "tileMapOffset"}) {
                            assertEquals(label + " live " + name, field(expected.fetcher, name), field(actual.fetcher, name));
                        }
                        assertEquals(position + 1, field(actual.fetcher, "xBasePosition"));
                    }
                }
            }
        }
    }

    @Test
    public void unsuitableTileEntriesRejectWithoutChangingState() throws Exception {
        for (int invalidPosition : new int[]{-1, 153, 160}) {
            TileFixture fixture = new TileFixture(0x91, 0, 0, 0);
            var before = fixture.fetcher.captureState();
            var fifo = fixture.fifo.captureState();
            assertFalse(fixture.fetcher.tryAdvanceSteadyBackgroundTimingTile(invalidPosition));
            assertStateEquals("rejected position Fetcher", before, fixture.fetcher.captureState());
            assertStateEquals("rejected position FIFO", fifo, fixture.fifo.captureState());
            assertTrue(fixture.reads.isEmpty());
        }
        for (int background : new int[]{7, 9}) {
            TileFixture fixture = new TileFixture(0x91, 0, 0, 0);
            fixture.fifo.restoreTimingState(new ScalarTimingColorPixelFifo.State(background, 0, 0, 12, 0, 0, 100, 0));
            assertFalse(fixture.fetcher.tryAdvanceSteadyBackgroundTimingTile(0));
            assertTrue(fixture.reads.isEmpty());
        }
        TileFixture sprite = new TileFixture(0x91, 0, 0, 0);
        sprite.fifo.restoreTimingState(new ScalarTimingColorPixelFifo.State(8, 0, 1, 0, 0, 0, 100, 0));
        assertFalse(sprite.fetcher.tryAdvanceSteadyBackgroundTimingTile(0));
        TileFixture phase = new TileFixture(0x91, 0, 0, 0);
        phase.fetcher.advanceSteadyBackground(1);
        assertFalse(phase.fetcher.tryAdvanceSteadyBackgroundTimingTile(0));
    }

    @Test
    public void bothNativeProfilesEveryScrollPrefixAndRestoredSplitMatchOriginalSteadyReplay() throws Exception {
        for (HardwareProfile profile : new HardwareProfile[]{HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0}) {
            for (int speed : new int[]{1, 2}) {
                for (int scx = 0; scx < 8; scx++) {
                    try (Gameboy expected = session(profile, speed, scx); Gameboy actual = session(profile, speed, scx)) {
                        PixelTransfer scalar = timing(expected);
                        PixelTransfer bulk = timing(actual);
                        assertTrue(scalar.usesScalarTimingFifo());
                        var expectedStart = expected.getGpu().captureState();
                        var actualStart = actual.getGpu().captureState();
                        // Positively demonstrate that this real native-profile fixture reaches
                        // the new tile proof before testing each arbitrary capture split.
                        Fetcher fetcher = (Fetcher) field(bulk, "fetcher");
                        while (bulk.getPosition() < 0 || fetcher.getState() != Fetcher.GET_TILE_T1) {
                            bulk.advanceSteadyBackgroundSpan(1);
                        }
                        assertTrue(profile.id() + " tile proof", fetcher.tryAdvanceSteadyBackgroundTimingTile(bulk.getPosition()));
                        actual.getGpu().restoreState(actualStart);

                        int total = 168 + scx;
                        for (int split = 0; split <= total; split++) {
                            expected.getGpu().restoreState(expectedStart);
                            actual.getGpu().restoreState(actualStart);
                            for (int i = 0; i < split; i++) scalar.advanceSteadyBackgroundSpan(1);
                            bulk.advanceSteadyBackgroundSpan(split);
                            String label = profile.id() + " x" + speed + " scx=" + scx + " split=" + split;
                            assertStateEquals(label + " prefix", scalar.captureState(), bulk.captureState());
                            // Restore both complete GPU states, then continue through the rest
                            // of the line with the original per-dot materializer as oracle.
                            var expectedMiddle = expected.getGpu().captureState();
                            var actualMiddle = actual.getGpu().captureState();
                            expected.getGpu().restoreState(expectedMiddle);
                            actual.getGpu().restoreState(actualMiddle);
                            for (int i = split; i < total; i++) scalar.advanceSteadyBackgroundSpan(1);
                            bulk.advanceSteadyBackgroundSpan(total - split);
                            assertStateEquals(label + " restored GPU", expected.getGpu().captureState(), actual.getGpu().captureState());
                            assertEquals(160, bulk.getPosition());
                        }
                        assertStateEquals(profile.id() + " whole machine", expected.captureStateWithoutTimeSource(), actual.captureStateWithoutTimeSource());
                    }
                }
            }
        }
    }

    private static Gameboy session(HardwareProfile profile, int speed, int scx) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3; image[0x102] = 1; image[0x143] = (byte) 0x80;
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile).setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.ACCURACY).setSupportBatterySave(false).build();
        if (speed == 2) {
            gameboy.getSpeedMode().setByte(0xff4d, 1);
            var stop = SpeedMode.class.getDeclaredMethod("onStop");
            stop.setAccessible(true);
            assertEquals(true, stop.invoke(gameboy.getSpeedMode()));
        }
        Gpu gpu = gameboy.getGpu();
        for (int bank = 0; bank < 2; bank++) {
            gpu.setByte(0xff4f, bank);
            for (int address = 0x8000; address < 0xa000; address++) {
                gpu.setByte(address, (address * 37 ^ address >>> 3 ^ 0x5a ^ bank * 0x3b) & 255);
            }
        }
        gpu.setByte(0xff4f, 0);
        gpu.setByte(0xff42, scx * 19 + 3);
        gpu.setByte(0xff43, scx);
        while (gpu.getLine() != 1 || gpu.getTicksInLine() != 80) gameboy.tick();
        return gameboy;
    }

    private static PixelTransfer timing(Gameboy gameboy) throws Exception {
        return (PixelTransfer) field(gameboy.getGpu(), "pixelTransferPhase");
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private record Read(long outputTick, int backgroundSize, int bank, int address) {}

    private static final class TileFixture {
        final ScalarTimingColorPixelFifo fifo = new ScalarTimingColorPixelFifo();
        final List<Read> reads = new ArrayList<>();
        final Fetcher fetcher;
        TileFixture(int control, int scx, int attrs, int position) {
            GpuRegisterValues registers = new GpuRegisterValues();
            registers.setGbc(true);
            registers.put(GpuRegister.LY, 37);
            registers.put(GpuRegister.SCY, (scx * 19 + position) & 255);
            registers.put(GpuRegister.SCX, scx);
            Lcdc lcdc = new Lcdc();
            lcdc.setGbc(true);
            lcdc.setByte(0xff40, control);
            for (int dot = 0; dot < 9; dot++) lcdc.tickConflicts();
            fifo.restoreTimingState(new ScalarTimingColorPixelFifo.State(8, 0, 0, 12,
                    (scx + attrs) & 7, position & 1, 100, position));
            fetcher = new Fetcher(fifo, memory(0, attrs), memory(1, attrs),
                    new Ram(0xfe00, 0xa0), lcdc, registers, true);
            fetcher.startLine();
        }
        AddressSpace memory(int bank, int attrs) {
            return new AddressSpace() {
                public boolean accepts(int address) { return address >= 0x8000 && address < 0xa000; }
                public void setByte(int address, int value) { throw new AssertionError("read-only fixture"); }
                public int getByte(int address) {
                    reads.add(new Read(fifo.getOutputTicks(), fifo.getLength(), bank, address));
                    return address >= 0x9800 ? bank == 1 ? attrs : (address * 13 + 0x80) & 255
                            : (address * 17 + bank * 37) & 255;
                }
            };
        }
    }
}
