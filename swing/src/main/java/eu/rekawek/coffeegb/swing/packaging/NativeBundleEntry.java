package eu.rekawek.coffeegb.swing.packaging;

import java.util.Objects;

/** One immutable, size- and digest-locked native library mapping. */
public record NativeBundleEntry(
        NativeComponent component,
        String resourcePath,
        String relativeOutputPath,
        long byteSize,
        String sha256) {

    public NativeBundleEntry {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(relativeOutputPath, "relativeOutputPath");
        Objects.requireNonNull(sha256, "sha256");
    }
}
