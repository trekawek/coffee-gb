package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.sgb.SgbPacketTestBuilder;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JoypadOpposingDirectionsTest {

    private static final int JOYP = 0xff00;

    @Test
    public void overlappingNetplayPressesResolveWithoutLosingTheHeldDirection() {
        try (EventBusImpl bus = new EventBusImpl(null, null, false)) {
            Joypad joypad = new Joypad(new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
            joypad.init(bus);
            joypad.setByte(JOYP, 0x20);

            bus.post(new ButtonPressEvent(Button.LEFT));
            assertEquals(0x0d, joypad.getByte(JOYP) & 0x0f);
            bus.post(new ButtonPressEvent(Button.RIGHT));
            assertEquals(0x0e, joypad.getByte(JOYP) & 0x0f);
            assertEquals(Set.of(Button.LEFT, Button.RIGHT), joypad.getLegacyPressedButtons());
            bus.post(new ButtonReleaseEvent(Button.RIGHT));
            assertEquals(0x0d, joypad.getByte(JOYP) & 0x0f);
            bus.post(new ButtonReleaseEvent(Button.LEFT));
            assertEquals(0x0f, joypad.getByte(JOYP) & 0x0f);
        }
    }

    @Test
    public void physicalAndLegacyInputsResolveTogetherBeforeTheInterruptFilter() {
        PlayerInputHub hub = new PlayerInputHub();
        hub.openSource(0).update(Set.of(Button.LEFT, Button.DOWN));
        InterruptManager interrupts = new InterruptManager(false);
        interrupts.setByte(0xff0f, 0);
        Joypad joypad = new Joypad(interrupts, EventBus.NULL_EVENT_BUS, false, hub);
        joypad.setPressedButtons(Set.of(Button.RIGHT, Button.UP));
        joypad.setByte(JOYP, 0x20);

        settle(joypad);

        assertEquals(0x0a, joypad.getByte(JOYP) & 0x0f);
        assertEquals(0x0a, joypad.captureDebugJoypadInspection(false).filteredInputLines());
        assertTrue(interrupts.isInterruptFlagSet(InterruptManager.InterruptType.P10_13));
        assertTrue(joypad.canTickPerformanceQuietSpan(3));

        joypad.setPressedButtons(Set.of());
        settle(joypad);
        assertEquals(0x05, joypad.getByte(JOYP) & 0x0f);
        assertEquals(0x05, joypad.captureDebugJoypadInspection(false).filteredInputLines());
    }

    @Test
    public void verticalOppositesResolveInPhysicalInputAndReleaseRestoresDown() {
        PlayerInputHub hub = new PlayerInputHub();
        var source = hub.openSource(0);
        source.update(Set.of(Button.UP, Button.DOWN));
        Joypad joypad = new Joypad(new InterruptManager(false), EventBus.NULL_EVENT_BUS, false, hub);
        joypad.setByte(JOYP, 0x20);
        settle(joypad);
        assertEquals(0x0b, joypad.getByte(JOYP) & 0x0f);

        source.update(Set.of(Button.DOWN));
        settle(joypad);
        assertEquals(0x07, joypad.getByte(JOYP) & 0x0f);
    }

    @Test
    public void resolvingDirectionsPreservesActionButtonsAndDiagonalsInEveryRowSelection() {
        Joypad joypad = new Joypad(new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        joypad.setPressedButtons(Set.of(Button.RIGHT, Button.LEFT, Button.B));
        assertRows(joypad, 0x0e, 0x0d, 0x0c);

        joypad.setPressedButtons(Set.of(Button.LEFT, Button.DOWN, Button.A, Button.START));
        assertRows(joypad, 0x05, 0x06, 0x04);
    }

    @Test
    public void replayCheckpointAndRollbackKeepRawInputsButResolveTheSameDirection() {
        Joypad joypad = new Joypad(new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        Set<Button> overlap = Set.of(Button.RIGHT, Button.LEFT, Button.UP, Button.DOWN);
        joypad.applyDeterministicReplayLegacyInput(overlap);
        joypad.setByte(JOYP, 0x20);
        settle(joypad);
        var checkpoint = joypad.captureState();

        joypad.applyDeterministicReplayLegacyInput(Set.of(Button.LEFT, Button.DOWN));
        settle(joypad);
        joypad.restoreState(checkpoint);
        joypad.seedDeterministicReplayInput(overlap, PlayerInputSnapshot.released());

        assertEquals(overlap, joypad.getLegacyPressedButtons());
        assertEquals(0x0a, joypad.getByte(JOYP) & 0x0f);
        assertEquals(0x0a, joypad.captureDebugJoypadInspection(false).filteredInputLines());
        assertFalse(joypad.canTickPerformanceQuietSpan(3));
        joypad.tick(); // Reconcile the restored input before a cached span can resume.
        assertTrue(joypad.tickPerformanceQuietSpan(3));
        assertEquals(0x0a, joypad.getByte(JOYP) & 0x0f);
    }

    @Test
    public void sgbResolvesEachPlayersDirectionsWithoutChangingPlayerIds() {
        PlayerInputSnapshot input = PlayerInputSnapshot.of(List.of(
                Set.of(Button.RIGHT, Button.LEFT), Set.of(Button.LEFT),
                Set.of(Button.UP, Button.DOWN), Set.of(Button.DOWN)));
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder(() -> input)) {
            Joypad joypad = fixture.joypad();
            joypad.tick();
            fixture.sendCommand(0x11, 1, 0);
            fixture.sendCommand(0x11, 1, 3);
            joypad.setPressedButtons(Set.of(Button.RIGHT));
            int[] expected = {0x0e, 0x0d, 0x0b, 0x07};
            for (int player = 0; player < 4; player++) {
                joypad.setByte(JOYP, 0x20);
                assertEquals(expected[player], joypad.getByte(JOYP) & 0x0f);
                joypad.setByte(JOYP, 0x10);
                joypad.setByte(JOYP, 0x30);
                assertEquals(0x0f - ((player + 1) % 4), joypad.getByte(JOYP) & 0x0f);
            }
        }
    }

    private static void assertRows(Joypad joypad, int directions, int actions, int both) {
        int[] selectors = {0x20, 0x10, 0x00, 0x30};
        int[] expected = {directions, actions, both, 0x0f};
        for (int i = 0; i < selectors.length; i++) {
            joypad.setByte(JOYP, selectors[i]);
            assertEquals(expected[i], joypad.getByte(JOYP) & 0x0f);
        }
    }

    private static void settle(Joypad joypad) {
        int ticks = Joypad.PLAYER_INPUT_HUB_POLL_TICKS + 4 * Joypad.JOYP_CLOCK_TICKS;
        for (int tick = 0; tick < ticks; tick++) {
            joypad.tick();
        }
    }
}
