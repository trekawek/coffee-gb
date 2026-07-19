package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Mode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HdmaTest {

    @Test
    public void generalPurposeDmaIncludesStartupTicks() {
        Fixture fixture = new Fixture();
        fixture.startTransfer(0x00);

        fixture.tick(37);
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

        fixture.tick(38);
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
        fixture.tick(35);
        assertEquals(0, fixture.memory.getByte(0x8000));

        fixture.tick(1);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0, fixture.memory.getByte(0x8010));

        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.tick(35);
        assertEquals(0, fixture.memory.getByte(0x8010));

        fixture.tick(1);
        assertEquals(0xb0, fixture.memory.getByte(0x8010));
        assertEquals(0xbf, fixture.memory.getByte(0x801f));
    }

    @Test
    public void doubleSpeedHblankDmaUsesThreeSchedulerStartupTicks() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);

        fixture.tick(34);
        assertEquals(0, fixture.memory.getByte(0x8000));

        fixture.tick(1);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0xaf, fixture.memory.getByte(0x800f));
    }

    @Test
    public void switchingLcdOffReleasesOnePendingHblankBurst() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x81);

        fixture.hdma.onLcdSwitch(false);
        fixture.tick(36);

        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0xaf, fixture.memory.getByte(0x800f));
        assertEquals(0, fixture.memory.getByte(0x8010));
        assertEquals(0x00, fixture.hdma.getByte(0xff55));
    }

    @Test
    public void highSourceRangeAliasesCartridgeRam() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 0x10; i++) {
            fixture.memory.setByte(0xa000 + i, 0x60 + i);
        }
        fixture.hdma.setByte(0xff51, 0xe0);
        fixture.startTransfer(0x00);

        fixture.tick(38);

        assertEquals(0x60, fixture.memory.getByte(0x8000));
        assertEquals(0x6f, fixture.memory.getByte(0x800f));
    }

    @Test
    public void vramSourceRangeStartsWithCpuBusResidue() {
        Fixture fixture = new Fixture();
        fixture.hdma.setByte(0xff51, 0x80);
        fixture.hdma.setCpuBusValue(0xa5);
        fixture.startTransfer(0x00);

        fixture.tick(38);

        assertEquals(0xa5, fixture.memory.getByte(0x8000));
        assertEquals(0xa5, fixture.memory.getByte(0x8001));
        assertEquals(0xff, fixture.memory.getByte(0x8002));
        assertEquals(0xff, fixture.memory.getByte(0x800f));
    }

    private static class Fixture {

        private final Ram memory = new Ram(0, 0x10000);

        private final Hdma hdma;

        private Fixture() {
            this(1);
        }

        private Fixture(int speed) {
            SpeedMode speedMode = new SpeedMode(true) {
                @Override
                public int getSpeedMode() {
                    return speed;
                }
            };
            hdma = new Hdma(memory, speedMode);
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
