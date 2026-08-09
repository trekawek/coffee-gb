package eu.rekawek.coffeegb.core.joypad;

import com.sun.management.ThreadMXBean;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.sgb.Commands;
import eu.rekawek.coffeegb.core.sgb.SgbPacketTestBuilder;
import eu.rekawek.coffeegb.core.state.ComponentState;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void releasedDefaultSourceStillReconcilesSeededPhysicalInput() {
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        PlayerInputSnapshot pressed = PlayerInputSnapshot.of(List.of(
                Set.of(Button.A), Set.of(), Set.of(), Set.of()));
        joypad.seedDeterministicReplayInput(Set.of(), pressed);
        assertFalse(releasedInputFastPathEligible(joypad));

        joypad.tick();

        assertEquals(PlayerInputSnapshot.RELEASED, joypad.getSampledInput());
        assertTrue(releasedInputFastPathEligible(joypad));
    }

    @Test
    public void fullySettledReleasedDefaultInputPreservesOutputAndAdvancesTick() {
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        ComponentState<Joypad> before = joypad.captureState();
        int output = joypad.getByte(JOYP);
        assertTrue(releasedInputFastPathEligible(joypad));

        joypad.tick();

        ComponentState<Joypad> after = joypad.captureState();
        assertEquals(output, joypad.getByte(JOYP));
        assertEquals(tick(before) + 1, tick(after));
        assertEquals(inputHistory(before), inputHistory(after));
        assertEquals(filteredInputLines(before), filteredInputLines(after));
        assertFalse(inputChangedSinceLastTick(after));
        assertTrue(releasedInputFastPathEligible(joypad));
    }

    @Test
    public void customReleasedSourceIsSampledEveryTick() {
        AtomicInteger samples = new AtomicInteger();
        PlayerInputSource source = () -> {
            samples.incrementAndGet();
            return PlayerInputSnapshot.RELEASED;
        };
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false, source);
        assertFalse(releasedInputFastPathEligible(joypad));

        joypad.tick();
        joypad.tick();

        assertEquals(2, samples.get());
    }

    @Test
    public void pendingInputChangeStillClearsOnItsFollowingTick() {
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        assertTrue(releasedInputFastPathEligible(joypad));
        joypad.setByte(JOYP, 0x30);
        assertFalse(releasedInputFastPathEligible(joypad));

        joypad.tick();

        assertFalse(inputChangedSinceLastTick(joypad.captureState()));
        assertTrue(releasedInputFastPathEligible(joypad));
    }

    @Test
    public void legacyButtonsStillAdvanceTheInputFilter() {
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        joypad.seedDeterministicReplayInput(Set.of(Button.A), PlayerInputSnapshot.RELEASED);
        assertFalse(releasedInputFastPathEligible(joypad));

        joypad.tick();

        assertEquals(1, inputHistory(joypad.captureState()));
    }

    @Test
    public void multiplayerPlayerIdStillAdvancesTheInputFilter() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            Joypad joypad = fixture.joypad();
            assertTrue(releasedInputFastPathEligible(joypad));
            fixture.sendCommand(0x11, 1, 1);
            assertFalse(releasedInputFastPathEligible(joypad));
            joypad.setByte(JOYP, 0x10);
            joypad.setByte(JOYP, 0x30);
            joypad.tick(); // consume the selector transition

            joypad.tick();

            assertEquals(1, inputHistory(joypad.captureState()));
        }
    }

    @Test
    public void multiplayerControlCallbackInvalidatesReleasedFastPathEligibility() {
        try (EventBusImpl sgbBus = new EventBusImpl(null, null, false)) {
            Joypad joypad = new Joypad(new InterruptManager(false), sgbBus, true);
            assertTrue(releasedInputFastPathEligible(joypad));

            int[] packet = new int[Commands.PACKET_SIZE];
            packet[0] = 0x11 << 3 | 1;
            packet[1] = 1;
            sgbBus.post((Commands.MltReqCmd) Commands.toCommand(packet));

            assertFalse(releasedInputFastPathEligible(joypad));
            assertEquals(Joypad.SgbMultiplayerMode.TWO_PLAYER,
                    joypad.getSgbMultiplayerStatus().mode());
        }
    }

    @Test
    public void restoredPartiallySettledReleasedFilterStillConverges() {
        Joypad original = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        original.setPressedButtons(Set.of(Button.A));
        original.tick(); // consume the input transition
        original.tick(); // first pressed sample
        ComponentState<Joypad> partialFilter = original.captureState();
        assertEquals(1, inputHistory(partialFilter));

        Joypad restored = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        restored.restoreState(partialFilter);
        assertFalse(releasedInputFastPathEligible(restored));
        restored.tick();

        assertEquals(2, inputHistory(restored.captureState()));
    }

    @Test
    public void legacyAndReplayMutationsInvalidateReleasedFastPathEligibility() {
        try (EventBusImpl eventBus = new EventBusImpl(null, null, false)) {
            Joypad joypad = new Joypad(
                    new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
            joypad.init(eventBus);
            assertTrue(releasedInputFastPathEligible(joypad));

            eventBus.post(new ButtonPressEvent(Button.A));
            assertFalse(releasedInputFastPathEligible(joypad));
            eventBus.post(new ButtonReleaseEvent(Button.A));
            assertFalse(releasedInputFastPathEligible(joypad));
        }

        Joypad replaced = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        replaced.setPressedButtons(Set.of(Button.A));
        assertFalse(releasedInputFastPathEligible(replaced));

        Joypad replay = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        replay.applyDeterministicReplayLegacyInput(Set.of(Button.A));
        assertFalse(releasedInputFastPathEligible(replay));

        Joypad seeded = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        seeded.seedDeterministicReplayInput(Set.of(), PlayerInputSnapshot.RELEASED);
        assertTrue(releasedInputFastPathEligible(seeded));
        seeded.seedDeterministicReplayInput(Set.of(Button.A), PlayerInputSnapshot.RELEASED);
        assertFalse(releasedInputFastPathEligible(seeded));
    }

    @Test
    public void restoringFullySettledReleasedStateRecomputesFastPathEligibility() {
        Joypad source = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        ComponentState<Joypad> releasedState = source.captureState();

        Joypad restored = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        restored.setByte(JOYP, 0x30);
        assertFalse(releasedInputFastPathEligible(restored));

        restored.restoreState(releasedState);

        assertTrue(releasedInputFastPathEligible(restored));
    }

    @Test
    public void stableReleasedDefaultInputFastPathAllocatesNothing() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean allocationBean = (ThreadMXBean) platformBean;
        Assume.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }

        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
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
        assertEquals("stable released joypad path allocated", 0L, minimumAllocated);
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

    private static long tick(ComponentState<Joypad> state) {
        return (long) stateField(state, "tick");
    }

    private static int inputHistory(ComponentState<Joypad> state) {
        return (int) stateField(state, "inputHistory");
    }

    private static int filteredInputLines(ComponentState<Joypad> state) {
        return (int) stateField(state, "filteredInputLines");
    }

    private static boolean inputChangedSinceLastTick(ComponentState<Joypad> state) {
        return (boolean) stateField(state, "inputChangedSinceLastTick");
    }

    private static boolean releasedInputFastPathEligible(Joypad joypad) {
        try {
            var field = Joypad.class.getDeclaredField("releasedInputFastPathEligible");
            field.setAccessible(true);
            return field.getBoolean(joypad);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Joypad has no released-input fast-path state", e);
        }
    }

    private static Object stateField(ComponentState<Joypad> state, String name) {
        try {
            var accessor = state.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(state);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Joypad checkpoint has no " + name + " state", e);
        }
    }
}
