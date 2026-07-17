package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.gpu.Mode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HdmaTest {

    @Test
    public void generalPurposeDmaIncludesStartupTicks() {
        Fixture fixture = new Fixture();
        fixture.startTransfer(0x00);

        fixture.tick(33);
        assertEquals(0, fixture.memory.getByte(0x8000));

        fixture.tick(1);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0xaf, fixture.memory.getByte(0x800f));
        assertEquals(0xff, fixture.hdma.getByte(0xff55));
    }

    @Test
    public void generalPurposeDmaPaysStartupCostOnlyOnce() {
        Fixture fixture = new Fixture();
        fixture.startTransfer(0x01);

        fixture.tick(34);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0, fixture.memory.getByte(0x8010));

        fixture.tick(31);
        assertEquals(0, fixture.memory.getByte(0x8010));

        fixture.tick(1);
        assertEquals(0xb0, fixture.memory.getByte(0x8010));
        assertEquals(0xbf, fixture.memory.getByte(0x801f));
    }

    @Test
    public void hblankDmaIncludesStartupTicksForEveryBurst() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x81);

        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.tick(34);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0, fixture.memory.getByte(0x8010));

        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.tick(33);
        assertEquals(0, fixture.memory.getByte(0x8010));

        fixture.tick(1);
        assertEquals(0xb0, fixture.memory.getByte(0x8010));
        assertEquals(0xbf, fixture.memory.getByte(0x801f));
    }

    private static class Fixture {

        private final Ram memory = new Ram(0, 0x10000);

        private final Hdma hdma = new Hdma(memory);

        private Fixture() {
            for (int i = 0; i < 0x20; i++) {
                memory.setByte(0x1200 + i, 0xa0 + i);
            }
            hdma.setByte(0xff51, 0x12);
            hdma.setByte(0xff52, 0x00);
            hdma.setByte(0xff53, 0x00);
            hdma.setByte(0xff54, 0x00);
        }

        private void startTransfer(int control) {
            hdma.setByte(0xff55, control);
        }

        private void tick(int count) {
            for (int i = 0; i < count; i++) {
                hdma.tick();
            }
        }
    }
}
