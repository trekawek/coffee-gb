package eu.rekawek.coffeegb.core.joypad;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-safe source-identity aggregator used by desktop input adapters.
 *
 * <p>Each handle represents one independently releasable physical source. The logical state is
 * the set union of every source assigned to that player, so disconnecting one device cannot
 * release a button that a different device still holds.
 */
public final class PlayerInputHub implements PlayerInputSource {

    private final Map<Long, SourceState> sources = new HashMap<>();

    private long nextToken;

    private volatile PlayerInputSnapshot snapshot = PlayerInputSnapshot.released();

    public synchronized SourceHandle openSource(int player) {
        PlayerInputSnapshot.checkPlayer(player);
        long token = nextToken++;
        sources.put(token, new SourceState(player));
        return new SourceHandle(token, player);
    }

    @Override
    public PlayerInputSnapshot sample() {
        return snapshot;
    }

    /** Releases every source, used for global focus loss and emulator shutdown. */
    public synchronized InputChange releaseAll() {
        PlayerInputSnapshot before = snapshot;
        sources.values().forEach(state -> state.buttons.clear());
        rebuildSnapshot();
        return InputChange.between(before, snapshot);
    }

    private synchronized InputChange update(long token, Collection<Button> pressed) {
        SourceState state = requireOpen(token);
        EnumSet<Button> next = EnumSet.noneOf(Button.class);
        for (Button button : Objects.requireNonNull(pressed, "pressed")) {
            next.add(Objects.requireNonNull(button, "pressed contains null"));
        }
        PlayerInputSnapshot before = snapshot;
        state.buttons.clear();
        state.buttons.addAll(next);
        rebuildSnapshot();
        return InputChange.between(before, snapshot);
    }

    private synchronized InputChange close(long token) {
        PlayerInputSnapshot before = snapshot;
        SourceState state = sources.remove(token);
        if (state == null) {
            return InputChange.NONE;
        }
        rebuildSnapshot();
        return InputChange.between(before, snapshot);
    }

    private SourceState requireOpen(long token) {
        SourceState state = sources.get(token);
        if (state == null) {
            throw new IllegalStateException("Input source is closed");
        }
        return state;
    }

    private void rebuildSnapshot() {
        var players = java.util.stream.IntStream.range(0, PLAYER_COUNT)
                .mapToObj(ignored -> EnumSet.noneOf(Button.class))
                .toList();
        for (SourceState source : sources.values()) {
            players.get(source.player).addAll(source.buttons);
        }
        snapshot = PlayerInputSnapshot.of(players);
    }

    private static final class SourceState {
        private final int player;
        private final EnumSet<Button> buttons = EnumSet.noneOf(Button.class);

        private SourceState(int player) {
            this.player = player;
        }
    }

    public final class SourceHandle implements AutoCloseable {
        private final long token;
        private final int player;
        private boolean closed;

        private SourceHandle(long token, int player) {
            this.token = token;
            this.player = player;
        }

        public int player() {
            return player;
        }

        public synchronized InputChange update(Collection<Button> pressed) {
            if (closed) {
                throw new IllegalStateException("Input source is closed");
            }
            return PlayerInputHub.this.update(token, pressed);
        }

        @Override
        public synchronized void close() {
            closeAndGetChange();
        }

        public synchronized InputChange closeAndGetChange() {
            if (closed) {
                return InputChange.NONE;
            }
            closed = true;
            return PlayerInputHub.this.close(token);
        }
    }

    /** Aggregate transitions produced by a source update or release. */
    public record InputChange(Map<Integer, Set<Button>> pressed,
                              Map<Integer, Set<Button>> released) {

        private static final InputChange NONE = new InputChange(Map.of(), Map.of());

        public InputChange {
            pressed = copyChanges(pressed);
            released = copyChanges(released);
        }

        private static Map<Integer, Set<Button>> copyChanges(Map<Integer, Set<Button>> changes) {
            Map<Integer, Set<Button>> copy = new HashMap<>();
            Objects.requireNonNull(changes, "changes").forEach((player, buttons) -> {
                PlayerInputSnapshot.checkPlayer(player);
                copy.put(player, Set.copyOf(buttons));
            });
            return Map.copyOf(copy);
        }

        private static InputChange between(PlayerInputSnapshot before, PlayerInputSnapshot after) {
            Map<Integer, Set<Button>> pressed = new HashMap<>();
            Map<Integer, Set<Button>> released = new HashMap<>();
            for (int player = 0; player < PLAYER_COUNT; player++) {
                EnumSet<Button> added = EnumSet.noneOf(Button.class);
                added.addAll(after.buttons(player));
                added.removeAll(before.buttons(player));
                if (!added.isEmpty()) {
                    pressed.put(player, Set.copyOf(added));
                }
                EnumSet<Button> removed = EnumSet.noneOf(Button.class);
                removed.addAll(before.buttons(player));
                removed.removeAll(after.buttons(player));
                if (!removed.isEmpty()) {
                    released.put(player, Set.copyOf(removed));
                }
            }
            if (pressed.isEmpty() && released.isEmpty()) {
                return NONE;
            }
            return new InputChange(pressed, released);
        }
    }
}
