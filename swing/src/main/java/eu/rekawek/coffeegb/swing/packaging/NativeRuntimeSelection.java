package eu.rekawek.coffeegb.swing.packaging;

import java.util.Objects;
import java.util.Optional;

/** Explicit target bundle or the backwards-compatible portable loading path. */
public interface NativeRuntimeSelection {

    record TargetBundle(NativeRuntimeBundle bundle) implements NativeRuntimeSelection {
        public TargetBundle {
            Objects.requireNonNull(bundle, "bundle");
        }
    }

    record Portable(Optional<NativeBundleFailure> fallbackCause)
            implements NativeRuntimeSelection {
        public Portable {
            fallbackCause = Objects.requireNonNull(fallbackCause, "fallbackCause");
        }

        public static Portable normal() {
            return new Portable(Optional.empty());
        }

        public static Portable after(NativeBundleFailure failure) {
            return new Portable(Optional.of(failure));
        }
    }
}
