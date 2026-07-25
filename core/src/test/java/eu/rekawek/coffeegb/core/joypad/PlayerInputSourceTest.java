package eu.rekawek.coffeegb.core.joypad;

import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PlayerInputSourceTest {

    @Test
    public void snapshotOwnsExactlyFourImmutableSlots() {
        EnumSet<Button> mutable = EnumSet.of(Button.A);
        PlayerInputSnapshot snapshot = PlayerInputSnapshot.of(
                List.of(mutable, Set.of(Button.B), Set.of(), Set.of(Button.START)));
        mutable.add(Button.SELECT);

        assertEquals(Set.of(Button.A), snapshot.buttons(0));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.buttons(0).add(Button.B));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.players().set(0, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot.buttons(-1));
        assertThrows(IllegalArgumentException.class, () -> snapshot.buttons(4));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerInputSnapshot.of(List.of(Set.of())));
        assertTrue(PlayerInputSource.RELEASED.sample().players().stream().allMatch(Set::isEmpty));
    }

    @Test
    public void independentSourcesAreUnionedAndReleasedByIdentity() {
        PlayerInputHub hub = new PlayerInputHub();
        var keyboard = hub.openSource(0);
        var gamepad = hub.openSource(0);
        var playerFour = hub.openSource(3);

        keyboard.update(Set.of(Button.A, Button.START));
        gamepad.update(Set.of(Button.A, Button.B));
        playerFour.update(Set.of(Button.LEFT));
        assertEquals(Set.of(Button.A, Button.B, Button.START), hub.sample().buttons(0));
        assertEquals(Set.of(Button.LEFT), hub.sample().buttons(3));

        keyboard.close();
        assertEquals(Set.of(Button.A, Button.B), hub.sample().buttons(0));
        gamepad.update(Set.of(Button.B));
        assertEquals(Set.of(Button.B), hub.sample().buttons(0));
        playerFour.close();
        assertTrue(hub.sample().buttons(3).isEmpty());

        assertThrows(IllegalArgumentException.class, () -> hub.openSource(4));
        assertThrows(IllegalStateException.class, () -> keyboard.update(Set.of(Button.A)));
    }

    @Test
    public void releaseAllIsIdempotentAndReportsOnlyAggregateTransitions() {
        PlayerInputHub hub = new PlayerInputHub();
        var first = hub.openSource(1);
        var second = hub.openSource(1);
        first.update(Set.of(Button.A));
        second.update(Set.of(Button.A));

        var firstClose = first.closeAndGetChange();
        assertTrue(firstClose.released().isEmpty());
        var released = hub.releaseAll();
        assertEquals(Set.of(Button.A), released.released().get(1));
        assertTrue(hub.releaseAll().released().isEmpty());
    }
}
