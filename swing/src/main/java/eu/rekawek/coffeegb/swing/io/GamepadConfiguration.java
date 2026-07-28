package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.properties.ControllerProperties;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Immutable runtime gamepad configuration.
 *
 * <p>The controller settings layer owns persistence. This SDL-free value is the hand-off to the
 * polling thread and defensively validates assignments even when a caller bypasses Preferences.
 */
public record GamepadConfiguration(
        List<ControllerProperties.GamepadAssignment> assignments,
        Map<String, Tuning> tuningByStableId) {

    private static final Pattern STABLE_ID = Pattern.compile("sdl-[0-9a-f]{64}");

    public GamepadConfiguration {
        Objects.requireNonNull(assignments, "assignments");
        Objects.requireNonNull(tuningByStableId, "tuningByStableId");

        assignments = assignments.stream()
                .map(assignment -> Objects.requireNonNull(assignment, "assignment"))
                .sorted(java.util.Comparator.comparingInt(
                        ControllerProperties.GamepadAssignment::getPlayer))
                .toList();
        var players = new HashSet<Integer>();
        var selectors = new HashSet<String>();
        for (var assignment : assignments) {
            if (!players.add(assignment.getPlayer())) {
                throw new IllegalArgumentException(
                        "P" + (assignment.getPlayer() + 1)
                                + " has more than one gamepad assignment");
            }
            if (!selectors.add(assignment.getSelector())) {
                throw new IllegalArgumentException(
                        "Gamepad selector " + assignment.getSelector()
                                + " is assigned to multiple players");
            }
        }

        var sortedTuning = new TreeMap<String, Tuning>();
        tuningByStableId.forEach((stableId, tuning) -> {
            Objects.requireNonNull(stableId, "stableId");
            Objects.requireNonNull(tuning, "tuning");
            if (!STABLE_ID.matcher(stableId).matches()) {
                throw new IllegalArgumentException(
                        "Gamepad tuning key must be sdl- followed by 64 lowercase hex digits");
            }
            sortedTuning.put(stableId, tuning);
        });
        tuningByStableId =
                Collections.unmodifiableMap(new LinkedHashMap<>(sortedTuning));
    }

    public GamepadConfiguration(
            List<ControllerProperties.GamepadAssignment> assignments) {
        this(assignments, Map.of());
    }

    public static GamepadConfiguration from(ControllerProperties.PlayerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        return new GamepadConfiguration(mapping.getGamepads());
    }

    public Tuning tuningFor(String stableId) {
        return tuningByStableId.getOrDefault(stableId, Tuning.DEFAULT);
    }

    /**
     * Raw SDL axis thresholds preserve Coffee GB's existing input behavior exactly.
     *
     * <p>Movement controls the left-stick digital directions. Tilt controls the P1 right stick.
     * Values are strictly below the maximum positive SDL axis value so full deflection remains
     * reachable.
     */
    public record Tuning(
            int movementDeadZone,
            int tiltDeadZone,
            boolean invertMovementX,
            boolean invertMovementY,
            boolean invertTiltX,
            boolean invertTiltY) {

        public static final int DEFAULT_MOVEMENT_DEAD_ZONE = 16_384;
        public static final int DEFAULT_TILT_DEAD_ZONE = 4_096;
        public static final int MAX_DEAD_ZONE = 32_766;
        public static final Tuning DEFAULT =
                new Tuning(
                        DEFAULT_MOVEMENT_DEAD_ZONE,
                        DEFAULT_TILT_DEAD_ZONE,
                        false,
                        false,
                        false,
                        false);

        public Tuning {
            validateDeadZone(movementDeadZone, "Movement");
            validateDeadZone(tiltDeadZone, "Tilt");
        }

        private static void validateDeadZone(int value, String label) {
            if (value < 0 || value > MAX_DEAD_ZONE) {
                throw new IllegalArgumentException(
                        label + " dead zone must be between 0 and " + MAX_DEAD_ZONE);
            }
        }
    }
}
