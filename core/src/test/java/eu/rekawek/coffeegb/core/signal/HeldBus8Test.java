package eu.rekawek.coffeegb.core.signal;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class HeldBus8Test {

    private static final int CPU = 0;

    private static final int PPU = 1;

    private static final int OAM_DMA = 2;

    private static final int VRAM_DMA = 3;

    @Test
    public void exhaustivelyResolvesEveryPerBitDriveCombinationAndHeldValue() {
        HeldBus8 bus = new HeldBus8(0);
        for (int held = 0; held <= 0xff; held++) {
            for (int drivenHigh = 0; drivenHigh <= 0xff; drivenHigh++) {
                for (int drivenLow = 0; drivenLow <= 0xff; drivenLow++) {
                    bus.restore(held);
                    bus.beginDrive();
                    bus.drive(CPU, drivenHigh, 0xff);
                    bus.drive(PPU, drivenLow, 0x00);
                    bus.resolve();

                    int driven = drivenHigh | drivenLow;
                    int expected = (held & ~driven) | (drivenHigh & ~drivenLow);
                    assertEquals(expected & 0xff, bus.sample());
                    assertEquals(held, bus.held());
                    assertEquals(drivenHigh & drivenLow, bus.contentionMask());
                    assertEquals(drivenHigh & drivenLow, bus.ownershipContentionMask());
                    assertEquals(driven, bus.drivenMask());
                    assertEquals(~driven & 0xff, bus.floatingHeldMask());
                }
            }
        }
    }

    @Test
    public void fullWidthDriverResolutionIsExhaustivelyOrderIndependent() {
        HeldBus8 first = new HeldBus8(0xa5);
        HeldBus8 second = new HeldBus8(0xa5);
        for (int a = 0; a <= 0xff; a++) {
            for (int b = 0; b <= 0xff; b++) {
                first.restore(0xa5);
                first.beginDrive();
                first.drive(CPU, a);
                first.drive(PPU, b);
                first.resolve();

                second.restore(0xa5);
                second.beginDrive();
                second.drive(PPU, b);
                second.drive(CPU, a);
                second.resolve();

                assertEquals(a & b, first.sample());
                assertEquals(first.sample(), second.sample());
                assertEquals(a ^ b, first.contentionMask());
                assertEquals(0xff, first.ownershipContentionMask());
                assertEquals(first.contentionMask(), second.contentionMask());
                assertEquals(first.activeOwnerMask(), second.activeOwnerMask());
                assertEquals(first.contendingOwnerMask(), second.contendingOwnerMask());
            }
        }
    }

    @Test
    public void allRequesterPermutationsProduceTheSameResolvedFabricReport() {
        Drive[] drives = {
                new Drive(CPU, 0xf3, 0xa1),
                new Drive(PPU, 0x3f, 0x16),
                new Drive(OAM_DMA, 0xcc, 0xc4),
                new Drive(VRAM_DMA, 0x55, 0x11)
        };
        int[] order = {0, 1, 2, 3};
        long[] expected = null;
        do {
            HeldBus8 bus = new HeldBus8(0x5a);
            bus.beginDrive();
            for (int index : order) {
                Drive drive = drives[index];
                bus.drive(drive.owner, drive.mask, drive.value);
            }
            bus.resolve();

            long[] report = report(bus);
            if (expected == null) {
                expected = report;
            } else {
                assertArrayEquals(expected, report);
            }
        } while (nextPermutation(order));
    }

    @Test
    public void ownerAndContentionReportsDistinguishSharedLinesFromElectricalConflict() {
        HeldBus8 bus = new HeldBus8(0xa5);
        bus.beginDrive();
        bus.drive(CPU, 0x0f, 0x05);
        bus.drive(PPU, 0x33, 0x21);
        bus.drive(OAM_DMA, 0xc0, 0x80);
        bus.drive(VRAM_DMA, 0x00, 0xff);
        bus.resolve();

        assertEquals(0xa5, bus.sample());
        assertEquals(0xff, bus.drivenMask());
        assertEquals(0x00, bus.contentionMask());
        assertEquals(0x03, bus.ownershipContentionMask());
        assertEquals(0x00, bus.floatingHeldMask());
        assertEquals(bits(CPU, PPU, OAM_DMA), bus.activeOwnerMask());
        assertEquals(bits(CPU, PPU), bus.contendingOwnerMask());
        assertEquals(bits(CPU, PPU), bus.ownerMask(0));
        assertEquals(bits(CPU), bus.ownerMask(2));
        assertEquals(bits(OAM_DMA), bus.ownerMask(7));
    }

    @Test
    public void repeatedContributionsFromOneOwnerCanConflictWithoutInventingAnotherOwner() {
        HeldBus8 bus = new HeldBus8(0xff);
        bus.beginDrive();
        bus.drive(CPU, 0x0f, 0x0f);
        bus.drive(CPU, 0x03, 0x00);
        bus.resolve();

        assertEquals(0xfc, bus.sample());
        assertEquals(0x03, bus.contentionMask());
        assertEquals(0x00, bus.ownershipContentionMask());
        assertEquals(bits(CPU), bus.activeOwnerMask());
        assertEquals(0, bus.contendingOwnerMask());
    }

    @Test
    public void floatingLinesRetainOnlyTheLastCommittedResolution() {
        HeldBus8 bus = new HeldBus8(0xa5);
        bus.beginDrive();
        bus.drive(CPU, 0x0f, 0x03);
        bus.resolve();

        assertEquals(0xa3, bus.sample());
        assertEquals(0xa5, bus.held());
        assertEquals(0xf0, bus.floatingHeldMask());
        bus.commit();
        assertEquals(0xa3, bus.held());

        bus.beginDrive();
        bus.resolve();
        assertEquals(0xa3, bus.sample());
        assertEquals(0xff, bus.floatingHeldMask());
    }

    @Test
    public void restoreDiscardsResolvedButUncommittedValueAndOwnership() {
        HeldBus8 bus = new HeldBus8(0x11);
        bus.beginDrive();
        bus.drive(63, 0xee);
        bus.resolve();
        assertEquals(0xee, bus.sample());

        bus.restore(0x42);
        assertEquals(0x42, bus.held());
        bus.beginDrive();
        bus.resolve();
        assertEquals(0x42, bus.sample());
        assertEquals(0, bus.activeOwnerMask());
        assertEquals(0, bus.contendingOwnerMask());
        for (int bit = 0; bit < Byte.SIZE; bit++) {
            assertEquals(0, bus.ownerMask(bit));
        }
    }

    @Test
    public void enforcesDriveResolveSampleCommitPhases() {
        HeldBus8 bus = new HeldBus8(0);
        assertThrows(IllegalStateException.class, () -> bus.drive(CPU, 0));
        assertThrows(IllegalStateException.class, bus::resolve);
        assertThrows(IllegalStateException.class, bus::sample);
        assertThrows(IllegalStateException.class, bus::commit);

        bus.beginDrive();
        assertThrows(IllegalStateException.class, bus::beginDrive);
        assertThrows(IllegalStateException.class, bus::sample);
        assertThrows(IllegalStateException.class, bus::commit);
        bus.resolve();
        assertThrows(IllegalStateException.class, () -> bus.drive(CPU, 0));
        assertThrows(IllegalStateException.class, bus::resolve);
        assertEquals(0, bus.sample());
        bus.commit();
        assertThrows(IllegalStateException.class, bus::sample);
    }

    @Test
    public void rejectsInvalidOwnerBitsAndBytes() {
        assertThrows(IllegalArgumentException.class, () -> new HeldBus8(-1));
        assertThrows(IllegalArgumentException.class, () -> new HeldBus8(0x100));

        HeldBus8 bus = new HeldBus8(0);
        bus.beginDrive();
        assertThrows(IllegalArgumentException.class, () -> bus.drive(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> bus.drive(64, 0));
        assertThrows(IllegalArgumentException.class, () -> bus.drive(CPU, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> bus.drive(CPU, 0, 0x100));
        bus.resolve();
        assertThrows(IllegalArgumentException.class, () -> bus.ownerMask(-1));
        assertThrows(IllegalArgumentException.class, () -> bus.ownerMask(8));
        assertThrows(IllegalArgumentException.class, () -> bus.restore(-1));
    }

    private static long[] report(HeldBus8 bus) {
        long[] result = new long[8 + 7];
        int index = 0;
        result[index++] = bus.sample();
        result[index++] = bus.drivenMask();
        result[index++] = bus.contentionMask();
        result[index++] = bus.ownershipContentionMask();
        result[index++] = bus.floatingHeldMask();
        result[index++] = bus.activeOwnerMask();
        result[index++] = bus.contendingOwnerMask();
        for (int bit = 0; bit < Byte.SIZE; bit++) {
            result[index++] = bus.ownerMask(bit);
        }
        return result;
    }

    private static boolean nextPermutation(int[] values) {
        int pivot = values.length - 2;
        while (pivot >= 0 && values[pivot] >= values[pivot + 1]) {
            pivot--;
        }
        if (pivot < 0) {
            return false;
        }
        int successor = values.length - 1;
        while (values[successor] <= values[pivot]) {
            successor--;
        }
        int tmp = values[pivot];
        values[pivot] = values[successor];
        values[successor] = tmp;
        for (int left = pivot + 1, right = values.length - 1; left < right; left++, right--) {
            tmp = values[left];
            values[left] = values[right];
            values[right] = tmp;
        }
        return true;
    }

    private static long bits(int... owners) {
        return Arrays.stream(owners).mapToLong(owner -> 1L << owner).reduce(0, (a, b) -> a | b);
    }

    private record Drive(int owner, int mask, int value) {
    }
}
