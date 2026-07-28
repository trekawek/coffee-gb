package eu.rekawek.coffeegb.swing.packaging;

import java.util.List;
import java.util.Objects;

/** Typed, non-throwing failures from target selection and native extraction. */
public interface NativeBundleFailure {

    record UnsupportedTarget(String requestedTarget, List<String> supportedTargets)
            implements NativeBundleFailure {
        public UnsupportedTarget {
            Objects.requireNonNull(requestedTarget, "requestedTarget");
            supportedTargets = List.copyOf(supportedTargets);
        }
    }

    record MissingNative(NativeTarget target, NativeComponent component)
            implements NativeBundleFailure {
        public MissingNative {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(component, "component");
        }
    }

    record NativeSourceNotConfigured(NativeTarget target)
            implements NativeBundleFailure {
        public NativeSourceNotConfigured {
            Objects.requireNonNull(target, "target");
        }
    }

    record NativeCacheBusy(NativeTarget target, String operation)
            implements NativeBundleFailure {
        public NativeCacheBusy {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(operation, "operation");
        }
    }

    record InvalidManifest(NativeTarget target, NativeComponent component, String reason)
            implements NativeBundleFailure {
        public InvalidManifest {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(reason, "reason");
        }
    }

    record IntegrityMismatch(NativeTarget target, NativeComponent component, String reason)
            implements NativeBundleFailure {
        public IntegrityMismatch {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(reason, "reason");
        }
    }

    record IoFailure(NativeTarget target, String operation)
            implements NativeBundleFailure {
        public IoFailure {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(operation, "operation");
        }
    }
}
