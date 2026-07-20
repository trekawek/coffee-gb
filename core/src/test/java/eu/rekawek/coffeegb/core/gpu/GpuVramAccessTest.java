package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.memento.Memento;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static org.junit.Assert.assertEquals;

public class GpuVramAccessTest {

    @Test
    public void normalSpeedFinalReadSlotRequiresTheImmediatelyPrecedingWriteRequest() {
        Fixture standaloneRead = new Fixture(1);
        standaloneRead.gpu.setByte(0x8000, 0x42);
        standaloneRead.advanceTo(1, 246);
        assertEquals(0xff, standaloneRead.gpu.getByte(0x8000));

        Fixture writeThenRead = new Fixture(1);
        writeThenRead.gpu.setByte(0x8000, 0x42);
        writeThenRead.advanceTo(1, 238);
        writeThenRead.gpu.setByte(0x8000, 0x99);
        writeThenRead.advanceTo(1, 246);
        assertEquals(0x42, writeThenRead.gpu.getByte(0x8000));
    }

    @Test
    public void doubleSpeedHblankReadCarriesTheImmediatelyPrecedingWriteRequest() {
        Fixture standaloneRead = new Fixture(2);
        standaloneRead.gpu.setByte(0x8000, 0x42);
        standaloneRead.advanceTo(1, 248);
        assertEquals(0xff, standaloneRead.gpu.getByte(0x8000));

        Fixture writeThenRead = new Fixture(2);
        writeThenRead.gpu.setByte(0x8000, 0x42);
        writeThenRead.advanceTo(1, 244);
        writeThenRead.gpu.setByte(0x8000, 0x99);
        writeThenRead.advanceTo(1, 248);
        assertEquals(0x42, writeThenRead.gpu.getByte(0x8000));
    }

    @Test
    public void retiringHdmaCpuInstructionKeepsItsDoubleSpeedHblankReadSlot() {
        Fixture fixture = new Fixture(2);
        fixture.gpu.setByte(0x8000, 0x42);
        fixture.advanceTo(1, 248);
        assertEquals(0xff, fixture.gpu.getByte(0x8000));

        fixture.gpu.setCpuRetiringInstructionForHdma(true);
        assertEquals(0x42, fixture.gpu.getByte(0x8000));

        fixture.gpu.setCpuRetiringInstructionForHdma(false);
        assertEquals(0xff, fixture.gpu.getByte(0x8000));
    }

    @Test
    public void rephasedClockExposesTheModelSpecificFetchStartSlot() {
        Fixture normalSpeed = new Fixture(1);
        normalSpeed.gpu.setByte(0x8000, 0x42);
        normalSpeed.gpu.onSpeedSwitch();
        normalSpeed.advanceTo(1, 83);
        assertEquals(0x42, normalSpeed.gpu.getByte(0x8000));
        normalSpeed.advanceTo(1, 87);
        assertEquals(0xff, normalSpeed.gpu.getByte(0x8000));

        Fixture doubleSpeed = new Fixture(2);
        doubleSpeed.gpu.setByte(0x8000, 0x42);
        doubleSpeed.gpu.onSpeedSwitch();
        doubleSpeed.advanceTo(1, 80);
        assertEquals(0x42, doubleSpeed.gpu.getByte(0x8000));
        doubleSpeed.advanceTo(1, 82);
        assertEquals(0xff, doubleSpeed.gpu.getByte(0x8000));
    }

    @Test
    public void pendingWriteRequestIsPreservedByMemento() {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0x8000, 0x42);
        fixture.advanceTo(1, 238);
        fixture.gpu.setByte(0x8000, 0x99);
        Memento<Gpu> memento = fixture.gpu.saveToMemento();

        fixture.advanceTo(1, 246);
        assertEquals(0x42, fixture.gpu.getByte(0x8000));

        fixture.gpu.restoreFromMemento(memento);
        fixture.advanceTo(1, 246);
        assertEquals(0x42, fixture.gpu.getByte(0x8000));
    }

    private static class Fixture {

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final StatRegister stat = new StatRegister(new InterruptManager(true));

        private final Gpu gpu;

        private Fixture(int speed) {
            SpeedMode speedMode = new SpeedMode(true) {
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

        private void advanceTo(int line, int dot) {
            while (gpu.getLine() != line || gpu.getTicksInLine() != dot) {
                gpu.tick();
                stat.tick();
            }
        }
    }
}
