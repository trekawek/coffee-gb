package eu.rekawek.coffeegb.core.sound;

import com.sun.management.ThreadMXBean;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.timer.Timer;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SoundOutputObserverTest {

    @Test
    public void exactObserverIsExclusiveAndDetachLeavesNoRetainedCallback() {
        Sound sound = newSound(ClockSpec.LEGACY);
        AtomicInteger samples = new AtomicInteger();
        SoundOutputObserver owner = (left, right) -> samples.incrementAndGet();
        SoundOutputObserver stranger = (left, right) -> {
            throw new AssertionError("non-owner observer invoked");
        };

        assertTrue(sound.attachOutputObserver(owner));
        assertFalse(sound.attachOutputObserver(stranger));
        tick(sound, 137);
        assertEquals(137, samples.get());

        var state = sound.captureState();
        assertFalse(sound.detachOutputObserver(stranger));
        assertTrue(sound.detachOutputObserver(owner));
        sound.restoreState(state);
        tick(sound, 71);
        assertEquals("portable state retained the transient observer", 137, samples.get());

        assertTrue(sound.attachOutputObserver(stranger));
        assertTrue(sound.detachOutputObserver(stranger));
    }

    @Test
    public void disabledObserverHotPathAllocatesNothing() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean allocationBean = (ThreadMXBean) platformBean;
        Assume.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }

        // Keep the complete measurement below the first SoundSampleEvent boundary; this isolates
        // the disabled observer's single null branch from the existing periodic frame event.
        Sound sound = newSound(new ClockSpec(200_000L, 1L, 1L));
        play(sound, 50_000);

        long threadId = Thread.currentThread().getId();
        allocationBean.getThreadAllocatedBytes(threadId);
        long minimumAllocated = Long.MAX_VALUE;
        for (int sample = 0; sample < 5; sample++) {
            long before = allocationBean.getThreadAllocatedBytes(threadId);
            play(sound, 20_000);
            long after = allocationBean.getThreadAllocatedBytes(threadId);
            minimumAllocated = Math.min(minimumAllocated, after - before);
        }

        // VM compilation bookkeeping can be attributed to an early sample. At least one warmed
        // sample must demonstrate the steady-state branch itself remains allocation-free.
        assertEquals("disabled sound output observer path allocated", 0L, minimumAllocated);
    }

    private static Sound newSound(ClockSpec clockSpec) {
        SpeedMode speedMode = new SpeedMode(true);
        Timer timer = new Timer(new InterruptManager(true), speedMode);
        return new Sound(timer, speedMode, true, clockSpec);
    }

    private static void tick(Sound sound, int ticks) {
        for (int i = 0; i < ticks; i++) {
            sound.tick();
        }
    }

    private static void play(Sound sound, int ticks) {
        for (int i = 0; i < ticks; i++) {
            sound.play(0, 0);
        }
    }
}
