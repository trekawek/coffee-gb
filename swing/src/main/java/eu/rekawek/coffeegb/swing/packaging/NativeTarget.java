package eu.rekawek.coffeegb.swing.packaging;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A package build target, deliberately independent from the host running this JVM.
 *
 * <p>Callers must select one of these IDs explicitly. Packaging must never silently turn
 * {@code os.name} or {@code os.arch} into a release target.
 */
public enum NativeTarget {
    LINUX_X86_64("linux-x86-64"),
    WINDOWS_X86_64("windows-x86-64"),
    MACOS_X86_64("macos-x86-64"),
    MACOS_AARCH64("macos-aarch64");

    private final String id;

    NativeTarget(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<NativeTarget> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(target -> target.id.equals(id)).findFirst();
    }

    public static List<String> supportedIds() {
        return Arrays.stream(values()).map(NativeTarget::id).toList();
    }
}
