package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import java.util.Random;

import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static eu.rekawek.coffeegb.core.gpu.DmgPpuRegisterFanout.LCDC;
import static eu.rekawek.coffeegb.core.gpu.DmgPpuRegisterFanout.PIXEL_CAPTURE_EDGES;
import static eu.rekawek.coffeegb.core.gpu.DmgPpuRegisterFanout.SCX;
import static eu.rekawek.coffeegb.core.gpu.DmgPpuRegisterFanout.WX;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Executable evidence that selected CPU-to-PPU write workarounds can be replaced by one source
 * register feeding fixed consumer taps. These tests deliberately exercise the externally pinned
 * Coffee timing; they do not claim that five Java callback edges identify five silicon cells.
 */
public class DmgPpuRegisterFanoutTest {

    @Test
    public void oneWriteFansOutToImmediateAndPixelConsumers() {
        DmgPpuRegisterFanout fanout = new DmgPpuRegisterFanout(0x93, 0x80, 0x10);

        fanout.cpuWrite(LCDC, 0xb1);
        fanout.cpuWrite(SCX, 0x47);
        fanout.cpuWrite(WX, 0x55);

        assertEquals(0xb1, fanout.cpuRead(LCDC));
        assertEquals(0x47, fanout.cpuRead(SCX));
        assertEquals(0x55, fanout.cpuRead(WX));
        assertEquals(0xb1, fanout.timingLcdc());
        assertEquals(0x47, fanout.timingScx());
        assertEquals(0x55, fanout.timingWx());

        assertFalse(fanout.pixelWindowEnabled());
        assertEquals(0x40, fanout.pixelScx());
        assertEquals(0x10, fanout.pixelWx());

        clock(fanout, PIXEL_CAPTURE_EDGES - 1);
        assertFalse(fanout.pixelWindowEnabled());
        assertEquals(0x40, fanout.pixelScx());
        assertEquals(0x10, fanout.pixelWx());

        fanout.clockPpu();
        assertTrue(fanout.pixelWindowEnabled());
        assertEquals(0x47, fanout.pixelScx());
        assertEquals(0x55, fanout.pixelWx());
    }

    @Test
    public void rapidEdgesStayOrderedWithoutPendingWriteObjects() {
        DmgPpuRegisterFanout fanout = new DmgPpuRegisterFanout(0x93, 0, 0x10);

        fanout.cpuWrite(WX, 0x55);
        clock(fanout, 2);
        fanout.cpuWrite(WX, 0xaa);

        clock(fanout, 2);
        assertEquals(0x10, fanout.pixelWx());
        fanout.clockPpu();
        assertEquals(0x55, fanout.pixelWx());
        fanout.clockPpu();
        assertEquals(0x55, fanout.pixelWx());
        fanout.clockPpu();
        assertEquals(0xaa, fanout.pixelWx());
    }

    @Test
    public void unrelatedBitsUseTheirOwnDirectWires() {
        DmgPpuRegisterFanout fanout = new DmgPpuRegisterFanout(0x93, 0x80, 0);

        fanout.cpuWrite(LCDC, 0xf9);
        fanout.cpuWrite(SCX, 0x47);

        // LCDC.5 and SCX.0-.2 remain old at their pixel taps. All other wires are live.
        assertEquals(0xd9, fanout.pixelLcdc());
        assertEquals(0x40, fanout.pixelScx());

        clock(fanout, PIXEL_CAPTURE_EDGES);
        assertEquals(0xf9, fanout.pixelLcdc());
        assertEquals(0x47, fanout.pixelScx());
    }

    @Test
    public void lcdResetOpensTheConsumerLatchesAndDiscardsHistory() {
        DmgPpuRegisterFanout fanout = new DmgPpuRegisterFanout(0x93, 0, 0x10);
        fanout.cpuWrite(WX, 0x55);
        clock(fanout, 2);

        fanout.cpuWrite(LCDC, 0x13);

        assertEquals(0x55, fanout.pixelWx());
        fanout.cpuWrite(LCDC, 0x93);
        clock(fanout, PIXEL_CAPTURE_EDGES + 2);
        assertEquals(0x55, fanout.pixelWx());
    }

