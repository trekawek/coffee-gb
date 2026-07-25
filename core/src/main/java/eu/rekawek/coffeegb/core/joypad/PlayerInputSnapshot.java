package eu.rekawek.coffeegb.core.joypad;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, deeply owned physical-button sample for exactly P1 through P4. */
public final class PlayerInputSnapshot {

    private static final PlayerInputSnapshot RELEASED =
            new PlayerInputSnapshot(List.of(Set.of(), Set.of(), Set.of(), Set.of()));

    private final List<Set<Button>> players;

    private PlayerInputSnapshot(List<Set<Button>> players) {
        this.players = players;
    }

    public static PlayerInputSnapshot released() {
        return RELEASED;
    }

    public static PlayerInputSnapshot of(
            List<? extends Collection<Button>> playerButtons) {
        Objects.requireNonNull(playerButtons, "playerButtons");
        if (playerButtons.size() != PlayerInputSource.PLAYER_COUNT) {
            throw new IllegalArgumentException("Exactly four logical player slots are required");
        }
        List<Set<Button>> copy = new ArrayList<>(PlayerInputSource.PLAYER_COUNT);
        for (int player = 0; player < PlayerInputSource.PLAYER_COUNT; player++) {
            Collection<Button> buttons = Objects.requireNonNull(
                    playerButtons.get(player), "playerButtons[" + player + "]");
            EnumSet<Button> owned = EnumSet.noneOf(Button.class);
            for (Button button : buttons) {
                owned.add(Objects.requireNonNull(button,
                        "playerButtons[" + player + "] contains null"));
            }
            copy.add(Collections.unmodifiableSet(owned));
        }
        return new PlayerInputSnapshot(List.copyOf(copy));
    }

    public Set<Button> buttons(int player) {
        checkPlayer(player);
        return players.get(player);
    }

    public List<Set<Button>> players() {
        return players;
    }

    public PlayerInputSnapshot withoutPrimary() {
        if (players.get(0).isEmpty()) {
            return this;
        }
        return of(List.of(Set.of(), players.get(1), players.get(2), players.get(3)));
    }

    static void checkPlayer(int player) {
        if (player < 0 || player >= PlayerInputSource.PLAYER_COUNT) {
            throw new IllegalArgumentException("Logical player index must be in 0..3: " + player);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlayerInputSnapshot that && players.equals(that.players);
    }

    @Override
    public int hashCode() {
        return players.hashCode();
    }

    @Override
    public String toString() {
        return players.toString();
    }
}
