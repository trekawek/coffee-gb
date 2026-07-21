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
    public void normalSpeedFinalMode3SlotsRemainBlocked() {
        Fixture standaloneRead = new Fixture(1);
        standaloneRead.gpu.setByte(0x8000, 0x42);
        standaloneRead.advanceTo(1, 246);
        assertEquals(0xff, standaloneRead.gpu.getByte(0x8000));

        Fixture writeThenRead = new Fixture(1);
        writeThenRead.gpu.setByte(0x8000, 0x42);
        writeThenRead.advanceTo(1, 238);
        writeThenRead.gpu.setByte(0x8000, 0x99);
        writeThenRead.advanceTo(1, 246);
        assertEquals(0xff, writeThenRead.gpu.getByte(0x8000));

        Fixture finalWrite = new Fixture(1);
        finalWrite.gpu.setByte(0x8000, 0x42);
        finalWrite.advanceTo(1, 246);
        finalWrite.gpu.setByte(0x8000, 0x99);
        finalWrite.advanceTo(1, 254);
        assertEquals(0x42, finalWrite.gpu.getByte(0x8000));
    }

    @Test
    public void fineScxMovesTheFinalSlotWithoutMovingItsXComparator() {
        Fixture finalRead = new Fixture(1);
        finalRead.gpu.setByte(0x8000, 0x42);
        finalRead.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
        finalRead.advanceTo(1, 242);
        finalRead.gpu.setByte(0x8000, 0x99);
        finalRead.advanceTo(1, 250);
        assertEquals(0x42, finalRead.gpu.getByte(0x8000));

        Fixture finalWrite = new Fixture(1);
        finalWrite.gpu.setByte(0x8000, 0x42);
        finalWrite.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
        finalWrite.advanceTo(1, 250);
        finalWrite.gpu.setByte(0x8000, 0x99);
        finalWrite.advanceTo(1, 258);
        assertEquals(0x99, finalWrite.gpu.getByte(0x8000));
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
    public void retiringHdmaCpuInstructionKeepsItsHblankReadSlotAtBothSpeeds() {
        for (int[] timing : new int[][] {{1, 250}, {2, 248}}) {
            Fixture fixture = new Fixture(timing[0]);
            fixture.gpu.setByte(0x8000, 0x42);
            fixture.advanceTo(1, timing[1]);
            assertEquals(0xff, fixture.gpu.getByte(0x8000));

            fixture.gpu.setCpuRetiringInstructionForHdma(true);
            assertEquals(0x42, fixture.gpu.getByte(0x8000));

            fixture.gpu.setCpuRetiringInstructionForHdma(false);
            assertEquals(0xff, fixture.gpu.getByte(0x8000));
        }
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
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
        fixture.advanceTo(1, 242);
        fixture.gpu.setByte(0x8000, 0x99);
        Memento<Gpu> memento = fixture.gpu.saveToMemento();

        fixture.advanceTo(1, 250);
        assertEquals(0x42, fixture.gpu.getByte(0x8000));
        fixture.advanceTo(2, 0);

        fixture.gpu.restoreFromMemento(memento);
        fixture.advanceTo(1, 250);
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
