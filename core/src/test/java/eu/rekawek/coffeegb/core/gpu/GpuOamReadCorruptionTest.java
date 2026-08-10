package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import java.lang.reflect.Field;

import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void directOamCorruptionStateIsResetOnlyOnDmgTicks()
            throws ReflectiveOperationException {
        Fixture dmg = new Fixture(false);
        Fixture cgb = new Fixture(true);
        for (String field : DIRECT_OAM_CORRUPTION_FIELDS) {
            setBooleanField(dmg.gpu, field, true);
            setBooleanField(cgb.gpu, field, true);
        }

        dmg.tick();
        cgb.tick();

        for (String field : DIRECT_OAM_CORRUPTION_FIELDS) {
            assertFalse(field, getBooleanField(dmg.gpu, field));
            assertTrue(field, getBooleanField(cgb.gpu, field));
        }
    }

    private static final String[] DIRECT_OAM_CORRUPTION_FIELDS = {
            "directOamReadCorruptionThisTick",
            "suppressNextDirectOamReadCorruption",
            "directOamWriteCorruptionThisTick",
            "suppressNextDirectOamWriteCorruption"
    };

    private static void setBooleanField(Gpu gpu, String name, boolean value)
            throws ReflectiveOperationException {
        Field field = Gpu.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(gpu, value);
    }

    private static boolean getBooleanField(Gpu gpu, String name)
            throws ReflectiveOperationException {
        Field field = Gpu.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(gpu);
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
                tick();
            }
        }

        private void tick() {
            gpu.tick();
            stat.tick();
        }
    }
}
