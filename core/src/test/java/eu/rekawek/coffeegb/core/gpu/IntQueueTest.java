package eu.rekawek.coffeegb.core.gpu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IntQueueTest {

    @Test
    public void packedGroupWrapsWithoutChangingLogicalOrder() {
        IntQueue queue = new IntQueue(16);
        for (int i = 0; i < 12; i++) {
            queue.enqueue(0x40 + i);
        }
        for (int i = 0; i < 4; i++) {
            queue.dequeue();
        }

        queue.enqueue8Packed(new int[] {0, 1, 2, 3, 0, 1, 2, 3}, 0x34);

        assertEquals(16, queue.size());
        for (int i = 0; i < 8; i++) {
            assertEquals(0x44 + i, queue.get(i));
        }
        for (int i = 0; i < 8; i++) {
            assertEquals(0x34 | (i & 3), queue.get(8 + i));
        }
        assertEquals(0x34, queue.array[12]);
        assertEquals(0x37, queue.array[15]);
        assertEquals(0x34, queue.array[0]);
        assertEquals(0x37, queue.array[3]);
    }
}
