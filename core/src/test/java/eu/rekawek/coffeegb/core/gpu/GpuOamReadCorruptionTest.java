package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static org.junit.Assert.assertEquals;

public class GpuOamReadCorruptionTest {

    @Test
    public void dmgReadDuringRegularScanCorruptsCurrentRows() {
        Fixture fixture = new Fixture(false);
        fixture.advanceTo(0, 48);

        assertEquals(0xff, fixture.gpu.getByte(0xfe00));
        assertEquals(0x60, fixture.oam.getByte(0xfe68));
        assertEquals(0x61, fixture.oam.getByte(0xfe69));
    }

    @Test
    public void dmgReadAfterLastScanRowCopiesTheOamLatch() {
        Fixture fixture = new Fixture(false);
        fixture.advanceTo(0, 76);

        assertEquals(0xff, fixture.gpu.getByte(0xfe00));
        assertEquals(0x98, fixture.oam.getByte(0xfe00));
        assertEquals(0x99, fixture.oam.getByte(0xfe01));
    }

    @Test
    public void dmgReadAtEarlyLineEdgeUsesAddressedRow() {
        Fixture fixture = new Fixture(false);
        fixture.advanceTo(0, 452);

        assertEquals(0xff, fixture.gpu.getByte(0xfe26));
        for (int i = 0; i < 8; i++) {
            assertEquals(0x20 + i, fixture.oam.getByte(0xfe00 + i));
        }
    }

    @Test
    public void cgbBlockedReadDoesNotCorruptOam() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(0, 48);

        assertEquals(0xff, fixture.gpu.getByte(0xfe00));
        for (int i = 0; i < 8; i++) {
            assertEquals(0x68 + i, fixture.oam.getByte(0xfe68 + i));
        }
    }

    private static class Fixture {

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final StatRegister stat;

        private final Gpu gpu;

        private Fixture(boolean gbc) {
            for (int i = 0; i < 0xa0; i++) {
                oam.setByte(0xfe00 + i, i);
            }
            InterruptManager interrupts = new InterruptManager(gbc);
            stat = new StatRegister(interrupts);
            SpeedMode speedMode = new SpeedMode(gbc);
            gpu = new Gpu(
                    new Display(gbc),
                    new Dma(new Ram(0, 0x10000), oam, speedMode),
                    oam,
                    new VRamTransfer(NULL_EVENT_BUS),
                    stat,
                    gbc,
                    speedMode);
            stat.init(gpu);
        }

        private void advanceTo(int line, int ticksInLine) {
            while (gpu.getLine() != line || gpu.getTicksInLine() != ticksInLine) {
                gpu.tick();
                stat.tick();
            }
        }
    }
}
