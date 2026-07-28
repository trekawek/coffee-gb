package eu.rekawek.coffeegb.swing.packaging;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Verified immutable native files for one explicitly selected package target. */
public record NativeRuntimeBundle(
        NativeTarget target,
        Path root,
        Map<NativeComponent, Path> libraries,
        GamepadNativeSupport gamepadSupport) {

    public NativeRuntimeBundle {
        Objects.requireNonNull(target, "target");
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        libraries = Map.copyOf(libraries);
        Objects.requireNonNull(gamepadSupport, "gamepadSupport");
    }

    public Path library(NativeComponent component) {
        Path result = libraries.get(component);
        if (result == null) {
            throw new IllegalArgumentException("Native component is not bundled: " + component.id());
        }
        return result;
    }
}
