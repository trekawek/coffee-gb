package eu.rekawek.coffeegb.swing.io;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free, UI-safe view of the devices owned by the gamepad polling thread.
 *
 * <p>Snapshots never expose SDL handles or mutable backend collections. Swing may read this value
 * on the EDT without performing device discovery there.
 */
public final class GamepadCatalog {

    public enum Status {
        STARTING,
        AVAILABLE,
        UNAVAILABLE,
        STOPPED
    }

    public record Device(String stableId, String name, Integer assignedPlayer) {
        public Device {
            Objects.requireNonNull(stableId, "stableId");
            name = name == null || name.isBlank() ? "Unknown game controller" : name;
            if (assignedPlayer != null && (assignedPlayer < 0 || assignedPlayer > 3)) {
                throw new IllegalArgumentException("Assigned player must be P1 through P4");
            }
        }
    }

    public record Snapshot(Status status, List<Device> devices, String message) {
        public Snapshot {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(devices, "devices");
            Objects.requireNonNull(message, "message");
            devices = devices.stream()
                    .sorted(Comparator.comparing(Device::stableId))
                    .toList();
        }
    }

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(Status.STARTING, List.of(), ""));

    public Snapshot snapshot() {
        return current.get();
    }

    void publishAvailable(List<Device> devices) {
        current.set(new Snapshot(Status.AVAILABLE, devices, ""));
    }

    void publishUnavailable() {
        current.set(new Snapshot(
                Status.UNAVAILABLE,
                List.of(),
                "Game controllers are unavailable. Keyboard input remains available."));
    }

    void publishStopped() {
        current.set(new Snapshot(Status.STOPPED, List.of(), ""));
    }
}
