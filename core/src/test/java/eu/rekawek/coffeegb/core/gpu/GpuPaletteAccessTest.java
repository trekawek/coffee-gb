package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static org.junit.Assert.assertEquals;

public class GpuPaletteAccessTest {

    @Test
    public void cgbPaletteReleaseUsesNormalSpeedCpuBusTimeline() {
        assertPaletteReleaseDelay(1, 8);
    }

    @Test
    public void cgbPaletteReleaseUsesDoubleSpeedCpuBusTimeline() {
        assertPaletteReleaseDelay(2, 6);
    }

    @Test
    public void bootPhasePaletteLatchClosesWithMode3Skeleton() {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff68, 0x00);
        fixture.advanceTo(1, 78);
        fixture.gpu.setByte(0xff69, 0x01);
        fixture.advanceTo(1, 82);
        fixture.gpu.setByte(0xff69, 0x02);
        fixture.advanceTo(1, 270);

        assertEquals(0x01, fixture.gpu.getByte(0xff69));
    }

    @Test
    public void rephasedNormalSpeedPaletteUsesFetchStartSlot() {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff68, 0x00);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 80);
        fixture.gpu.setByte(0xff69, 0x01);
        fixture.advanceTo(1, 84);
        fixture.gpu.setByte(0xff69, 0x02);
        fixture.advanceTo(1, 270);

        assertEquals(0x01, fixture.gpu.getByte(0xff69));
    }

    @Test
    public void rephasedDoubleSpeedPaletteUsesAlternatingStartSlots() {
        Fixture fixture = new Fixture(2);
        fixture.gpu.setByte(0xff68, 0x00);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 80);
        fixture.gpu.setByte(0xff69, 0x01);
        fixture.advanceTo(1, 82);
        fixture.gpu.setByte(0xff69, 0x02);
        fixture.advanceTo(1, 84);
        fixture.gpu.setByte(0xff69, 0x03);
        fixture.advanceTo(1, 270);

        assertEquals(0x02, fixture.gpu.getByte(0xff69));
    }

    private static void assertPaletteReleaseDelay(int speed, int blockedDots) {
        Fixture fixture = new Fixture(speed);
        fixture.gpu.setByte(0xff68, 0x00);
        fixture.gpu.setByte(0xff69, 0x42);
        fixture.advanceTo(1, 0);
        fixture.advanceToInternalHBlank();
        fixture.advanceToMode0InterruptEdge();

        for (int dot = 0; dot < blockedDots; dot++) {
            assertEquals("palette opened at line dot " + fixture.gpu.getTicksInLine(),
                    0xff, fixture.gpu.getByte(0xff69));
            fixture.gpu.setByte(0xff69, 0x99);
            fixture.tick();
        }

        assertEquals(0x42, fixture.gpu.getByte(0xff69));
    }

    private static class Fixture {

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final StatRegister stat = new StatRegister(new InterruptManager(true));

        private final SpeedMode speedMode;

        private final Gpu gpu;

        private Fixture(int speed) {
            speedMode = new SpeedMode(true) {
                @Override
                public int getSpeedMode() {
                    return speed;
                }
            };
            gpu = new Gpu(
                    new Display(true),
                    new Dma(new Ram(0, 0x10000), oam, speedMode),
                    oam,
                    new VRamTransfer(NULL_EVENT_BUS),
                    stat,
                    true,
                    speedMode);
            stat.init(gpu);
        }

        private void advanceToInternalHBlank() {
            Mode previous;
            do {
                previous = gpu.getMode();
                tick();
            } while (previous != Mode.PixelTransfer || gpu.getMode() != Mode.HBlank);
        }

        private void advanceToMode0InterruptEdge() {
            while (!gpu.isMode0IntWindow()) {
                tick();
            }
        }

        private void advanceTo(int line, int dot) {
            while (gpu.getLine() != line || gpu.getTicksInLine() != dot) {
                tick();
            }
        }

        private void tick() {
            gpu.tick();
            stat.tick();
        }
    }
}