    @Test
    public void packedTapStateRestoresEveryInFlightWrite() {
        DmgPpuRegisterFanout fanout = new DmgPpuRegisterFanout(0x93, 0, 0x10);
        fanout.cpuWrite(WX, 0x55);
        clock(fanout, 2);
        fanout.cpuWrite(WX, 0xaa);
        DmgPpuRegisterFanout.State state = fanout.capture();

        clock(fanout, 5);
        assertEquals(0xaa, fanout.pixelWx());

        fanout.restore(state);
        clock(fanout, 3);
        assertEquals(0x55, fanout.pixelWx());
        clock(fanout, 2);
        assertEquals(0xaa, fanout.pixelWx());
    }

    @Test
    public void fixedFanoutDifferentialMatchesCpuReachableProductionWrites() {
        Fixture fixture = new Fixture();
        fixture.advanceTo(1, 100);
        DmgPpuRegisterFanout fanout = new DmgPpuRegisterFanout(0x93, 0, 0);
        Random random = new Random(0x50475546414e4f55L);

        for (int dot = 0; dot < 112; dot++) {
            // DMG exposes one CPU write strobe per four-dot machine cycle. Dense direct Java
            // calls between those strobes are useful fixture stress, but are not a reachable
            // hardware waveform and must not force queue semantics into the signal model.
            if ((dot & 3) == 0) {
                switch (random.nextInt(4)) {
                    case 0 -> {
                        int wx = random.nextInt(256);
                        fixture.gpu.setByteFromCpu(WX, wx);
                        fanout.cpuWrite(WX, wx);
                    }
                    case 1 -> {
                        int scx = random.nextInt(256);
                        fixture.gpu.setByteFromCpu(SCX, scx);
                        fanout.cpuWrite(SCX, scx);
                    }
                    case 2 -> {
                        int lcdc = 0x93 | (random.nextBoolean() ? 0x20 : 0);
                        fixture.gpu.setByteFromCpu(LCDC, lcdc);
                        fanout.cpuWrite(LCDC, lcdc);
                    }
                    default -> {
                        // Idle M-cycle.
                    }
                }
            }

            assertEquivalent(fixture.gpu, fanout, "before dot " + dot);
            fixture.tick();
            fanout.clockPpu();
            assertEquivalent(fixture.gpu, fanout, "after dot " + dot);
        }
    }

    private static void assertEquivalent(Gpu gpu, DmgPpuRegisterFanout fanout, String phase) {
        assertEquals(phase + " CPU LCDC", gpu.getByte(LCDC), fanout.cpuRead(LCDC));
        assertEquals(phase + " CPU SCX", gpu.getByte(SCX), fanout.cpuRead(SCX));
        assertEquals(phase + " CPU WX", gpu.getByte(WX), fanout.cpuRead(WX));
        assertEquals(phase + " timing LCDC", gpu.getLcdc().get(), fanout.timingLcdc());
        assertEquals(phase + " pixel LCDC.5",
                gpu.isPixelWindowDisplayVisible(), fanout.pixelWindowEnabled());
        assertEquals(phase + " pixel SCX", gpu.getRegisters().get(GpuRegister.SCX),
                fanout.pixelScx());
        assertEquals(phase + " pixel WX", gpu.getPixelWindowXVisible(), fanout.pixelWx());
    }

    private static void clock(DmgPpuRegisterFanout fanout, int dots) {
        for (int i = 0; i < dots; i++) {
            fanout.clockPpu();
        }
    }

    private static final class Fixture {

        private final StatRegister stat;

        private final Gpu gpu;

        private Fixture() {
            Ram oam = new Ram(0xfe00, 0xa0);
            InterruptManager interrupts = new InterruptManager(false);
            stat = new StatRegister(interrupts);
            SpeedMode speedMode = new SpeedMode(false);
            gpu = new Gpu(
                    new Display(false),
                    new Dma(new Ram(0, 0x10000), oam, speedMode),
                    oam,
                    new VRamTransfer(NULL_EVENT_BUS),
                    stat,
                    false,
                    speedMode);
            stat.init(gpu);
            gpu.setByte(LCDC, 0x93);
        }

        private void advanceTo(int line, int ticksInLine) {
            while (gpu.getLine() != line || gpu.getTicksInLine() != ticksInLine) {
                tick();
            }
        }

        private void tick() {
            gpu.tick();
            stat.tick();
        }
    }
}
