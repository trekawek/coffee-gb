package eu.rekawek.coffeegb.core.joypad;

import com.sun.management.ThreadMXBean;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JoypadHotPathTest {

    private static final int JOYP = 0xff00;

    private static final AtomicReference<PlayerInputSnapshot> ALLOCATION_INPUT =
            new AtomicReference<>();

    @Test
    public void everyPhysicalButtonMaskMatchesSelectorElectricalLevels() {
        AtomicReference<PlayerInputSnapshot> input =
                new AtomicReference<>(PlayerInputSnapshot.released());
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false, input::get);
        int[] selectors = {0x00, 0x10, 0x20, 0x30};

        for (int mask = 0; mask <= JoypadButtonMask.ALL; mask++) {
            Set<Button> buttons = JoypadButtonMask.toButtons(mask);
            input.set(PlayerInputSnapshot.of(List.of(buttons, Set.of(), Set.of(), Set.of())));
            joypad.tick();
            for (int selector : selectors) {
                joypad.setByte(JOYP, selector);
                assertEquals(
                        "mask=" + mask + ", selector=" + selector,
                        expectedInputLines(selector, buttons),
                        joypad.getByte(JOYP) & 0x0f);
            }
        }
    }

    @Test
    public void stablePhysicalInputHotPathAllocatesNothing() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean allocationBean = (ThreadMXBean) platformBean;
        Assume.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }

        PlayerInputSnapshot snapshot = PlayerInputSnapshot.of(List.of(
                Set.of(Button.RIGHT, Button.DOWN, Button.A, Button.START),
                Set.of(), Set.of(), Set.of()));
        ALLOCATION_INPUT.set(snapshot);
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false,
                ALLOCATION_INPUT::get);
        joypad.setByte(JOYP, 0x00);
        exercise(joypad, 100_000);

        long threadId = Thread.currentThread().getId();
        allocationBean.getThreadAllocatedBytes(threadId);
        long minimumAllocated = Long.MAX_VALUE;
        long checksum = 0;
        for (int sample = 0; sample < 5; sample++) {
            long before = allocationBean.getThreadAllocatedBytes(threadId);
            checksum += exercise(joypad, 20_000);
            long after = allocationBean.getThreadAllocatedBytes(threadId);
            minimumAllocated = Math.min(minimumAllocated, after - before);
        }

        assertTrue(checksum != 0);
        assertEquals("stable physical joypad path allocated", 0L, minimumAllocated);
    }

    @Test
    public void adoptsAnEqualReplacementSnapshotAfterOneComparison() {
        PlayerInputSnapshot replacement = PlayerInputSnapshot.of(List.of(
                Set.of(), Set.of(), Set.of(), Set.of()));
        AtomicReference<PlayerInputSnapshot> input = new AtomicReference<>(replacement);
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false, input::get);

        joypad.tick();

        assertSame(replacement, joypad.getSampledInput());
    }

    private static int expectedInputLines(int selector, Set<Button> buttons) {
        int inputLines = 0x0f;
        for (Button button : buttons) {
            if ((button.getLine() & selector) == 0) {
                inputLines &= ~button.getMask();
            }
        }
        return inputLines & 0x0f;
    }

    private static long exercise(Joypad joypad, int iterations) {
        long checksum = 0;
        for (int i = 0; i < iterations; i++) {
            joypad.tick();
            checksum += joypad.getByte(JOYP);
        }
        return checksum;
    }
}
