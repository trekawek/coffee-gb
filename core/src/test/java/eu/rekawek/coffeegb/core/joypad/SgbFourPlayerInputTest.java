package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.sgb.SgbPacketTestBuilder;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SgbFourPlayerInputTest {

    private static final int JOYP = 0xff00;

    @Test
    public void freshSessionAndModeResetSelectReleasedPrimaryPlayer() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            Joypad joypad = fixture.joypad();
            assertEquals(Joypad.SgbMultiplayerMode.ONE_PLAYER,
                    joypad.getSgbMultiplayerStatus().mode());
            assertEquals(0, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertPlayer(joypad, 0x0f);

            fixture.sendCommand(0x11, 1, 3);
            selectNext(joypad);
            fixture.sendCommand(0x11, 1, 0);
            assertEquals(Joypad.SgbMultiplayerMode.ONE_PLAYER,
                    joypad.getSgbMultiplayerStatus().mode());
            assertEquals(0, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertPlayer(joypad, 0x0f);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new Joypad.SgbMultiplayerStatus(
                        Joypad.SgbMultiplayerMode.ONE_PLAYER, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Joypad.SgbMultiplayerStatus(
                        Joypad.SgbMultiplayerMode.CONTROL_2_COMPATIBILITY, 1));
    }

    @Test
    public void fourDistinctPlayersRotateWithExactIdFeedbackAndWrap() {
        AtomicReference<PlayerInputSnapshot> input = new AtomicReference<>(PlayerInputSnapshot.of(
                List.of(Set.of(Button.A), Set.of(Button.B),
                        Set.of(Button.SELECT), Set.of(Button.START))));
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder(input::get)) {
            Joypad joypad = fixture.joypad();
            joypad.tick();
            fixture.sendCommand(0x11, 1, 0); // force P1 before expanding the mask
            fixture.sendCommand(0x11, 1, 3);

            int[] expectedLines = {0x0e, 0x0d, 0x0b, 0x07, 0x0e};
            int[] expectedIds = {0x0f, 0x0e, 0x0d, 0x0c, 0x0f};
            for (int index = 0; index < expectedLines.length; index++) {
                joypad.setByte(JOYP, 0x10);
                assertEquals(expectedLines[index], joypad.getByte(JOYP) & 0x0f);
                if (index + 1 < expectedLines.length) {
                    joypad.setByte(JOYP, 0x30);
                    assertEquals(expectedIds[index + 1], joypad.getByte(JOYP) & 0x0f);
                }
            }
            assertEquals(Joypad.SgbMultiplayerMode.FOUR_PLAYER,
                    joypad.getSgbMultiplayerStatus().mode());
            assertEquals(0, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertEquals(4, joypad.getSgbMultiplayerStatus().playerCount());
        }
    }

    @Test
    public void oneTwoAndFourPlayerTransitionsMaskSelectionAndNeverBleed() {
        PlayerInputHub hub = new PlayerInputHub();
        var p1 = hub.openSource(0);
        var p2 = hub.openSource(1);
        var p4 = hub.openSource(3);
        p1.update(Set.of(Button.A));
        p2.update(Set.of(Button.B));
        p4.update(Set.of(Button.START));

        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder(hub)) {
            Joypad joypad = fixture.joypad();
            joypad.tick();
            assertEquals(Set.of(Button.A), joypad.getPressedButtons());
            fixture.sendCommand(0x11, 1, 1);
            assertPlayer(joypad, 0x0e);
            selectNext(joypad);
            assertPlayer(joypad, 0x0d);
            selectNext(joypad);
            assertPlayer(joypad, 0x0e);

            fixture.sendCommand(0x11, 1, 0);
            fixture.sendCommand(0x11, 1, 3);
            selectNext(joypad); // P2
            selectNext(joypad); // P3, unassigned
            assertPlayer(joypad, 0x0f);
            selectNext(joypad); // P4
            assertPlayer(joypad, 0x07);

            fixture.sendCommand(0x11, 1, 1); // 3 & 1 => P2
            assertEquals(1, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertPlayer(joypad, 0x0d);
            fixture.sendCommand(0x11, 1, 0);
            assertEquals(0, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertPlayer(joypad, 0x0e);
        }
    }

    @Test
    public void inputIsLatchedOnlyOnJoypadTicksAndRapidReadsDoNotResample() {
        AtomicInteger samples = new AtomicInteger();
        AtomicReference<PlayerInputSnapshot> next =
                new AtomicReference<>(PlayerInputSnapshot.released());
        PlayerInputSource source = () -> {
            samples.incrementAndGet();
            return next.get();
        };
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder(source)) {
            Joypad joypad = fixture.joypad();
            joypad.setByte(JOYP, 0x10);
            assertEquals(0x0f, joypad.getByte(JOYP) & 0x0f);
            next.set(PlayerInputSnapshot.of(
                    List.of(Set.of(Button.A), Set.of(), Set.of(), Set.of())));
            assertEquals(0x0f, joypad.getByte(JOYP) & 0x0f);
            assertEquals(0, samples.get());

            joypad.tick();
            assertEquals(0x0e, joypad.getByte(JOYP) & 0x0f);
            assertEquals(0x0e, joypad.getByte(JOYP) & 0x0f);
            assertEquals(1, samples.get());
            joypad.tick();
            assertEquals(2, samples.get());
        }
    }

    @Test
    public void atomicPhysicalP1LatchCoexistsWithUnchangedLegacyEventApi() {
        PlayerInputHub hub = new PlayerInputHub();
        var physical = hub.openSource(0);
        EventBusImpl machineBus = new EventBusImpl(null, null, false);
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        Joypad joypad = new Joypad(new InterruptManager(false), sgbBus, false, hub);
        joypad.init(machineBus);

        physical.update(Set.of(Button.A));
        assertTrue("physical updates wait for the Joypad tick", joypad.getPressedButtons().isEmpty());
        joypad.tick();
        assertEquals(Set.of(Button.A), joypad.getPressedButtons());

        physical.update(Set.of(Button.B));
        assertEquals("the old latch remains intact before the next tick",
                Set.of(Button.A), joypad.getPressedButtons());
        joypad.tick();
        assertEquals("A to B is one atomic sample, never an A+B transient",
                Set.of(Button.B), joypad.getPressedButtons());

        machineBus.post(new ButtonPressEvent(Button.START));
        assertEquals(Set.of(Button.B, Button.START), joypad.getPressedButtons());
        assertEquals(Set.of(Button.START), joypad.getLegacyPressedButtons());
        machineBus.post(new ButtonReleaseEvent(Button.START));
        assertEquals(Set.of(Button.B), joypad.getPressedButtons());
        assertTrue(joypad.getLegacyPressedButtons().isEmpty());

        machineBus.close();
        sgbBus.close();
    }

    @Test
    public void repeatedSelectorLevelsRotateOnlyOnTheEstablishedEdge() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            Joypad joypad = fixture.joypad();
            fixture.sendCommand(0x11, 1, 3);

            selectNext(joypad);
            assertEquals(1, joypad.getSgbMultiplayerStatus().selectedPlayer());
            joypad.setByte(JOYP, 0x30);
            joypad.setByte(JOYP, 0x30);
            assertEquals(1, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertEquals(0x0e, joypad.getByte(JOYP) & 0x0f);

            joypad.setByte(JOYP, 0x10);
            joypad.setByte(JOYP, 0x10);
            assertEquals(1, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertEquals(0x0f, joypad.getByte(JOYP) & 0x0f);
            joypad.setByte(JOYP, 0x30);
            assertEquals(2, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertEquals(0x0d, joypad.getByte(JOYP) & 0x0f);
            assertTrue(joypad.getPressedButtons().isEmpty());
        }
    }

    @Test
    public void machineRestoreRestoresMultiplexButKeepsCurrentPhysicalInput() {
        PlayerInputHub hub = new PlayerInputHub();
        var playerTwo = hub.openSource(1);
        playerTwo.update(Set.of(Button.B));
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder(hub)) {
            Joypad joypad = fixture.joypad();
            joypad.tick();
            fixture.sendCommand(0x11, 1, 1);
            selectNext(joypad);
            var state = joypad.captureState();

            playerTwo.update(Set.of(Button.START));
            joypad.tick();
            fixture.sendCommand(0x11, 1, 0);
            joypad.restoreState(state);

            assertEquals(1, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertPlayer(joypad, 0x07);
        }
    }

    @Test
    public void specialControlTwoRetainsEstablishedSelectionBehavior() {
        PlayerInputHub hub = new PlayerInputHub();
        hub.openSource(0).update(Set.of(Button.A));
        hub.openSource(2).update(Set.of(Button.SELECT));
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder(hub)) {
            Joypad joypad = fixture.joypad();
            joypad.tick();
            fixture.sendCommand(0x11, 1, 3);
            selectNext(joypad); // P2
            fixture.sendCommand(0x11, 1, 2);
            assertEquals(Joypad.SgbMultiplayerMode.CONTROL_2_COMPATIBILITY,
                    joypad.getSgbMultiplayerStatus().mode());
            assertEquals(2, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertPlayer(joypad, 0x0b);
            selectNext(joypad);
            assertEquals(2, joypad.getSgbMultiplayerStatus().selectedPlayer());

            fixture.sendCommand(0x11, 1, 3);
            selectNext(joypad); // P4
            fixture.sendCommand(0x11, 1, 2);
            assertEquals(0, joypad.getSgbMultiplayerStatus().selectedPlayer());
            assertPlayer(joypad, 0x0e);
            assertEquals(Set.of(Button.A), joypad.getPressedButtons());
        }
    }

    private static void selectNext(Joypad joypad) {
        joypad.setByte(JOYP, 0x10);
        joypad.setByte(JOYP, 0x30);
    }

    private static void assertPlayer(Joypad joypad, int expectedLines) {
        joypad.setByte(JOYP, 0x10);
        assertEquals(expectedLines, joypad.getByte(JOYP) & 0x0f);
    }
}
