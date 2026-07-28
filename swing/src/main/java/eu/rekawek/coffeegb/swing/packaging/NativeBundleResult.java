package eu.rekawek.coffeegb.swing.packaging;

import java.util.Objects;

/** Result of resolving one concrete manifest, before portable fallback is applied. */
public interface NativeBundleResult {

    record Ready(NativeRuntimeBundle bundle) implements NativeBundleResult {
        public Ready {
            Objects.requireNonNull(bundle, "bundle");
        }
    }

    record Failed(NativeBundleFailure failure) implements NativeBundleResult {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
