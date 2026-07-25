package eu.rekawek.coffeegb.core.joypad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Test-only future-facing four-player input fixture; it is not a production input abstraction. */
final class FakeFourPlayerInputSource {

    private static final int PLAYERS = 4;

    private final List<EnumSet<Button>> pressed = new ArrayList<>(PLAYERS);

    private final int[] samples = new int[PLAYERS];

    FakeFourPlayerInputSource() {
        for (int i = 0; i < PLAYERS; i++) {
            pressed.add(EnumSet.noneOf(Button.class));
        }
    }

    void setPressed(int player, Button... buttons) {
        checkPlayer(player);
        EnumSet<Button> state = EnumSet.noneOf(Button.class);
        Collections.addAll(state, buttons);
        pressed.set(player, state);
    }

    Set<Button> sample(int player) {
        checkPlayer(player);
        samples[player]++;
        return Collections.unmodifiableSet(EnumSet.copyOf(pressed.get(player)));
    }

    int sampleCount(int player) {
        checkPlayer(player);
        return samples[player];
    }

    void disconnect(int player) {
        checkPlayer(player);
        pressed.get(player).clear();
    }

    String diagnostic() {
        StringBuilder result = new StringBuilder();
        for (int player = 0; player < PLAYERS; player++) {
            if (player > 0) {
                result.append(';');
            }
            result.append('P').append(player + 1).append('=')
                    .append(pressed.get(player).stream().map(Enum::name).sorted().toList())
                    .append(" samples=").append(samples[player]);
        }
        return result.toString();
    }

    private static void checkPlayer(int player) {
        if (player < 0 || player >= PLAYERS) {
            throw new IllegalArgumentException("Player must be in 0..3");
        }
    }
}
